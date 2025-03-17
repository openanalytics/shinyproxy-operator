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
package eu.openanalytics.shinyproxyoperator.impl.kubernetes.controller

import eu.openanalytics.shinyproxyoperator.IEventController
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.KubernetesSource
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.*
import kotlin.concurrent.timer

class ReplicaSetStatusChecker(private val podRetriever: PodRetriever,
                              private val kubernetesSource: KubernetesSource,
                              private val eventController: IEventController) {

    data class Status(val failed: Boolean, val failureMessage: String?, val creationTimestamp: Instant?)

    private val logger = KotlinLogging.logger {}
    private var timer: Timer? = null

    fun init() {
        timer = timer(period = 30_000L, initialDelay = 3_000L) {
            runBlocking {
                try {
                    for (status in kubernetesSource.listStatus()) {
                        for (shinyProxyInstance in status.instances) {
                            val replicaSetStatus = check(shinyProxyInstance)
                            if (replicaSetStatus.failed) {
                                eventController.createInstanceFailed(shinyProxyInstance, replicaSetStatus.failureMessage)
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "${"Error while checking ReplicaSet status"}" }
                }
            }
        }
    }

    private fun check(shinyProxyInstance: ShinyProxyInstance): Status {
        val pods = podRetriever.getShinyProxyPods(shinyProxyInstance)
        if (pods.isEmpty()) {
            // no pods -> not ready yet, but not failed
            return Status(false, null, null)
        }
        for (pod in pods) {
            if (pod.status.containerStatuses.isEmpty()) {
                // no container status yet
                continue
            }
            val shinyproxyContainer = pod.status.containerStatuses.firstOrNull { it.name == "shinyproxy" }
            if (shinyproxyContainer == null) {
                // no shinyproxy container status
                continue
            }
            if (!shinyproxyContainer.ready && shinyproxyContainer.restartCount >= 1) {
                // container has failed
                val msg = shinyproxyContainer.lastState?.terminated?.message ?: "Unknown error"
                val creationTimestamp = Instant.parse(pod.metadata.creationTimestamp)
                return Status(true, msg, creationTimestamp)
            }
        }
        return Status(false, null, null)
    }

    fun stop() {
        timer?.cancel()
    }

}
