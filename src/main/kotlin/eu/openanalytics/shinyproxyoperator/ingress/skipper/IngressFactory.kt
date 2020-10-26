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
package eu.openanalytics.shinyproxyoperator.ingress.skipper

import eu.openanalytics.shinyproxyoperator.Operator
import eu.openanalytics.shinyproxyoperator.components.LabelFactory
import eu.openanalytics.shinyproxyoperator.components.ResourceNameFactory
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.networking.v1beta1.HTTPIngressPath
import io.fabric8.kubernetes.api.model.networking.v1beta1.HTTPIngressPathBuilder
import io.fabric8.kubernetes.api.model.networking.v1beta1.Ingress
import io.fabric8.kubernetes.api.model.networking.v1beta1.IngressBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import mu.KotlinLogging

class IngressFactory(private val kubeClient: KubernetesClient) {

    private val logger = KotlinLogging.logger {}

    fun createOrReplaceIngress(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance, replicaSet: ReplicaSet) {
        val hashOfSpec = shinyProxyInstance.hashOfSpec

        // TODO this should use shinyProxyInstance.isLatestInstance ?
        val isLatest = hashOfSpec == shinyProxy.hashOfCurrentSpec

        val cookiePath = if (shinyProxy.subPath != "") {
            shinyProxy.subPath
        } else {
            "/"
        }

        val security = if (Operator.operatorInstance!!.disableSecureCookies) {
            ""
        } else {
            "Secure;"
        }

        val annotations = if (isLatest) {
            mapOf(
                    "kubernetes.io/ingress.class" to "skipper",
                    "zalando.org/skipper-predicate" to "True()",
                    "zalando.org/skipper-filter" to """appendResponseHeader("Set-Cookie",  "sp-instance=$hashOfSpec; $security Path=$cookiePath") -> appendResponseHeader("Set-Cookie", "sp-latest-instance=${shinyProxy.hashOfCurrentSpec}; $security Path=$cookiePath")"""

            )
        } else {
            mapOf(
                    "kubernetes.io/ingress.class" to "skipper",
                    "zalando.org/skipper-predicate" to """True() && Cookie("sp-instance", "$hashOfSpec")""",
                    "zalando.org/skipper-filter" to """appendResponseHeader("Set-Cookie", "sp-latest-instance=${shinyProxy.hashOfCurrentSpec}; $security Path=$cookiePath")"""
            )
        }

        val labels = LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance).toMutableMap()
        labels[LabelFactory.INGRESS_IS_LATEST] = isLatest.toString()

        //@formatter:off
        val ingressDefinition = IngressBuilder()
                .withNewMetadata()
                    .withName(ResourceNameFactory.createNameForIngress(shinyProxy, shinyProxyInstance))
                    .withLabels(labels)
                    .addNewOwnerReference()
                        .withController(true)
                        .withKind("ReplicaSet")
                        .withApiVersion("v1")
                        .withName(ResourceNameFactory.createNameForReplicaSet(shinyProxy, shinyProxyInstance))
                        .withNewUid(replicaSet.metadata.uid)
                    .endOwnerReference()
                    .withAnnotations(annotations)
                .endMetadata()
                .withNewSpec()
                    .addNewRule()
                        .withHost(shinyProxy.fqdn)
                        .withNewHttp()
                            .addToPaths(createPath(shinyProxy, shinyProxyInstance))
                        .endHttp()
                    .endRule()
                .endSpec()
                .build()
        //@formatter:on

        try {
            val createdIngress = kubeClient.network().ingress().inNamespace(shinyProxy.metadata.namespace).createOrReplace(ingressDefinition)
            logger.debug { "Created Ingress with name ${createdIngress.metadata.name} (latest=$isLatest)" }
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

    private fun createPath(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): HTTPIngressPath {
        //@formatter:off
        val builder = HTTPIngressPathBuilder()
                .withNewBackend()
                    .withServiceName(ResourceNameFactory.createNameForService(shinyProxy, shinyProxyInstance))
                    .withNewServicePort()
                        .withIntVal(80)
                    .endServicePort()
                .endBackend()
        //@formatter:on

        if (shinyProxy.subPath != "") {
            builder.withPath(shinyProxy.subPath)
        }

        return builder.build()
    }

}