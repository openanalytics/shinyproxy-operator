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
package eu.openanalytics.shinyproxyoperator.impl.kubernetes.components

import eu.openanalytics.shinyproxyoperator.LabelFactory
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.getParsedServicePatches
import eu.openanalytics.shinyproxyoperator.logPrefix
import eu.openanalytics.shinyproxyoperator.model.ShinyProxy
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.ServiceList
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.ServiceResource
import io.github.oshai.kotlinlogging.KotlinLogging

class ServiceFactory(private val serviceClient: MixedOperation<Service, ServiceList, ServiceResource<Service>>) {

    private val logger = KotlinLogging.logger {}

    private val servicePatcher = Patcher()

    fun create(shinyProxy: ShinyProxy, latestShinyProxyInstance: ShinyProxyInstance, shinyProxyUid: String) {
        val labels = LabelFactory.labelsForShinyProxy(shinyProxy.realmId).toMutableMap()
        labels[LabelFactory.LATEST_INSTANCE_LABEL] = latestShinyProxyInstance.hashOfSpec
        labels[LabelFactory.REVISION_LABEL] = latestShinyProxyInstance.revision.toString()

        //@formatter:off
        val serviceDefinition: Service = ServiceBuilder()
                .withNewMetadata()
                    .withName(ResourceNameFactory.createNameForService(shinyProxy))
                    .withNamespace(shinyProxy.namespace)
                    .withLabels<String, String>(labels)
                    .addNewOwnerReference()
                        .withController(true)
                        .withKind("ShinyProxy")
                        .withApiVersion("openanalytics.eu/v1")
                        .withName(shinyProxy.name)
                        .withUid(shinyProxyUid)
                    .endOwnerReference()
                .endMetadata()
                .withNewSpec()
                    .withType("ClusterIP")
                    .addNewPort()
                        .withPort(80)
                        .withTargetPort(IntOrString(8080))
                    .endPort()
                    .withSelector<String, String>(LabelFactory.labelsForShinyProxyInstance(latestShinyProxyInstance))
                .endSpec()
                .build()
        //@formatter:on

        val patchedService = servicePatcher.patch(serviceDefinition, shinyProxy.getParsedServicePatches())
        val createdService = serviceClient.inNamespace(shinyProxy.namespace).resource(patchedService).forceConflicts().serverSideApply()
        logger.debug { "${logPrefix(latestShinyProxyInstance)} [Component/Service] Created ${createdService.metadata.name}" }
    }

}
