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
package eu.openanalytics.shinyproxyoperator.controller

import eu.openanalytics.shinyproxyoperator.IEventController
import eu.openanalytics.shinyproxyoperator.IOrchestrator
import eu.openanalytics.shinyproxyoperator.IRecyclableChecker
import eu.openanalytics.shinyproxyoperator.IShinyProxySource
import eu.openanalytics.shinyproxyoperator.event.ShinyProxyEvent
import eu.openanalytics.shinyproxyoperator.event.ShinyProxyEventType
import eu.openanalytics.shinyproxyoperator.logPrefix
import eu.openanalytics.shinyproxyoperator.model.ShinyProxy
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance
import io.fabric8.kubernetes.client.KubernetesClientException
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.timer

class ShinyProxyController(
    private val channel: Channel<ShinyProxyEvent>,
    private val orchestrator: IOrchestrator,
    private val shinyProxySource: IShinyProxySource,
    private val eventController: IEventController,
    private val recyclableChecker: IRecyclableChecker
) {

    private val logger = KotlinLogging.logger {}

    private val scope = CoroutineScope(Dispatchers.Default)

    suspend fun run() {
        val timer = timer(period = 60_000L, initialDelay = 60_000L) {
            runBlocking {
                channel.send(ShinyProxyEvent(ShinyProxyEventType.CHECK_OBSOLETE_INSTANCES, null, null, null, null))
            }
        }
        logger.info { "Starting ShinyProxyController" }
        while (true) {
            try {
                receiveAndHandleEvent()
            } catch (cancellationException: CancellationException) {
                logger.warn { "Controller cancelled -> stopping" }
                timer.cancel()
                throw cancellationException
            }
        }
    }

    suspend fun receiveAndHandleEvent() {
        suspend fun tryReceiveAndHandleEvent(event: ShinyProxyEvent) {
            when (event.eventType) {
                ShinyProxyEventType.ADD -> {
                    if (event.name == null || event.namespace == null || event.realmId == null) {
                        logger.warn { "Event of type ADD should have realmId attached to it." }
                        return
                    }
                    val shinyProxy = shinyProxySource.get(event.namespace, event.name)
                    if (shinyProxy == null) {
                        logger.warn { "Did not find source for realm: ${event.realmId}." }
                        return
                    }
                    val newInstance = createNewInstance(shinyProxy, false)
                    reconcileSingleShinyProxyInstance(shinyProxy, newInstance)
                }

                ShinyProxyEventType.UPDATE_SPEC -> {
                    if (event.name == null || event.namespace == null || event.realmId == null) {
                        logger.warn { "Event of type UPDATE_SPEC should have realmId attached to it." }
                        return
                    }
                    val shinyProxy = shinyProxySource.get(event.namespace, event.name)
                    if (shinyProxy == null) {
                        logger.warn { "Did not find source for realm: ${event.realmId}." }
                        return
                    }
                    val newInstance = createNewInstance(shinyProxy, true)
                    reconcileSingleShinyProxyInstance(shinyProxy, newInstance)
                }

                ShinyProxyEventType.DELETE -> {
                    if (event.realmId == null) {
                        logger.warn { "Event of type DELETE should have realmId attached to it." }
                        return
                    }
                    logger.info { "${logPrefix(event.realmId)} DeleteRealm" }
                    orchestrator.deleteRealm(event.realmId)
                }

                ShinyProxyEventType.RECONCILE -> {
                    if (event.name == null || event.namespace == null || event.realmId == null) {
                        logger.warn { "Event of type RECONCILE should have realmId attached to it." }
                        return
                    }
                    val shinyProxy = shinyProxySource.get(event.namespace, event.name)
                    if (shinyProxy == null) {
                        logger.warn { "Did not find source for realm: ${event.realmId}." }
                        return
                    }
                    val status = orchestrator.getShinyProxyStatus(shinyProxy)
                    val hash = event.shinyProxyInstance ?: status?.latestInstance()?.hashOfSpec ?: shinyProxy.hashOfCurrentSpec
                    val shinyProxyInstance = status?.getInstanceByHash(hash)
                    if (shinyProxyInstance == null) {
                        logger.warn { "Received event with invalid shinyProxyInstance." }
                        return
                    }

                    reconcileSingleShinyProxyInstance(shinyProxy, shinyProxyInstance)
                }

                ShinyProxyEventType.FAILURE -> {
                    if (event.name == null || event.namespace == null || event.realmId == null) {
                        logger.warn { "Event of type FAILURE should have realmId attached to it." }
                        return
                    }
                    if (event.shinyProxyInstance == null) {
                        logger.warn { "Event of type FAILURE should have shinyProxyInstance attached to it." }
                        return
                    }
                    val shinyProxy = shinyProxySource.get(event.namespace, event.name)
                    if (shinyProxy == null) {
                        logger.warn { "Did not find source for realm: ${event.realmId}." }
                        return
                    }
                    val shinyProxyInstance = orchestrator.getShinyProxyStatus(shinyProxy)?.getInstanceByHash(event.shinyProxyInstance)
                    if (shinyProxyInstance == null) {
                        logger.warn { "Received event with invalid shinyProxyInstance." }
                        return
                    }

                    instanceFailure(shinyProxy, shinyProxyInstance, event.message)
                }

                ShinyProxyEventType.CHECK_OBSOLETE_INSTANCES -> checkForObsoleteInstances()
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

    private fun createNewInstance(shinyProxy: ShinyProxy, isUpdate: Boolean): ShinyProxyInstance {
        val status = orchestrator.getShinyProxyStatus(shinyProxy) // refresh shinyproxy to ensure status is always up to date
        val existingInstance = status?.getInstanceByHash(shinyProxy.hashOfCurrentSpec)

        if (existingInstance != null && existingInstance.isLatestInstance && !isUpdate) {
            return existingInstance
        }

        val revision = if (existingInstance != null) {
            logger.info { "${logPrefix(existingInstance)} Trying to create new instance which already exists and is not the latest instance. Therefore this instance will become the latest again" }
            // reconcile will take care of making this the latest instance again
            existingInstance.revision + 1
        } else {
            0
        }

        // create new instance and add it to the list of instances
        // initial the instance is not the latest. Only when the ReplicaSet is created and fully running
        // the latestInstance marker will change to the new instance.
        val newInstance = ShinyProxyInstance(shinyProxy.name, shinyProxy.namespace, shinyProxy.realmId, shinyProxy.hashOfCurrentSpec, false, revision)
        orchestrator.addNewInstanceToStatus(shinyProxy, newInstance)
        eventController.createNewInstanceEvent(newInstance)
        return newInstance
    }

    private fun reconcileLatestMarker(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): Boolean {
        val status = orchestrator.getShinyProxyStatus(shinyProxy)
        val latestInstance = status?.getInstanceByHash(shinyProxy.hashOfCurrentSpec) ?: return false
        if (latestInstance.isLatestInstance) {
            // already updated marker
            return false
        }

        if (latestInstance != shinyProxyInstance) {
            // not called by latest instance -> not updating the latest marker
            // this update could be triggered by an older instance while the latest instance is not ready yet
            return false
        }

        orchestrator.makeLatest(shinyProxy, latestInstance)
        return true
    }

    suspend fun reconcileSingleShinyProxyInstance(
        shinyProxy: ShinyProxy,
        shinyProxyInstance: ShinyProxyInstance
    ) {
        try {
            if (orchestrator.getShinyProxyStatus(shinyProxy)?.getInstance(shinyProxyInstance) == null) {
                return
            }
            logger.info { "${logPrefix(shinyProxyInstance)} [Step 0/4: Ok] ReconcileSingleShinyProxy" }

            val ready = orchestrator.reconcileInstance(shinyProxy, shinyProxyInstance)
            if (!ready) {
                logger.info { "${logPrefix(shinyProxyInstance)} [Step 1/4: Reconciling] [Container]" }
                return
            }
            logger.info { "${logPrefix(shinyProxyInstance)} [Step 1/4: Ok] [Container]" }

            val hasUpdatedLatestMaker = reconcileLatestMarker(shinyProxy, shinyProxyInstance)
            val updatedShinyProxyInstance = orchestrator.getShinyProxyStatus(shinyProxy)?.getInstance(shinyProxyInstance) ?: return
            if (hasUpdatedLatestMaker) {
                logger.info { "${logPrefix(shinyProxyInstance)} [Step 2/4: Ok] [LatestMarker] Instance became latest" }
            } else {
                logger.info { "${logPrefix(shinyProxyInstance)} [Step 2/4: Ok] [LatestMarker]" }
            }

            logger.info { "${logPrefix(shinyProxyInstance)} [Step 3/4: Reconciling] [Ingress]" }
            orchestrator.reconcileIngress(shinyProxy, updatedShinyProxyInstance)
            logger.info { "${logPrefix(shinyProxyInstance)} [Step 4/4: Ok] [Ingress]" }

            if (hasUpdatedLatestMaker) {
                eventController.createInstanceReadyEvent(updatedShinyProxyInstance)
            }
            eventController.createInstanceReconciledEvent(updatedShinyProxyInstance)
        } catch (e: KubernetesClientException) {
            if (e.status != null && e.status.message != null) {
                eventController.createInstanceFailed(shinyProxyInstance, "KubernetesClientException: " + e.status.message)
            } else {
                eventController.createInstanceFailed(shinyProxyInstance, e.message)
            }
            throw e
        } catch (e: Exception) {
            eventController.createInstanceFailed(shinyProxyInstance, e.message)
            throw e
        }
    }

    private fun instanceFailure(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance, message: String?) {
        logger.info { "${logPrefix(shinyProxyInstance)} Instance failed to start up" }
        eventController.createInstanceFailed(shinyProxyInstance, message)
    }

    private suspend fun checkForObsoleteInstances() {
        for (status in orchestrator.getShinyProxyStatuses()) {
            if (status.instances.size > 1) {
                // this SP has more than one instance -> check if some of them are obsolete
                // take a copy of the list to check to prevent concurrent modification
                val instancesToCheck = status.instances.toList()
                for (shinyProxyInstance in instancesToCheck) {
                    val latestRevision = status.getInstanceByHash(shinyProxyInstance.hashOfSpec)?.revision ?: 0
                    if (shinyProxyInstance.isLatestInstance || (shinyProxyInstance.hashOfSpec == status.hashOfCurrentSpec && shinyProxyInstance.revision >= latestRevision)) {
                        // shinyProxyInstance is either the latest or the soon to be latest instance
                        continue
                    }
                    if (recyclableChecker.isInstanceRecyclable(shinyProxyInstance)) {
                        logger.info { "${logPrefix(shinyProxyInstance)} ShinyProxyInstance is recyclable (i.e. it has no open websocket connections) and is not the latest version => removing this instance" }
                        deleteInstance(shinyProxyInstance)
                    } else {
                        logger.info { "${logPrefix(shinyProxyInstance)} ShinyProxyInstance is not recyclable (e.g. because it has open websocket connections) => not removing this instance" }
                    }
                }
            }
        }
    }

    suspend fun deleteInstance(shinyProxyInstance: ShinyProxyInstance) {
        // Important: update status BEFORE deleting, otherwise we will start reconciling this instance, before it's completely deleted
        eventController.createDeletingInstanceEvent(shinyProxyInstance)
        orchestrator.removeInstanceFromStatus(shinyProxyInstance)
        logger.info { "${logPrefix(shinyProxyInstance)} DeleteInstance [Step 1/3] [Status] Updated" }

        scope.launch { // run async
            // delete resources after delay of 30 seconds to ensure all routes are updated before deleting replicaset
            // note: proxy only points to latest instance
            delay(30_000)
            logger.info { "${logPrefix(shinyProxyInstance)} DeleteInstance [Step 2/3] [Resources] Deleting" }

            orchestrator.deleteInstance(shinyProxyInstance)
            logger.info { "${logPrefix(shinyProxyInstance)} DeleteInstance [Step 3/3] [Resources] Deleted" }
            eventController.createInstanceDeletedEvent(shinyProxyInstance)
        }
    }


}
