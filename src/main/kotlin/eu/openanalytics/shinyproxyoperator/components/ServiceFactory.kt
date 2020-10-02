/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2020 Open Analytics
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
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import mu.KotlinLogging

class ServiceFactory(private val kubeClient: KubernetesClient) {

    private val logger = KotlinLogging.logger {}

    fun create(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance) {
        //@formatter:off
        val serviceDefinition: Service = ServiceBuilder()
                .withNewMetadata()
                    .withName(ResourceNameFactory.createNameForService(shinyProxy, shinyProxyInstance))
                    .withNamespace(shinyProxy.metadata.namespace)
                    .withLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance))
                    .addNewOwnerReference()
                        .withController(true)
                        .withKind("ShinyProxy")
                        .withApiVersion("openanalytics.eu/v1alpha1")
                        .withName(shinyProxy.metadata.name)
                        .withNewUid(shinyProxy.metadata.uid)
                    .endOwnerReference()
                .endMetadata()
                .withNewSpec()
                    .withType("NodePort")
                    .addNewPort()
                        .withPort(80)
                        .withTargetPort(IntOrString(8080))
                    .endPort()
                    .withSelector(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance))
                .endSpec()
                .build()
        //@formatter:on

        try {
            val createdService = kubeClient.services().inNamespace(shinyProxy.metadata.namespace).create(serviceDefinition)
            logger.debug { "Created Service with name ${createdService.metadata.name}" }
        } catch (e: KubernetesClientException) {
            if (e.code == 409) {
                // Kubernetes reported a conflict -> the resource is probably already begin created -> ignore
                // In the case that something else happened, kubernetes will create an event
                logger.debug { "Conflict during creating of resource, ignoring." }
            } else {
                throw e
            }
        }
    }

}