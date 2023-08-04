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
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPath
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPathBuilder
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import mu.KotlinLogging

class IngressFactory(private val kubeClient: KubernetesClient) {

    private val logger = KotlinLogging.logger {}

    private val ingressPatcher = IngressPatcher()

    fun create(shinyProxy: ShinyProxy, latestShinyProxyInstance: ShinyProxyInstance) {
        val labels = LabelFactory.labelsForShinyProxy(shinyProxy).toMutableMap()
        labels[LabelFactory.LATEST_INSTANCE_LABEL] = latestShinyProxyInstance.hashOfSpec
        //@formatter:off
        val ingressDefinition = IngressBuilder()
                .withNewMetadata()
                    .withName(ResourceNameFactory.createNameForIngress(shinyProxy))
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
                    .withIngressClassName("nginx")
                    .addNewRule()
                        .withHost(shinyProxy.fqdn)
                        .withNewHttp()
                            .addToPaths(createPathV1(shinyProxy))
                        .endHttp()
                    .endRule()
                .endSpec()
                .build()
            //@formatter:on

        val patchedIngress = ingressPatcher.patch(ingressDefinition, shinyProxy.parsedIngressPatches)
        val createdIngress = kubeClient.network().v1().ingresses().inNamespace(shinyProxy.metadata.namespace).resource(patchedIngress).createOrReplace()
        logger.debug { "${shinyProxy.logPrefix()} [Component/Ingress] Created ${createdIngress.metadata.name}" }
    }

    private fun createPathV1(shinyProxy: ShinyProxy): HTTPIngressPath {
        //@formatter:off
        val builder = HTTPIngressPathBuilder()
                .withPathType("Prefix")
                .withPath(shinyProxy.subPath)
                .withNewBackend()
                    .withNewService()
                        .withName(ResourceNameFactory.createNameForService(shinyProxy))
                        .withNewPort()
                            .withNumber(80)
                        .endPort()
                    .endService()
                .endBackend()
        //@formatter:on

        return builder.build()
    }

}
