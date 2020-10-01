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

import eu.openanalytics.shinyproxyoperator.components.LabelFactory
import eu.openanalytics.shinyproxyoperator.components.ResourceNameFactory
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.networking.v1beta1.Ingress
import io.fabric8.kubernetes.api.model.networking.v1beta1.IngressBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.internal.SerializationUtils
import mu.KotlinLogging
import java.lang.RuntimeException

class IngressFactory(private val kubeClient: KubernetesClient) {

    private val logger = KotlinLogging.logger {}

    fun createOrReplaceIngress(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance, replicaSet: ReplicaSet): Ingress? {
        val hashOfSpec = shinyProxyInstance.hashOfSpec

        // TODO this should use shinyProxyInstance.isLatestInstance ?
        val isLatest = hashOfSpec == shinyProxy.hashOfCurrentSpec
        val annotations = if (isLatest) {
            mapOf(
                    "kubernetes.io/ingress.class" to "skipper",
                    "zalando.org/skipper-predicate" to "True()",
                    "zalando.org/skipper-filter" to """jsCookie("sp-instance", "$hashOfSpec") -> jsCookie("sp-latest-instance", "${shinyProxy.hashOfCurrentSpec}")"""
            )
        } else {
            mapOf(
                    "kubernetes.io/ingress.class" to "skipper",
                    "zalando.org/skipper-predicate" to """True() && Cookie("sp-instance", "$hashOfSpec")""",
                    "zalando.org/skipper-filter" to """jsCookie("sp-latest-instance", "${shinyProxy.hashOfCurrentSpec}")"""
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
                            .addNewPath()
                                .withNewBackend()
                                    .withServiceName(ResourceNameFactory.createNameForService(shinyProxy, shinyProxyInstance))
                                    .withNewServicePort()
                                        .withIntVal(80)
                                    .endServicePort()
                                .endBackend()
                            .endPath()
                        .endHttp()
                    .endRule()
                .endSpec()
                .build()
        //@formatter:on

        val createdIngress = kubeClient.network().ingress().inNamespace(shinyProxy.metadata.namespace).createOrReplace(ingressDefinition)

        logger.debug { "Created Ingress with name ${createdIngress.metadata.name} (latest=$isLatest)" }

        return kubeClient.resource(createdIngress).fromServer().get()
    }

}