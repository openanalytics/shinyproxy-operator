/*
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021-2025 Open Analytics
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
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance

class EventController(private val orchestrator: IOrchestrator) : IEventController {

    override fun createNewInstanceEvent(shinyProxyInstance: ShinyProxyInstance) {
        orchestrator.logEvent(shinyProxyInstance, "Normal", "StartingNewInstance",
            "Configuration changed, starting new instance: ${shinyProxyInstance.hashOfSpec}, revision: ${shinyProxyInstance.revision}")
    }

    override fun createInstanceReadyEvent(shinyProxyInstance: ShinyProxyInstance) {
        orchestrator.logEvent(shinyProxyInstance, "Normal", "InstanceReady",
            "ShinyProxy instance ready: ${shinyProxyInstance.hashOfSpec}, revision: ${shinyProxyInstance.revision}")
    }

    override fun createInstanceFailed(shinyProxyInstance: ShinyProxyInstance, message: String?) {
        orchestrator.logEvent(shinyProxyInstance, "Warning", "StartingNewInstanceFailed",
            "ShinyProxy instance failed to start: ${shinyProxyInstance.hashOfSpec}, revision: ${shinyProxyInstance.revision}, output: $message")
    }

    override fun createDeletingInstanceEvent(shinyProxyInstance: ShinyProxyInstance) {
        orchestrator.logEvent(shinyProxyInstance, "Normal", "DeletingInstance",
            "Deleting ShinyProxy instance: ${shinyProxyInstance.hashOfSpec}, revision: ${shinyProxyInstance.revision}")
    }

    override fun createInstanceDeletedEvent(shinyProxyInstance: ShinyProxyInstance) {
        orchestrator.logEvent(shinyProxyInstance, "Normal", "InstanceDeleted",
            "Deleted ShinyProxy instance: ${shinyProxyInstance.hashOfSpec}, revision: ${shinyProxyInstance.revision}")
    }

    override fun createInstanceReconciledEvent(shinyProxyInstance: ShinyProxyInstance) {
        // no-op
    }

    override fun inputError(message: String) {
        orchestrator.logEvent("Warning", "InputError", "Failed to read input: $message")
    }

}
