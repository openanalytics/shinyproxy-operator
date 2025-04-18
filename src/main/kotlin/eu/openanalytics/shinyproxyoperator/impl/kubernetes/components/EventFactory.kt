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
package eu.openanalytics.shinyproxyoperator.impl.kubernetes.components

import eu.openanalytics.shinyproxyoperator.LabelFactory
import eu.openanalytics.shinyproxyoperator.logPrefix
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance
import io.fabric8.kubernetes.api.model.EventBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter


class EventFactory(private val kubeClient: KubernetesClient) {

    private val logger = KotlinLogging.logger {}

    private fun createInstanceFailed(shinyProxyInstance: ShinyProxyInstance, type: String, action: String, shinyProxyUid: String, message: String?) {
        val events = kubeClient.v1().events().inNamespace(shinyProxyInstance.namespace).withLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxyInstance)).list()
        val truncatedMessage = truncateMessage(message)
        if (events.items.any { it.action == action && it.type == type && it.message == truncatedMessage }) {
            return
        }
        logger.warn { "${logPrefix(shinyProxyInstance)} ShinyProxy instance failed to start: ${message?.replace("\n", "")}" }
        createEvent(shinyProxyInstance, type, action, shinyProxyUid, truncatedMessage)
    }

    private fun createEvent(shinyProxyInstance: ShinyProxyInstance, type: String, action: String, shinyProxyUid: String, message: String? = null) {
        val k8sMicroTime = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'.'SSSSSSXXX")

        //@formatter:off
        val eventBuilder = EventBuilder()
            .withNewMetadata()
                .withGenerateName("shinyproxy-operator-${action.lowercase()}-")
                .withNamespace(shinyProxyInstance.namespace)
                .addNewOwnerReference()
                    .withController(true)
                    .withKind("ShinyProxy")
                    .withApiVersion("openanalytics.eu/v1")
                    .withName(shinyProxyInstance.name)
                    .withUid(shinyProxyUid)
                .endOwnerReference()
                .withLabels<String, String>(LabelFactory.labelsForShinyProxyInstance( shinyProxyInstance))
            .endMetadata()
            .withNewInvolvedObject()
                .withKind("ShinyProxy")
                .withApiVersion("openanalytics.eu/v1")
                .withName(shinyProxyInstance.name)
                .withNamespace(shinyProxyInstance.namespace)
                .withUid(shinyProxyUid)
            .endInvolvedObject()
            .withNewEventTime(k8sMicroTime.format(ZonedDateTime.now()))
            .withReportingInstance("shinyproxy-operator")
            .withReportingComponent("shinyproxy-operator")
            .withAction(action)
            .withReason(action)
            .withType(type)
        //@formatter:on

        if (message != null) {
            eventBuilder.withMessage(message)
        }

        try {
            kubeClient.v1().events().resource(eventBuilder.build()).create()
        } catch (e: KubernetesClientException) {
            logger.warn(e) { "${logPrefix(shinyProxyInstance)} Error while creating event, type: $type, action: $action, message: $message" }
        }
    }

    private fun truncateMessage(message: String?): String? {
        if (message == null) {
            return null
        }
        if (message.length > 1024) {
            return message.substring(0, 1012) + " [truncated]"
        }
        return message
    }

    fun logEvent(shinyProxyInstance: ShinyProxyInstance, type: String, action: String, shinyProxyUid: String, message: String?) {
        if (action == "StartingNewInstanceFailed") {
            createInstanceFailed(shinyProxyInstance, type, action, shinyProxyUid, message)
        } else {
            createEvent(shinyProxyInstance, type, action, shinyProxyUid, message)
        }
    }

    fun logEvent(shinyProxyInstance: String, action: String, message: String?) {
        // no-op
    }

}
