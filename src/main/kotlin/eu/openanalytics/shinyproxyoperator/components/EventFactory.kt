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
package eu.openanalytics.shinyproxyoperator.components

import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance
import io.fabric8.kubernetes.api.model.EventBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import mu.KotlinLogging
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter


class EventFactory(private val kubeClient: KubernetesClient) {

    private val logger = KotlinLogging.logger {}

    fun createNewInstanceEvent(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance) {
        createEvent(shinyProxy, shinyProxyInstance, "Normal", "StartingNewInstance", "Configuration changed")
    }

    fun createInstanceReadyEvent(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance) {
        createEvent(shinyProxy, shinyProxyInstance, "Normal", "InstanceReady", "ShinyProxy ready")
    }

    private fun createEvent(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance, type: String, action: String, reason: String, message: String? = null) {
        val k8sMicroTime = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'.'SSSSSSXXX")

        //@formatter:off
        val eventBuilder = EventBuilder()
            .withNewMetadata()
                .withGenerateName("test-event")
                .withNamespace(shinyProxy.metadata.namespace)
                .addNewOwnerReference()
                    .withController(true)
                    .withKind("ShinyProxy")
                    .withApiVersion("openanalytics.eu/v1")
                    .withName(shinyProxy.metadata.name)
                    .withUid(shinyProxy.metadata.uid)
                .endOwnerReference()
                .withLabels<String, String>(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance))
            .endMetadata()
            .withNewInvolvedObject()
                .withKind("ShinyProxy")
                .withApiVersion("openanalytics.eu/v1")
                .withName(shinyProxy.metadata.name)
                .withNamespace(shinyProxy.metadata.namespace)
                .withUid(shinyProxy.metadata.uid)
            .endInvolvedObject()
            .withNewEventTime(k8sMicroTime.format(ZonedDateTime.now()))
            .withReportingInstance("shinyproxy-operator") // TODO
            .withReportingComponent("shinyproxy-operator")
            .withAction(action) // which action failed, machine-readable
            .withType(type) // Warning or Normal
            .withReason(reason); // reason is why the action was taken, human-readable, 128 characters
        //@formatter:on

        if (message != null) {
            eventBuilder.withMessage(message)
        }

        kubeClient.v1().events().resource(eventBuilder.build()).create()
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

    fun createInstanceFailed(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance, message: String?, creationTimestamp: Instant?) {
        val events = kubeClient.v1().events().withLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance)).list()
        val truncatedMessage = truncateMessage(message)
        if (events.items.any {
                it.action == "StartingNewInstanceFailed"
                    && it.type == "Warning"
                    && it.message == truncatedMessage
                    && Instant.parse(it.eventTime.time) > creationTimestamp
            }) {
            return
        }
        logger.warn { "${shinyProxy.logPrefix(shinyProxyInstance)} Pods are failing: ${message?.replace("\n", "")}" }
        createEvent(shinyProxy, shinyProxyInstance, "Warning", "StartingNewInstanceFailed", "ShinyProxy failed to start", truncatedMessage)
    }

}
