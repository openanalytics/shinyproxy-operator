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
package eu.openanalytics.shinyproxyoperator

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.*

object Versions {

    fun print() {
        val properties = Properties()
        properties.load(this::class.java.getResourceAsStream("/versions.properties"))
        val logger = KotlinLogging.logger {}
        logger.info { "ShinyProxy Operator version: ${getVersion(properties, "shinyproxy-operator.version")}" }
        logger.info { "Docker Client version: ${getVersion(properties, "docker.client.version")}" }
        logger.info { "Kubernetes Client version: ${getVersion(properties, "fabric8.client.version")}" }
    }

    private fun getVersion(properties: Properties, key: String): String {
        val version = properties.getProperty(key) ?: return "N/A"
        if (version.startsWith("\${")) {
            return "N/A"
        }
        return version
    }

}
