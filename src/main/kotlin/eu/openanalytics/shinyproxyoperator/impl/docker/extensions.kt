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
package eu.openanalytics.shinyproxyoperator.impl.docker

import org.mandas.docker.client.messages.Container
import org.mandas.docker.client.messages.ContainerInfo

fun Container.getLabelOrNull(label: String): String? {
    val labels = labels() ?: return null
    return labels[label]
}

fun Container.getSharedNetworkIpAddress(): String? {
    val ip = networkSettings().networks()[DockerOrchestrator.SHARED_NETWORK_NAME]?.ipAddress()
    if (ip.isNullOrBlank()) {
        return null
    }
    return ip
}

fun Container.name(): String? {
    return names().firstOrNull { it.startsWith("/sp-") }?.replace("/", "")
}

fun Container.shortId(): String {
    return id().take(13)
}

fun ContainerInfo.getSharedNetworkIpAddress(): String? {
    return networkSettings().networks()[DockerOrchestrator.SHARED_NETWORK_NAME]?.ipAddress()
}
