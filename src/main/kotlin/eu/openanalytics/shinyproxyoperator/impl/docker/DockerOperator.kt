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

import eu.openanalytics.shinyproxyoperator.IOperator
import eu.openanalytics.shinyproxyoperator.controller.EventController
import eu.openanalytics.shinyproxyoperator.controller.RecyclableChecker
import eu.openanalytics.shinyproxyoperator.controller.ShinyProxyController
import eu.openanalytics.shinyproxyoperator.event.ShinyProxyEvent
import eu.openanalytics.shinyproxyoperator.impl.source.FileSource
import kotlinx.coroutines.channels.Channel

class DockerOperator :IOperator {

    private val channel = Channel<ShinyProxyEvent>(10000)
    private val orchestrator = DockerOrchestrator(channel)
    private val eventController = EventController(orchestrator)
    private val fileSource = FileSource(channel, orchestrator, eventController)
    private val recyclableChecker = RecyclableChecker(orchestrator)
    private val shinyProxyController = ShinyProxyController(channel, orchestrator, fileSource, eventController, recyclableChecker)

    override suspend fun init() {
        fileSource.init()
        orchestrator.init(fileSource)
        fileSource.run()
    }

    override suspend fun run() {
        shinyProxyController.run()
    }

}
