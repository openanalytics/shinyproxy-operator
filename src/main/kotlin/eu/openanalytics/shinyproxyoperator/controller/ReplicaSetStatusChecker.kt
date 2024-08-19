/**
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

import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance
import java.time.Instant

class ReplicaSetStatusChecker(private val podRetriever: PodRetriever) {

    data class Status(val failed: Boolean, val failureMessage: String?, val creationTimestamp: Instant?)

    fun check(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): Status {
        val pods = podRetriever.getShinyProxyPods(shinyProxy, shinyProxyInstance)
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

}
