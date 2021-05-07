/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021 Open Analytics
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
package eu.openanalytics.shinyproxyoperator

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import eu.openanalytics.shinyproxyoperator.controller.*
import eu.openanalytics.shinyproxyoperator.crd.DoneableShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyList
import eu.openanalytics.shinyproxyoperator.ingress.skipper.IngressController
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapList
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.ServiceList
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.apps.ReplicaSetList
import io.fabric8.kubernetes.api.model.networking.v1beta1.Ingress
import io.fabric8.kubernetes.api.model.networking.v1beta1.IngressList
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.fabric8.kubernetes.client.NamespacedKubernetesClient
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext
import io.fabric8.kubernetes.client.dsl.base.OperationContext
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.fabric8.kubernetes.client.informers.SharedInformerFactory
import io.fabric8.kubernetes.client.informers.cache.Lister
import io.fabric8.kubernetes.client.utils.Serialization
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import mu.KotlinLogging
import org.slf4j.LoggerFactory
import java.lang.IllegalStateException
import kotlin.system.exitProcess


class Operator(client: NamespacedKubernetesClient? = null,
               mode: Mode? = null,
               disableSecureCookies: Boolean? = null,
               reconcileListener: IReconcileListener? = null,
               probeInitialDelay: Int? = null,
               probeFailureThreshold: Int? = null,
               probeTimeout: Int? = null,
               startupProbeInitialDelay: Int? = null,
               logLevel: Level? = null) {

    private val logger = KotlinLogging.logger {}
    private val client: NamespacedKubernetesClient
    val mode: Mode
    val namespace: String
    val disableSecureCookies: Boolean
    val probeInitialDelay: Int
    val probeFailureThreshold: Int
    val probeTimeout: Int
    val startupProbeInitialDelay: Int

    private val podSetCustomResourceDefinitionContext = CustomResourceDefinitionContext.Builder()
            .withVersion("v1alpha1")
            .withScope("Namespaced")
            .withGroup("openanalytics.eu")
            .withPlural("shinyproxies")
            .build()

    private val informerFactory: SharedInformerFactory
    private val replicaSetInformer: SharedIndexInformer<ReplicaSet>
    private val serviceInformer: SharedIndexInformer<Service>
    private val configMapInformer: SharedIndexInformer<ConfigMap>
    private val ingressInformer: SharedIndexInformer<Ingress>
    private val shinyProxyInformer: SharedIndexInformer<ShinyProxy>
    val podRetriever: PodRetriever

    /**
     * Initialize mode, client, namespace and informers
     */
    init {
        Serialization.jsonMapper().registerKotlinModule()
        Serialization.yamlMapper().registerKotlinModule()
        if (client != null) {
            this.client = client
        } else {
            this.client = DefaultKubernetesClient()
        }

        this.mode = readConfigValue(mode, Mode.CLUSTERED, "SPO_MODE") {
            when (it.toLowerCase()) {
                "clustered" -> Mode.CLUSTERED
                "namespaced" -> Mode.NAMESPACED
                else -> error("Unsupported operator mode: $it")
            }
        }
        this.disableSecureCookies = readConfigValue(disableSecureCookies, false, "SPO_DISABLE_SECURE_COOKIES", { true })
        this.probeInitialDelay = readConfigValue(probeInitialDelay, 0, "SPO_PROBE_INITIAL_DELAY", String::toInt)
        this.probeFailureThreshold = readConfigValue(probeFailureThreshold, 0, "SPO_PROBE_FAILURE_THRESHOLD", String::toInt)
        this.probeTimeout = readConfigValue(probeTimeout, 1, "SPO_PROBE_TIMEOUT", String::toInt)
        this.startupProbeInitialDelay = readConfigValue(startupProbeInitialDelay, 60, "SPO_STARTUP_PROBE_INITIAL_DELAY", String::toInt)

        val rootLogger = LoggerFactory.getILoggerFactory().getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        rootLogger.level = readConfigValue(logLevel, Level.DEBUG, "SPO_LOG_LEVEL", { Level.toLevel(it) })

        logger.info { "Running in ${this.mode} mode" }

        namespace = if (this.client.namespace == null) {
            logger.info { "No namespace found via config, assuming default." }
            "default"
        } else {
            this.client.namespace
        }
        logger.info { "Using namespace : $namespace " }

        informerFactory = when (this.mode) {
            Mode.CLUSTERED -> this.client.inAnyNamespace().informers()
            Mode.NAMESPACED -> this.client.inNamespace(namespace).informers()
        }

        if (this.mode == Mode.CLUSTERED) {
            replicaSetInformer = informerFactory.sharedIndexInformerFor(ReplicaSet::class.java, ReplicaSetList::class.java, 10 * 60 * 1000.toLong())
            serviceInformer = informerFactory.sharedIndexInformerFor(Service::class.java, ServiceList::class.java, 10 * 60 * 1000.toLong())
            configMapInformer = informerFactory.sharedIndexInformerFor(ConfigMap::class.java, ConfigMapList::class.java, 10 * 60 * 1000.toLong())
            ingressInformer = informerFactory.sharedIndexInformerFor(Ingress::class.java, IngressList::class.java, 10 * 60 * 1000.toLong())
            shinyProxyInformer = informerFactory.sharedIndexInformerForCustomResource(podSetCustomResourceDefinitionContext, ShinyProxy::class.java, ShinyProxyList::class.java, 10 * 60 * 1000)
            podRetriever = PodRetriever(this.client)
        } else {
            val operationContext = OperationContext().withNamespace(namespace)
            replicaSetInformer = informerFactory.sharedIndexInformerFor(ReplicaSet::class.java, ReplicaSetList::class.java, operationContext, 10 * 60 * 1000.toLong())
            serviceInformer = informerFactory.sharedIndexInformerFor(Service::class.java, ServiceList::class.java, operationContext, 10 * 60 * 1000.toLong())
            configMapInformer = informerFactory.sharedIndexInformerFor(ConfigMap::class.java, ConfigMapList::class.java, operationContext, 10 * 60 * 1000.toLong())
            ingressInformer = informerFactory.sharedIndexInformerFor(Ingress::class.java, IngressList::class.java, operationContext, 10 * 60 * 1000.toLong())
            shinyProxyInformer = informerFactory.sharedIndexInformerForCustomResource(podSetCustomResourceDefinitionContext, ShinyProxy::class.java, ShinyProxyList::class.java, operationContext, 10 * 60 * 1000)
            podRetriever = PodRetriever(this.client)
        }

    }

    /**
     * Main Components
     */
    private val shinyProxyClient = when (this.mode) {
        Mode.CLUSTERED -> this.client.customResources(podSetCustomResourceDefinitionContext, ShinyProxy::class.java, ShinyProxyList::class.java, DoneableShinyProxy::class.java)
        Mode.NAMESPACED -> this.client.inNamespace(namespace).customResources(podSetCustomResourceDefinitionContext, ShinyProxy::class.java, ShinyProxyList::class.java, DoneableShinyProxy::class.java)
    }
    private val channel = Channel<ShinyProxyEvent>(10000)
    val sendChannel: SendChannel<ShinyProxyEvent> = channel // public for tests

    /**
     * Listers
     */
    private val shinyProxyLister = Lister(shinyProxyInformer.indexer)
    private val replicaSetLister = Lister(replicaSetInformer.indexer)
    private val configMapLister = Lister(configMapInformer.indexer)
    private val serviceLister = Lister(serviceInformer.indexer)
    private val ingressLister = Lister(ingressInformer.indexer)

    /**
     * Listeners
     * Note: it is normal that these are unused, since they only perform background processing
     */
    private val shinyProxyListener = ShinyProxyListener(sendChannel, shinyProxyInformer, shinyProxyLister)
    private val replicaSetListener = ResourceListener(sendChannel, replicaSetInformer, shinyProxyLister)
    private val serviceListener = ResourceListener(sendChannel, serviceInformer, shinyProxyLister)
    private val configMapListener = ResourceListener(sendChannel, configMapInformer, shinyProxyLister)

    /**
     * Helpers
     */
    private val resourceRetriever = ResourceRetriever(replicaSetLister, configMapLister, serviceLister, ingressLister)

    /**
     * Controllers
     */
    private val ingressController = IngressController(channel, ingressInformer, shinyProxyLister, this.client, resourceRetriever)
    val shinyProxyController = ShinyProxyController(channel, this.client, shinyProxyClient, replicaSetInformer, shinyProxyInformer, ingressController, resourceRetriever, shinyProxyLister, podRetriever, reconcileListener)


    fun prepare() {
        if (client.customResourceDefinitions().withName("shinyproxies.openanalytics.eu").get() == null) {
            println()
            println()
            println("ERROR: the CustomResourceDefinition (CRD) of the Operator does not exist!")
            println("The name of the CRD is 'shinyproxies.openanalytics.eu'")
            println("Create the CRD first, before starting the operator")
            println()
            println("Exiting in 10 seconds because of the above error")
            Thread.sleep(10000) // sleep 10 seconds to make it easier to find this error by an sysadmin

            exitProcess(2)
        }

        informerFactory.startAllRegisteredInformers()

        informerFactory.addSharedInformerEventListener {
            // TODO exit when KubernetesClientException ?
            logger.warn(it) { "Exception occurred, but caught $it" }
        }

        while (!replicaSetInformer.hasSynced() || !shinyProxyInformer.hasSynced()) {
            // Wait till Informer syncs
        }

    }

    suspend fun run() {
        prepare()
        shinyProxyController.run()
    }

    companion object {
        private var _operatorInstance: Operator? = null

        fun setOperatorInstance(operator: Operator) {
            this._operatorInstance = operator
       }

        fun getOperatorInstance(): Operator {
            _operatorInstance.let {
                if (it == null) {
                    throw IllegalStateException("Cannot query for operatorInstance when it is not set")
                } else {
                    return it
                }
            }
        }
    }

    private fun<T> readConfigValue(constructorValue: T?, default: T, envVarName: String, convertor: (String) -> T): T {
        val e = System.getenv(envVarName)
        val res = when {
            constructorValue != null -> constructorValue
            e != null -> convertor(e)
            else -> default
        }
        logger.info { "Using $res for property $envVarName" }
        return res
    }

}

enum class Mode {
    CLUSTERED,
    NAMESPACED
}