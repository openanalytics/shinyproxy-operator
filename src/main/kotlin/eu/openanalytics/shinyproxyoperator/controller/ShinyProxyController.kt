/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2020 Open Analytics
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
package eu.openanalytics.shinyproxyoperator.controller

import eu.openanalytics.shinyproxyoperator.ShinyProxyClient
import eu.openanalytics.shinyproxyoperator.components.ConfigMapFactory
import eu.openanalytics.shinyproxyoperator.components.LabelFactory
import eu.openanalytics.shinyproxyoperator.components.ReplicaSetFactory
import eu.openanalytics.shinyproxyoperator.components.ServiceFactory
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyList
import eu.openanalytics.shinyproxyoperator.ingres.IIngressController
import eu.openanalytics.shinyproxyoperator.ingress.skipper.IngressController
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.networking.v1beta1.Ingress
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.fabric8.kubernetes.client.informers.cache.Lister
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KotlinLogging
import kotlin.system.exitProcess


class ShinyProxyController(private val channel: Channel<ShinyProxyEvent>,
                           private val kubernetesClient: KubernetesClient,
                           private val shinyProxyClient: ShinyProxyClient,
                           private val replicaSetInformer: SharedIndexInformer<ReplicaSet>,
                           private val shinyProxyInformer: SharedIndexInformer<ShinyProxy>,
                           private val ingressController: IIngressController,
                           private val resourceRetriever: ResourceRetriever,
                           private val shinyProxyLister: Lister<ShinyProxy>) {

    private val configMapFactory = ConfigMapFactory(kubernetesClient)
    private val serviceFactory = ServiceFactory(kubernetesClient)
    private val replicaSetFactory = ReplicaSetFactory(kubernetesClient)

    private val logger = KotlinLogging.logger {}

    suspend fun run() {
        logger.info("Starting PodSet controller")
        while (!replicaSetInformer.hasSynced() || !shinyProxyInformer.hasSynced()) {
            // Wait till Informer syncs
        }
        GlobalScope.launch { scheduleAdditionalEvents() }
        while (true) {
            try {
                val event = channel.receive()

                try {
                    when (event.eventType) {
                        ShinyProxyEventType.ADD -> {
                            if (event.shinyProxy == null) {
                                logger.warn { "Event of type ADD should have shinyproxy attached to it." }
                                continue
                            }
                            val newInstance = createNewInstance(event.shinyProxy)
                            reconcileSingleShinyProxyInstance(event.shinyProxy, newInstance)
                        }
                        ShinyProxyEventType.UPDATE_SPEC -> {
                            if (event.shinyProxy == null) {
                                logger.warn { "Event of type UPDATE_SPEC should have shinyproxy attached to it." }
                                continue
                            }
                            val newInstance = createNewInstance(event.shinyProxy)
                            reconcileSingleShinyProxyInstance(event.shinyProxy, newInstance)
                        }
                        ShinyProxyEventType.DELETE -> {
                            // DELETE is not needed
                        }
                        ShinyProxyEventType.RECONCILE -> {
                            if (event.shinyProxy == null) {
                                logger.warn { "Event of type RECONCILE should have shinyProxy attached to it." }
                                continue
                            }
                            if (event.shinyProxyInstance == null) {
                                logger.warn { "Event of type RECONCILE should have shinyProxyInstance attached to it." }
                                continue
                            }
                            reconcileSingleShinyProxyInstance(event.shinyProxy, event.shinyProxyInstance)
                        }
                        ShinyProxyEventType.CHECK_OBSOLETE_INSTANCES -> {
                            checkForObsoleteInstances()
                        }
                    }
                } catch (e: KubernetesClientException) {
                    logger.warn(e) { "Caught KubernetesClientException while processing event $event. Exiting process." }
                    exitProcess(1)
                } catch (e: Exception) {
                    logger.warn(e) { "Caught an exception while processing event $event. Continuing processing other events." }
                }
            } catch (interruptedException: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.warn { "controller interrupted.." }
            }
        }
    }

    private fun createNewInstance(shinyProxy: ShinyProxy): ShinyProxyInstance {
        val existingInstance = shinyProxy.status.getInstanceByHash(shinyProxy.hashOfCurrentSpec)

        if (existingInstance != null && existingInstance.isLatestInstance == true) {
            logger.warn { "Trying to create new instance which already exists and is the latest instance" }
            return existingInstance
        } else if (existingInstance != null && existingInstance.isLatestInstance == false) {
            // make the old existing instance again the latest instance
            shinyProxy.status.instances.forEach { it.isLatestInstance = false }
            existingInstance.isLatestInstance = true
            shinyProxyClient.updateStatus(shinyProxy)
            ingressController.reconcile(shinyProxy)
            return existingInstance
        }

        // create new instance to replace old ones
        val newInstance = ShinyProxyInstance()
        newInstance.hashOfSpec = shinyProxy.hashOfCurrentSpec
        newInstance.isLatestInstance = true
        shinyProxy.status.instances.forEach { it.isLatestInstance = false }
        shinyProxy.status.instances.add(newInstance)
        shinyProxyClient.inNamespace(shinyProxy.metadata.namespace).updateStatus(shinyProxy)

        return newInstance
    }

    private suspend fun reconcileSingleShinyProxyInstance(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance) {
        logger.info { "ReconcileSingleShinyProxy: ${shinyProxy.metadata.name} ${shinyProxyInstance.hashOfSpec}" }

        if (shinyProxyInstance.hashOfSpec == null) {
            logger.warn { "Cannot reconcile ShinProxyInstance $shinyProxyInstance because it has no hash." }
            return
        }

        if (!shinyProxy.status.instances.contains(shinyProxyInstance)) {
            logger.info { "Cannot reconcile ShinProxyInstance ${shinyProxyInstance.hashOfSpec} because it is begin deleted." }
            return
        }

        val configMaps = resourceRetriever.getConfigMapByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance), shinyProxy.metadata.namespace)
        if (configMaps.isEmpty()) {
            logger.debug { "0 ConfigMaps found -> creating ConfigMap" }
            configMapFactory.create(shinyProxy, shinyProxyInstance)
            return
        }

        val replicaSets = resourceRetriever.getReplicaSetByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance), shinyProxy.metadata.namespace)
        if (replicaSets.isEmpty()) {
            logger.debug { "0 ReplicaSets found -> creating ReplicaSet" }
            replicaSetFactory.create(shinyProxy, shinyProxyInstance)
            ingressController.reconcile(shinyProxy)
            return
        }

        val services = resourceRetriever.getServiceByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance), shinyProxy.metadata.namespace)
        if (services.isEmpty()) {
            logger.debug { "0 Services found -> creating Service" }
            serviceFactory.create(shinyProxy, shinyProxyInstance)
            return
        }

        ingressController.reconcile(shinyProxy)
    }

    private fun checkForObsoleteInstances() {
        for (shinyProxy in shinyProxyLister.list()) {
            if (shinyProxy.status.instances.size > 1) {
                // this SP has more than one instance -> check if some of them are obsolete
                // take a copy of the list to check to prevent concurrent modification
                val instancesToCheck = shinyProxy.status.instances.toList()
                for (shinyProxyInstance in instancesToCheck) {
                    if (shinyProxyInstance.isLatestInstance == true) continue
                    val hashOfSpec: String = shinyProxyInstance.hashOfSpec ?: continue
                    val pods = resourceRetriever.getPodByLabels(
                            mapOf(
                                    LabelFactory.PROXIED_APP to "true",
                                    LabelFactory.INSTANCE_LABEL to hashOfSpec
                            )
                    )

                    if (pods.isEmpty()) {
                        logger.info { "ShinyProxyInstance ${shinyProxyInstance.hashOfSpec} has no running apps and is not the latest version => removing this instance" }
                        deleteSingleShinyProxyInstance(shinyProxy, shinyProxyInstance)
                        shinyProxy.status.instances.remove(shinyProxyInstance)
                        shinyProxyClient.inNamespace(shinyProxy.metadata.namespace).updateStatus(shinyProxy)
                    }
                }
            }
        }
    }

    // TODO timer and extract from this class?
    private suspend fun scheduleAdditionalEvents() {
        while (true) {
            channel.send(ShinyProxyEvent(ShinyProxyEventType.CHECK_OBSOLETE_INSTANCES, null, null))
            delay(3000)
        }
    }

    private fun deleteSingleShinyProxyInstance(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance) {
        logger.info { "DeleteSingleShinyProxyInstance: ${shinyProxy.metadata.name} ${shinyProxyInstance.hashOfSpec}" }
        for (service in resourceRetriever.getServiceByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance), shinyProxy.metadata.namespace)) {
            kubernetesClient.resource(service).delete()
        }
        for (replicaSet in resourceRetriever.getReplicaSetByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance), shinyProxy.metadata.namespace)) {
            kubernetesClient.resource(replicaSet).delete()
        }
        for (configMap in resourceRetriever.getConfigMapByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance), shinyProxy.metadata.namespace)) {
            kubernetesClient.resource(configMap).delete()
        }
    }


}
