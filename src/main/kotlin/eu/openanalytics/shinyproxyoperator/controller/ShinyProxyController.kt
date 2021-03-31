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
import eu.openanalytics.shinyproxyoperator.ingres.IIngressController
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.fabric8.kubernetes.client.informers.cache.Lister
import io.fabric8.kubernetes.client.internal.readiness.Readiness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
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
                           private val shinyProxyLister: Lister<ShinyProxy>,
                           private val podRetriever: PodRetriever,
                           private val reconcileListener: IReconcileListener?) {

    private val configMapFactory = ConfigMapFactory(kubernetesClient)
    private val serviceFactory = ServiceFactory(kubernetesClient)
    private val replicaSetFactory = ReplicaSetFactory(kubernetesClient)

    private val logger = KotlinLogging.logger {}

    suspend fun run() {
        logger.info("Starting ShinyProxy Operator")
        GlobalScope.launch { scheduleAdditionalEvents() }
        while (true) {
            try {
                receiveAndHandleEvent()
            } catch (cancellationException: CancellationException) {
                logger.warn { "Controller cancelled -> stopping" }
                throw cancellationException
            }
        }
    }

    suspend fun receiveAndHandleEvent() {
        val event = channel.receive()

        try {
            when (event.eventType) {
                ShinyProxyEventType.ADD -> {
                    if (event.shinyProxy == null) {
                        logger.warn { "Event of type ADD should have shinyproxy attached to it." }
                        return
                    }
                    val newInstance = createNewInstance(event.shinyProxy)
                    reconcileSingleShinyProxyInstance(event.shinyProxy, newInstance)
                }
                ShinyProxyEventType.UPDATE_SPEC -> {
                    if (event.shinyProxy == null) {
                        logger.warn { "Event of type UPDATE_SPEC should have shinyproxy attached to it." }
                        return
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
                        return
                    }
                    if (event.shinyProxyInstance == null) {
                        logger.warn { "Event of type RECONCILE should have shinyProxyInstance attached to it." }
                        return
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
    }

    private fun createNewInstance(_shinyProxy: ShinyProxy): ShinyProxyInstance {
        val shinyProxy = refreshShinyProxy(_shinyProxy) // refresh shinyproxy to ensure status is always up to date
        val existingInstance = shinyProxy.status.getInstanceByHash(shinyProxy.hashOfCurrentSpec)

        if (existingInstance != null && existingInstance.isLatestInstance) {
            logger.warn { "${shinyProxy.logPrefix(existingInstance)} Trying to create new instance which already exists and is the latest instance" }
            return existingInstance
        } else if (existingInstance != null && !existingInstance.isLatestInstance) {
            logger.info { "${shinyProxy.logPrefix(existingInstance)} Trying to create new instance which already exists and is not the latest instance. Therefore this instance wiill become the latest again" }
            // reconcile will take care of making this the latest instance again
            return existingInstance
        }

        // create new instance and add it to the list of instances
        // initial the instance is not the latest. Only when the ReplicaSet is created and fully running
        // the latestInstance marker will change to the new instance.
        val newInstance = ShinyProxyInstance(shinyProxy.hashOfCurrentSpec, false)
        updateStatus(shinyProxy) {
            // Extra check, if this check is positive we have some bug
            if (it.status.instances.firstOrNull { instance -> instance.hashOfSpec == newInstance.hashOfSpec } != null) {
                logger.error(Throwable()) { "Tried to add new instance with hash ${newInstance.hashOfSpec}, while status already contains an instance with that hash, this should not happen! New: $newInstance, status: ${it.status} " }
            }
            it.status.instances.add(newInstance)
        }

        return newInstance
    }

    private fun updateStatus(shinyProxy: ShinyProxy, updater: (ShinyProxy) -> Unit) {
        val freshShinyProxy = refreshShinyProxy(shinyProxy)
        updater(freshShinyProxy)
        try {
            shinyProxyClient.inNamespace(shinyProxy.metadata.namespace).updateStatus(freshShinyProxy)
        } catch (e: KubernetesClientException) {
            // TODO handle this
            throw e
        }
    }

    private fun refreshShinyProxy(shinyProxy: ShinyProxy): ShinyProxy {
        return shinyProxyClient.inNamespace(shinyProxy.metadata.namespace).withName(shinyProxy.metadata.name).get()
    }

    private fun refreshShinyProxy(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): Pair<ShinyProxy, ShinyProxyInstance?> {
        val sp = shinyProxyClient.inNamespace(shinyProxy.metadata.namespace).withName(shinyProxy.metadata.name).get()
        return sp to sp.status.getInstanceByHash(shinyProxyInstance.hashOfSpec)
    }

    private fun updateLatestMarker(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance) {
        val latestInstance = shinyProxy.status.instances.firstOrNull { it.hashOfSpec == shinyProxy.hashOfCurrentSpec }
                ?: return
        if (latestInstance.isLatestInstance) {
            // already updated marker
            return
        }

        if (latestInstance != shinyProxyInstance) {
            // not called by latest instance -> not updating the latest marker
            // this update could be triggered by an older instance while the latest instance is not ready yet
            return
        }

        // Extra check, if this check is positive we have some bug, see #24986
        logger.warn(Throwable()) { "${shinyProxy.logPrefix(shinyProxyInstance)} Updating latest marker to ${latestInstance.hashOfSpec}, status: ${shinyProxy.status}" }

        updateStatus(shinyProxy) {
            it.status.instances.forEach { inst -> inst.isLatestInstance = false }
            it.status.instances.first { inst -> inst.hashOfSpec == latestInstance.hashOfSpec }.isLatestInstance = true
        }
    }

    suspend fun reconcileSingleShinyProxyInstance(_shinyProxy: ShinyProxy, _shinyProxyInstance: ShinyProxyInstance) {
        val (shinyProxy, shinyProxyInstance) = refreshShinyProxy(_shinyProxy, _shinyProxyInstance) // refresh shinyproxy to ensure status is always up to date

        if (shinyProxyInstance == null) {
            logger.info { "${shinyProxy.logPrefix(_shinyProxyInstance)} Cannot reconcile ShinProxyInstance because this instance does not exists." }
            return
        }

        logger.info { "${shinyProxy.logPrefix(shinyProxyInstance)} ReconcileSingleShinyProxy" }

        if (!shinyProxy.status.instances.contains(shinyProxyInstance)) {
            logger.info { "${shinyProxy.logPrefix(shinyProxyInstance)} Cannot reconcile ShinProxyInstance because it is being deleted." }
            return
        }

        val configMaps = resourceRetriever.getConfigMapByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance), shinyProxy.metadata.namespace)
        if (configMaps.isEmpty()) {
            logger.debug { "${shinyProxy.logPrefix(shinyProxyInstance)} 0 ConfigMaps found -> creating ConfigMap" }
            configMapFactory.create(shinyProxy, shinyProxyInstance)
            return
        }

        val replicaSets = resourceRetriever.getReplicaSetByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance), shinyProxy.metadata.namespace)
        if (replicaSets.isEmpty()) {
            logger.debug { "${shinyProxy.logPrefix(shinyProxyInstance)} 0 ReplicaSets found -> creating ReplicaSet" }
            replicaSetFactory.create(shinyProxy, shinyProxyInstance)
            return
        }

        // Extra check, if this check is positive we have some bug, see #24986
        if (replicaSets.size > 1) {
            logger.error(Throwable()) {
                "${shinyProxy.logPrefix(shinyProxyInstance)} Trying to reconcile but detected more than one ReplicaSet which matches the labels for a single instance. ${replicaSets.map { it.metadata.name }} ${replicaSets.map { Readiness.isReady(it) }}"
            }
        }

        if (!Readiness.isReady(replicaSets[0])) {
            // do no proceed until replicaset is ready
            logger.debug { "${shinyProxy.logPrefix(shinyProxyInstance)} ReplicaSet is not ready -> not proceeding with reconcile" }
            return
        }

        logger.debug { "${shinyProxy.logPrefix(shinyProxyInstance)} ReplicaSet is ready -> proceed with reconcile" }

        updateLatestMarker(shinyProxy, shinyProxyInstance)

        val services = resourceRetriever.getServiceByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance), shinyProxy.metadata.namespace)
        if (services.isEmpty()) {
            logger.debug { "${shinyProxy.logPrefix(shinyProxyInstance)} 0 Services found -> creating Service" }
            serviceFactory.create(shinyProxy, shinyProxyInstance)
            return
        }

        ingressController.reconcile(shinyProxy)
        podRetriever.addNamespaces(shinyProxy.namespacesOfCurrentInstance)
        reconcileListener?.onInstanceFullyReconciled(shinyProxy, shinyProxyInstance)
    }

    private fun checkForObsoleteInstances() {
        for (shinyProxy in shinyProxyLister.list()) {
            if (shinyProxy.status.instances.size > 1) {
                // this SP has more than one instance -> check if some of them are obsolete
                // take a copy of the list to check to prevent concurrent modification
                val instancesToCheck = shinyProxy.status.instances.toList()
                for (shinyProxyInstance in instancesToCheck) {
                    if (shinyProxyInstance.isLatestInstance || shinyProxyInstance.hashOfSpec == shinyProxy.hashOfCurrentSpec) {
                        // shinyProxyInstance is either the latest or the soon to be latest instance
                        continue
                    }

                    val pods = podRetriever.getPodsForShinyProxyInstance(shinyProxy, shinyProxyInstance)

                    if (pods.isEmpty()) {
                        logger.info { "${shinyProxy.logPrefix(shinyProxyInstance)} ShinyProxyInstance has no running apps and is not the latest version => removing this instance" }
                        deleteSingleShinyProxyInstance(shinyProxy, shinyProxyInstance)
                    } else {
                        logger.debug { "${shinyProxy.logPrefix(shinyProxyInstance)} ShinyProxyInstance has ${pods.size} running apps => not removing this instance" }
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

    fun deleteSingleShinyProxyInstance(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance) {
        logger.info { "${shinyProxy.logPrefix(shinyProxyInstance)} DeleteSingleShinyProxyInstance" }
        // Important: update status BEFORE deleting, otherwise we will start reconciling this instance, before it's completely deleted
        updateStatus(shinyProxy) {
            it.status.instances.remove(shinyProxyInstance)
        }
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
