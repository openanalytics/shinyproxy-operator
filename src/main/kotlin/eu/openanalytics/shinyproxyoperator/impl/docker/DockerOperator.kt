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

import eu.openanalytics.shinyproxyoperator.Config
import eu.openanalytics.shinyproxyoperator.IEventController
import eu.openanalytics.shinyproxyoperator.IOperator
import eu.openanalytics.shinyproxyoperator.IRecyclableChecker
import eu.openanalytics.shinyproxyoperator.controller.EventController
import eu.openanalytics.shinyproxyoperator.controller.RecyclableChecker
import eu.openanalytics.shinyproxyoperator.controller.ShinyProxyController
import eu.openanalytics.shinyproxyoperator.event.ShinyProxyEvent
import eu.openanalytics.shinyproxyoperator.impl.source.FileSource
import kotlinx.coroutines.channels.Channel
import java.nio.file.Path

class DockerOperator(config: Config,
                     eventController: IEventController? = null,
                     recyclableChecker: IRecyclableChecker? = null) : IOperator {

    private val dataDir = config.readConfigValue(Path.of("/opt/shinyproxy-docker-operator/data/"), "SPO_DOCKER_DATA_DIR") { Path.of(it) }
    private val inputDir = config.readConfigValue(Path.of("/opt/shinyproxy-docker-operator/input"), "SPO_INPUT_DIR") { Path.of(it) }

    private val channel = Channel<ShinyProxyEvent>(10000)
    val orchestrator = DockerOrchestrator(channel, config, this.dataDir, this.inputDir)
    private val eventController = eventController ?: EventController(orchestrator)
    private val fileSource = FileSource(channel, config, orchestrator, this.eventController, this.inputDir)
    private val recyclableChecker = recyclableChecker ?: RecyclableChecker(orchestrator)
    private val shinyProxyController = ShinyProxyController(channel, orchestrator, fileSource, this.eventController, this.recyclableChecker)

    override suspend fun init() {
        fileSource.init()
        orchestrator.init(fileSource)
        fileSource.run()
    }

    override suspend fun run() {
        shinyProxyController.run()
    }

    override fun stop() {
        fileSource.stop()
        orchestrator.stop()
    }

}
