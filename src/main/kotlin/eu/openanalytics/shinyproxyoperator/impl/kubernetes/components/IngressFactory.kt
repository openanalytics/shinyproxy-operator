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
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.getParsedIngressPatches
import eu.openanalytics.shinyproxyoperator.logPrefix
import eu.openanalytics.shinyproxyoperator.model.ShinyProxy
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPath
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPathBuilder
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder
import io.fabric8.kubernetes.api.model.networking.v1.IngressRuleBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.oshai.kotlinlogging.KotlinLogging

class IngressFactory(private val kubeClient: KubernetesClient) {

    private val logger = KotlinLogging.logger {}

    private val ingressPatcher = Patcher()

    fun create(shinyProxy: ShinyProxy, latestShinyProxyInstance: ShinyProxyInstance, shinyProxyUid: String) {
        val labels = LabelFactory.labelsForShinyProxy(shinyProxy.realmId).toMutableMap()
        labels[LabelFactory.LATEST_INSTANCE_LABEL] = latestShinyProxyInstance.hashOfSpec

        //@formatter:off
        val fqdns = listOf(shinyProxy.fqdn) + shinyProxy.additionalFqdns
        val rules = fqdns.map { host ->
            IngressRuleBuilder()
                .withHost(host)
                .withNewHttp()
                    .addToPaths(createPathV1(shinyProxy))
                .endHttp()
            .build()
        }

        val ingressDefinition = IngressBuilder()
                .withNewMetadata()
                    .withName(ResourceNameFactory.createNameForIngress(shinyProxy))
                    .withLabels<String, String>(labels)
                    .withAnnotations<String, String>(mapOf("nginx.org/websocket-services" to ResourceNameFactory.createNameForService(shinyProxy)))
                    .addNewOwnerReference()
                        .withController(true)
                        .withKind("ShinyProxy")
                        .withApiVersion("openanalytics.eu/v1")
                        .withName(shinyProxy.name)
                        .withUid(shinyProxyUid)
                    .endOwnerReference()
                .endMetadata()
                .withNewSpec()
                    .withIngressClassName("nginx")
                    .withRules(rules)
                .endSpec()
                .build()
            //@formatter:on

        val patchedIngress = ingressPatcher.patch(ingressDefinition, shinyProxy.getParsedIngressPatches())
        val createdIngress = kubeClient.network().v1().ingresses().inNamespace(shinyProxy.namespace).resource(patchedIngress).forceConflicts().serverSideApply()
        logger.debug { "${logPrefix(shinyProxy.realmId)} [Component/Ingress] Created ${createdIngress.metadata.name}" }
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
