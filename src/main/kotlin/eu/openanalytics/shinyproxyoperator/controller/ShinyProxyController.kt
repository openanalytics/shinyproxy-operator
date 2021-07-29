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
        suspend fun tryReceiveAndHandleEvent(event: ShinyProxyEvent) {
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
        }

        val event = channel.receive()
        for (i in 1..5) {
            try {
                tryReceiveAndHandleEvent(event)
                return
            } catch (e: Exception) {
                logger.warn(e) { "Caught an exception while processing event. [Attempt $i/5]" }
            }
        }
        logger.warn { "Caught an exception while processing event $event. [Attempt 5/5] Not re-processing this event." }
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
            val checkExistingInstance = it.status.instances.firstOrNull { instance -> instance.hashOfSpec == newInstance.hashOfSpec }
            if (checkExistingInstance != null) {
                // status has already been updated (e.g. after an HTTP 409 Conflict response)
                // remove the existing instance and add the new one, to ensure that all values are correct.
                it.status.instances.remove(checkExistingInstance)
            }
            it.status.instances.add(newInstance)
        }

        return newInstance
    }

    private fun updateStatus(shinyProxy: ShinyProxy, updater: (ShinyProxy) -> Unit) {
        /**
         * Tries to update the status, once, in a single step.
         * @throws KubernetesClientException
         */
        fun tryUpdateStatus() {
            val freshShinyProxy = refreshShinyProxy(shinyProxy)
            updater(freshShinyProxy)
            shinyProxyClient.inNamespace(shinyProxy.metadata.namespace).updateStatus(freshShinyProxy)
        }

        for (i in 1..5) {
            try {
                logger.debug { "${shinyProxy.logPrefix()} Trying to update status (attempt ${i}/5)" }
                tryUpdateStatus()
                logger.debug { "${shinyProxy.logPrefix()} Status successfully updated" }
                return
            } catch (e: KubernetesClientException) {
                logger.warn(e) { "${shinyProxy.logPrefix()} Update of status not succeeded (attempt ${i}/5)" }
            }
        }
        throw RuntimeException("${shinyProxy.logPrefix()} Unable to update Status of ShinyProxy object after 5 attempts (event will be re-processed)")
    }


    private fun refreshShinyProxy(shinyProxy: ShinyProxy): ShinyProxy {
        return shinyProxyClient.inNamespace(shinyProxy.metadata.namespace).withName(shinyProxy.metadata.name).get()
    }

    private fun refreshShinyProxy(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): Pair<ShinyProxy?, ShinyProxyInstance?> {
        val sp = shinyProxyClient.inNamespace(shinyProxy.metadata.namespace).withName(shinyProxy.metadata.name).get()
        return sp to sp?.status?.getInstanceByHash(shinyProxyInstance.hashOfSpec)
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

        if (shinyProxy == null || shinyProxyInstance == null) {
            logger.info { "${_shinyProxy.logPrefix(_shinyProxyInstance)} Cannot reconcile ShinProxyInstance because this instance does not exists." }
            return
        }

        logger.info { "${shinyProxy.logPrefix(shinyProxyInstance)} [Step 0/$amountOfSteps: Ok] ReconcileSingleShinyProxy" }

        if (!shinyProxy.status.instances.contains(shinyProxyInstance)) {
            logger.info { "${shinyProxy.logPrefix(shinyProxyInstance)} Cannot reconcile ShinProxyInstance because it is being deleted." }
            return
        }

        val configMaps = resourceRetriever.getConfigMapByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance), shinyProxy.metadata.namespace)
        if (configMaps.isEmpty()) {
            logger.debug { "${shinyProxy.logPrefix(shinyProxyInstance)} [Step 1/$amountOfSteps: Reconciling] [Component/ConfigMap] 0 ConfigMaps found -> creating ConfigMap" }
            configMapFactory.create(shinyProxy, shinyProxyInstance)
            return
        }

        logger.debug { "${shinyProxy.logPrefix(shinyProxyInstance)} [Step 1/$amountOfSteps: Ok] [Component/ConfigMap]" }

        val replicaSets = resourceRetriever.getReplicaSetByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance), shinyProxy.metadata.namespace)
        if (replicaSets.isEmpty()) {
            logger.debug { "${shinyProxy.logPrefix(shinyProxyInstance)} [Step 2/$amountOfSteps: Reconciling] [Component/ReplicaSet] 0 ReplicaSets found -> creating ReplicaSet" }
            replicaSetFactory.create(shinyProxy, shinyProxyInstance)
            return
        }

        logger.debug { "${shinyProxy.logPrefix(shinyProxyInstance)} [Step 2/$amountOfSteps: Ok] [Component/ReplicaSet]" }

        // Extra check, if this check is positive we have some bug, see #24986
        if (replicaSets.size > 1) {
            logger.error(Throwable()) {
                "${shinyProxy.logPrefix(shinyProxyInstance)} Trying to reconcile but detected more than one ReplicaSet which matches the labels for a single instance. ${replicaSets.map { it.metadata.name }} ${replicaSets.map { Readiness.isReady(it) }}"
            }
        }

        if (!Readiness.isReady(replicaSets[0])) {
            // do no proceed until replicaset is ready
            logger.debug { "${shinyProxy.logPrefix(shinyProxyInstance)} [Step 3/$amountOfSteps: Waiting] [Component/ReplicaSet] ReplicaSet not ready" }
            return
        }

        logger.debug { "${shinyProxy.logPrefix(shinyProxyInstance)} [Step 3/$amountOfSteps: Ok] [Component/ReplicaSet] ReplicaSet ready" }

        val services = resourceRetriever.getServiceByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance), shinyProxy.metadata.namespace)
        if (services.isEmpty()) {
            logger.debug { "${shinyProxy.logPrefix(shinyProxyInstance)} [Step 4/$amountOfSteps: Reconciling] [Component/Service] 0 Services found -> creating Service" }
            serviceFactory.create(shinyProxy, shinyProxyInstance)
            return
        }

        logger.debug { "${shinyProxy.logPrefix(shinyProxyInstance)} [Step 4/$amountOfSteps: Ok] [Component/Service]" }

        updateLatestMarker(shinyProxy, shinyProxyInstance)

        logger.debug { "${shinyProxy.logPrefix(shinyProxyInstance)} [Step 5/$amountOfSteps: Ok] [Status/LatestMarker]" }

        // refresh the ShinyProxy variables after updating the latest marker
        val (updatedShinyProxy, updatedShinyProxyInstance) = refreshShinyProxy(_shinyProxy, _shinyProxyInstance)
        if (updatedShinyProxy == null) return
        ingressController.reconcile(updatedShinyProxy)

        logger.debug { "${shinyProxy.logPrefix(shinyProxyInstance)} [Step 6/$amountOfSteps: Ok] [Component/Ingress]" }

        podRetriever.addNamespaces(shinyProxy.namespacesOfCurrentInstance)
        if (updatedShinyProxyInstance != null) {
            reconcileListener?.onInstanceFullyReconciled(updatedShinyProxy, updatedShinyProxyInstance)
        }
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
        logger.info { "${shinyProxy.logPrefix(shinyProxyInstance)} DeleteSingleShinyProxyInstance [Step 1/3]: Update status" }
        // Important: update status BEFORE deleting, otherwise we will start reconciling this instance, before it's completely deleted
        updateStatus(shinyProxy) {
            it.status.instances.remove(shinyProxyInstance)
        }

        // Important: remove ingress before removing the ReplicaSet. This ensures that the rotues are correclty updated in the Ingress
        // and users aren't routed to non-existing pods
        logger.info { "${shinyProxy.logPrefix(shinyProxyInstance)} DeleteSingleShinyProxyInstance [Step 2/3]: Update Ingress" }
        ingressController.onRemoveInstance(shinyProxy, shinyProxyInstance)

        GlobalScope.launch { // run async
            // delete resources after delay of 30 seconds to ensure all routes are updated before deleting replicaset
            delay(30_000)
            logger.info { "${shinyProxy.logPrefix(shinyProxyInstance)} DeleteSingleShinyProxyInstance [Step 3/3]: Delete resources" }

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

    companion object {
        const val amountOfSteps: Int = 6
    }


}
