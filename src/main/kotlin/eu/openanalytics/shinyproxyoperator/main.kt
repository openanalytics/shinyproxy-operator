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

import eu.openanalytics.shinyproxyoperator.impl.docker.DockerOperator
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.KubernetesOperator
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mu.KotlinLogging
import kotlin.system.exitProcess


suspend fun main() {
    val logger = KotlinLogging.logger {}
    try {

        val orchestratorName = readConfigValue( "kubernetes", "SPO_ORCHESTRATOR") { it.lowercase() }
        val operator: IOperator = if (orchestratorName == "kubernetes") {
            KubernetesOperator()
        } else if (orchestratorName == "docker") {
            DockerOperator()
        } else {
            println()
            println()
            println("ERROR: Invalid env variable SPO_ORCHESTRATOR: 'kubernetes' and 'docker' are supported.")
            println()
            exitProcess(1)
        }

        logger.info { "Starting background processes of ShinyProxy Operator" }
        operator.init()

        CoroutineScope(Dispatchers.Default).launch(CoroutineName("run")) {
            logger.info { "Starting ShinyProxy Operator" }
            operator.run()
        }
    } catch (exception: Exception) {
        logger.warn { "Exception : ${exception.message}" }
        exception.printStackTrace()
        exitProcess(1)
    }
}
