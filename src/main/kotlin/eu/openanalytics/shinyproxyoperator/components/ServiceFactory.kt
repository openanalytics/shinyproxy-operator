/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021-2023 Open Analytics
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
import io.fabric8.kubernetes.api.model.ServiceList
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.ServiceResource
import mu.KotlinLogging

class ServiceFactory(private val serviceClient: MixedOperation<Service, ServiceList, ServiceResource<Service>>) {

    private val logger = KotlinLogging.logger {}

    fun create(shinyProxy: ShinyProxy, latestShinyProxyInstance: ShinyProxyInstance) {
        val labels = LabelFactory.labelsForShinyProxy(shinyProxy).toMutableMap()
        labels[LabelFactory.LATEST_INSTANCE_LABEL] = latestShinyProxyInstance.hashOfSpec

        //@formatter:off
        val serviceDefinition: Service = ServiceBuilder()
                .withNewMetadata()
                    .withName(ResourceNameFactory.createNameForService(shinyProxy))
                    .withNamespace(shinyProxy.metadata.namespace)
                    .withLabels<String, String>(labels)
                    .addNewOwnerReference()
                        .withController(true)
                        .withKind("ShinyProxy")
                        .withApiVersion("openanalytics.eu/v1")
                        .withName(shinyProxy.metadata.name)
                        .withUid(shinyProxy.metadata.uid)
                    .endOwnerReference()
                .endMetadata()
                .withNewSpec()
                    .withType("ClusterIP")
                    .addNewPort()
                        .withPort(80)
                        .withTargetPort(IntOrString(8080))
                    .endPort()
                    .withSelector<String, String>(LabelFactory.labelsForShinyProxyInstance(shinyProxy, latestShinyProxyInstance))
                .endSpec()
                .build()
        //@formatter:on

        val createdService = serviceClient.inNamespace(shinyProxy.metadata.namespace).resource(serviceDefinition).createOrReplace()
        logger.debug { "${shinyProxy.logPrefix(latestShinyProxyInstance)} [Component/Service] Created ${createdService.metadata.name}" }
    }

}
