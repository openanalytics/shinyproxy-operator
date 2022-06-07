/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021 Open Analytics
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
package eu.openanalytics.shinyproxyoperator.ingress.skipper

import com.fasterxml.jackson.databind.ObjectMapper
import eu.openanalytics.shinyproxyoperator.components.LabelFactory
import eu.openanalytics.shinyproxyoperator.components.ResourceNameFactory
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import io.fabric8.kubernetes.api.model.KubernetesResourceList
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.Resource
import mu.KotlinLogging


class MetadataRouteGroupFactory(private val routeGroupClient: MixedOperation<RouteGroup, KubernetesResourceList<RouteGroup>, Resource<RouteGroup>>) {

    private val logger = KotlinLogging.logger {}
    private val objectMapper = ObjectMapper()

    fun createOrReplaceRouteGroup(shinyProxy: ShinyProxy) {
        val metadata = objectMapper.writeValueAsString(mapOf("instances" to shinyProxy.status.instances)).replace("\"", "\\\"")

        val path = if (shinyProxy.subPath != "") {
            shinyProxy.subPath + "/operator/metadata"
        } else {
            "/operator/metadata"
        }

        val routeGroupSpec = RouteGroupSpec(
            hosts = listOf(shinyProxy.fqdn),
            backends = listOf(Backend("shunt", "shunt")),
            defaultBackends = listOf(BackendName("shunt")),
            routes = listOf(
                Route(
                    pathSubtree = path,
                    filters = listOf(
                        """setResponseHeader("Content-Type","application/json")""",
                        """inlineContent("$metadata")""",
                        """status(200)"""
                    ),
                    backends = listOf(BackendName("shunt"))
                )
            )
        )

        //@formatter:off
        val routeGroup = RouteGroup()
        routeGroup.spec = routeGroupSpec
        routeGroup.metadata = ObjectMetaBuilder()
            .withNamespace(shinyProxy.metadata.namespace)
            .withName(ResourceNameFactory.createNameForMetadataIngress(shinyProxy))
            .withLabels<String, String>(LabelFactory.labelsForShinyProxy(shinyProxy))
            .addNewOwnerReference()
                .withController(true)
                .withKind("ShinyProxy")
                .withApiVersion("openanalytics.eu/v1")
                .withName(shinyProxy.metadata.name)
                .withUid(shinyProxy.metadata.uid)
            .endOwnerReference()
            .build()
        //@formatter:on

        val createdRouteGroup = routeGroupClient.inNamespace(shinyProxy.metadata.namespace).createOrReplace(routeGroup)
        logger.info { "[Component/RouteGroup] Created ${createdRouteGroup.metadata.name}" }
    }
}
