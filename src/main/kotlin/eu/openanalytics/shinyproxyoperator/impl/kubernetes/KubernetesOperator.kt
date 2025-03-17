/*
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021-2024 Open Analytics
 *
 * ===========================================================================
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License as published by
 * The Apache Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Apache License for more details.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/>
 */
package eu.openanalytics.shinyproxyoperator.impl.kubernetes

import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import eu.openanalytics.shinyproxyoperator.Config
import eu.openanalytics.shinyproxyoperator.IEventController
import eu.openanalytics.shinyproxyoperator.IOperator
import eu.openanalytics.shinyproxyoperator.IRecyclableChecker
import eu.openanalytics.shinyproxyoperator.controller.EventController
import eu.openanalytics.shinyproxyoperator.controller.RecyclableChecker
import eu.openanalytics.shinyproxyoperator.controller.ShinyProxyController
import eu.openanalytics.shinyproxyoperator.event.ShinyProxyEvent
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.controller.IngressController
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.controller.PodRetriever
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.controller.ReplicaSetStatusChecker
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.controller.ResourceListener
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.controller.ServiceController
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.crd.ShinyProxyCustomResource
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapList
import io.fabric8.kubernetes.api.model.KubernetesResourceList
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.ServiceList
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.apps.ReplicaSetList
import io.fabric8.kubernetes.api.model.networking.v1.Ingress
import io.fabric8.kubernetes.api.model.networking.v1.IngressList
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.NamespacedKubernetesClient
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.Resource
import io.fabric8.kubernetes.client.dsl.RollableScalableResource
import io.fabric8.kubernetes.client.dsl.ServiceResource
import io.fabric8.kubernetes.client.utils.Serialization
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.config.Configurator
import java.util.*
import kotlin.system.exitProcess

typealias ShinyProxyClient = MixedOperation<ShinyProxyCustomResource, KubernetesResourceList<ShinyProxyCustomResource>, Resource<ShinyProxyCustomResource>>

class KubernetesOperator(config: Config,
                         client: NamespacedKubernetesClient? = null,
                         eventController: IEventController? = null,
                         recyclableChecker: IRecyclableChecker? = null) : IOperator {

    private val logger = KotlinLogging.logger {}
    private val client = client ?: createKubernetesClient()
    private val mode: Mode
    private val namespace: String

    private val podRetriever = PodRetriever(this.client)
    private val shinyProxyClient: ShinyProxyClient
    private val replicaSetListener: ResourceListener<ReplicaSet, ReplicaSetList, RollableScalableResource<ReplicaSet>>
    private val serviceListener: ResourceListener<Service, ServiceList, ServiceResource<Service>>
    private val configMapListener: ResourceListener<ConfigMap, ConfigMapList, Resource<ConfigMap>>
    private val ingressListener: ResourceListener<Ingress, IngressList, Resource<Ingress>>
    private val serviceController: ServiceController
    private val ingressController: IngressController
    private val kubernetesSource: KubernetesSource
    val orchestrator: KubernetesOrchestrator
    private val eventController: IEventController
    val shinyProxyController: ShinyProxyController
    private val replicaSetStatusChecker: ReplicaSetStatusChecker

    private val channel = Channel<ShinyProxyEvent>(10000)
    val sendChannel: SendChannel<ShinyProxyEvent> = channel // public for tests

    /**
     * Initialize mode, client, namespace and listeners
     */
    init {
        Serialization.jsonMapper().registerKotlinModule()
        Serialization.yamlMapper().registerKotlinModule()

        mode = config.readConfigValue(Mode.CLUSTERED, "SPO_MODE") {
            when (it.lowercase(Locale.getDefault())) {
                "clustered" -> Mode.CLUSTERED
                "namespaced" -> Mode.NAMESPACED
                else -> error("Unsupported operator mode: $it")
            }
        }

        val level = config.readConfigValue(Level.DEBUG, "SPO_LOG_LEVEL") { Level.toLevel(it) }
        Configurator.setRootLevel(level)
        logger.info { "Running in $mode mode" }

        namespace = if (this.client.namespace == null) {
            logger.info { "No namespace found via config, assuming default." }
            "default"
        } else {
            this.client.namespace
        }
        logger.info { "Using namespace : $namespace " }

        this.shinyProxyClient = when (mode) {
            Mode.CLUSTERED -> this.client.inAnyNamespace().resources(ShinyProxyCustomResource::class.java)
            Mode.NAMESPACED -> this.client.inNamespace(namespace).resources(ShinyProxyCustomResource::class.java)
        }
        if (mode == Mode.CLUSTERED) {
            replicaSetListener = ResourceListener(sendChannel, this.client.inAnyNamespace().apps().replicaSets())
            serviceListener = ResourceListener(sendChannel, this.client.inAnyNamespace().services())
            configMapListener = ResourceListener(sendChannel, this.client.inAnyNamespace().configMaps())
            ingressListener = ResourceListener(sendChannel, this.client.inAnyNamespace().network().v1().ingresses())
            serviceController = ServiceController(this.client.inAnyNamespace().services(), serviceListener, replicaSetListener)
            ingressController = IngressController(this.client, ingressListener)
        } else {
            replicaSetListener = ResourceListener(sendChannel, this.client.inNamespace(namespace).apps().replicaSets())
            serviceListener = ResourceListener(sendChannel, this.client.inNamespace(namespace).services())
            configMapListener = ResourceListener(sendChannel, this.client.inNamespace(namespace).configMaps())
            ingressListener = ResourceListener(sendChannel, this.client.inNamespace(namespace).network().v1().ingresses())
            serviceController = ServiceController(this.client.inNamespace(namespace).services(), serviceListener, replicaSetListener)
            ingressController = IngressController(this.client, ingressListener)
        }
        kubernetesSource = KubernetesSource(shinyProxyClient, channel, mode, this.namespace)
        orchestrator = KubernetesOrchestrator(
            this.client,
            shinyProxyClient,
            serviceController,
            ingressController,
            kubernetesSource,
            podRetriever,
            configMapListener,
            replicaSetListener,
            config
        )
        this.eventController = eventController ?: EventController(orchestrator)
        this.shinyProxyController = ShinyProxyController(
            channel,
            orchestrator,
            kubernetesSource,
            this.eventController,
            recyclableChecker ?: RecyclableChecker(orchestrator)
        )
        replicaSetStatusChecker = ReplicaSetStatusChecker(podRetriever, kubernetesSource, this.eventController)
    }

    private fun checkCrdExists(name: String, shortName: String) {
        try {
            if (client.apiextensions().v1().customResourceDefinitions().withName(name).get() == null) {
                println()
                println()
                println("ERROR: the CustomResourceDefinition (CRD) '${shortName}' does not exist!")
                println("The name of the CRD is '${name}'")
                println("Create the CRD first, before starting the operator")
                println()
                println("Exiting in 10 seconds because of the above error")
                Thread.sleep(10000) // sleep 10 seconds to make it easier to find this error by a sysadmin

                exitProcess(2)
            }
        } catch (e: KubernetesClientException) {
            println()
            println()
            println("Warning: could not check whether $shortName CRD exits.")
            println("This is normal when the ServiceAccount of the operator does not have permission to access CRDs (at cluster scope).")
            println("If you get an unexpected error after this message, make sure that the CRD exists.")
            println()
            println()
        }

    }

    override suspend fun init() {
        checkCrdExists("shinyproxies.openanalytics.eu", "ShinyProxy")

        try {
            replicaSetListener.start()
            configMapListener.start()
            serviceListener.start()
            ingressListener.start()
            kubernetesSource.init()
            replicaSetStatusChecker.init()
            kubernetesSource.run()
        } catch (e: KubernetesClientException) {
            println()
            println()
            println("Error during starting up. Please check if all CRDs exists (see above).")
            println("Exiting in 10 seconds because of the above error")
            println()
            e.printStackTrace()
            println()
            println()
            delay(10_000) // sleep 10 seconds to make it easier to find this error by a sysadmin
            exitProcess(3)
        }
    }

    override suspend fun run() {
        try {
            shinyProxyController.run()
        } catch (e: KubernetesClientException) {
            logger.warn { "Kubernetes Client Exception : ${e.message}" }
            e.printStackTrace()
            exitProcess(1)
        }
    }

    override fun stop() {
        replicaSetStatusChecker.stop()
        replicaSetListener.stop()
        serviceListener.stop()
        configMapListener.stop()
        ingressListener.stop()
        kubernetesSource.stop()
    }

}

enum class Mode {
    CLUSTERED,
    NAMESPACED
}
