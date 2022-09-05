/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021-2022 Open Analytics
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
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPath
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPathBuilder
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import mu.KotlinLogging

class IngressFactory(private val kubeClient: KubernetesClient) {

    private val logger = KotlinLogging.logger {}

    fun createOrReplaceIngress(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance, replicaSet: ReplicaSet) {

        val hashOfSpec = shinyProxyInstance.hashOfSpec

        val isLatest = shinyProxyInstance.isLatestInstance

        val routes = createRoutes(isLatest, hashOfSpec, shinyProxy)

        val labels = LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance).toMutableMap()
        labels[LabelFactory.INGRESS_IS_LATEST] = isLatest.toString()

        for ((routeName, routeAnnotations) in routes) {

            //@formatter:off
            val ingressDefinition = IngressBuilder()
                    .withNewMetadata()
                        .withName(ResourceNameFactory.createNameForIngress(shinyProxy, routeName, shinyProxyInstance))
                        .withLabels<String, String>(labels)
                        .addNewOwnerReference()
                            .withController(true)
                            .withKind("ReplicaSet")
                            .withApiVersion("apps/v1")
                            .withName(ResourceNameFactory.createNameForReplicaSet(shinyProxy, shinyProxyInstance))
                            .withUid(replicaSet.metadata.uid)
                        .endOwnerReference()
                        .withAnnotations<String, String>(routeAnnotations)
                    .endMetadata()
                    .withNewSpec()
                        .withIngressClassName("skipper")
                        .addNewRule()
                            .withHost(shinyProxy.fqdn)
                            .withNewHttp()
                                .addToPaths(createPathV1(shinyProxy, shinyProxyInstance))
                            .endHttp()
                        .endRule()
                    .endSpec()
                    .build()
            //@formatter:on

            val createdIngress = kubeClient.network().v1().ingresses().inNamespace(shinyProxy.metadata.namespace).createOrReplace(ingressDefinition)
            logger.debug { "${shinyProxy.logPrefix(shinyProxyInstance)} [Component/Ingress] Created ${createdIngress.metadata.name} [latest=$isLatest]" }
        }

    }

    private fun createRoutes(isLatest: Boolean, hashOfSpec: String, shinyProxy: ShinyProxy): Map<String, Map<String, String>> {
        return if (isLatest) {
            mapOf(
                "" to createRoute(true, hashOfSpec, shinyProxy, "True()"),
                "cookie-override" to createRoute(false, hashOfSpec, shinyProxy, """Cookie("sp-instance-override", "$hashOfSpec") && Weight(20)"""),
                "query-override" to createRoute(false, hashOfSpec, shinyProxy, """QueryParam("sp_instance_override", "$hashOfSpec") && Weight(20)"""),
            )
        } else {
            mapOf(
                "" to createRoute(false, hashOfSpec, shinyProxy, """Cookie("sp-instance", "$hashOfSpec") && Weight(10)"""),
                "cookie-override" to createRoute(false, hashOfSpec, shinyProxy, """Cookie("sp-instance-override", "$hashOfSpec") && Weight(20)"""),
                "query-override" to createRoute(false, hashOfSpec, shinyProxy, """QueryParam("sp_instance_override", "$hashOfSpec") && Weight(20)"""),
            )
        }
    }

    private fun createRoute(isDefaultRoute: Boolean, hashOfSpec: String, shinyProxy: ShinyProxy, predicate: String): Map<String, String> {
        val security = if (Operator.getOperatorInstance().disableSecureCookies) {
            ""
        } else {
            "Secure;"
        }

        return mapOf(
            "zalando.org/skipper-predicate" to predicate,
            "zalando.org/skipper-filter" to
                """setRequestHeader("X-ShinyProxy-Instance", "$hashOfSpec")""" +
                """ -> """ +
                """setRequestHeader("X-ShinyProxy-Latest-Instance", "${shinyProxy.hashOfCurrentSpec}")""" +
                if (isDefaultRoute) {
                    """ -> """ +
                        """appendResponseHeader("Set-Cookie", "sp-instance=$hashOfSpec; $security Path=${shinyProxy.subPath}")"""
                } else {
                    ""
                } +
                """ -> """ +
                """appendResponseHeader("Set-Cookie", "sp-latest-instance=${shinyProxy.hashOfCurrentSpec}; $security Path=${shinyProxy.subPath}")""",
        )

    }

    private fun createPathV1 (shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): HTTPIngressPath {
        //@formatter:off
        val builder = HTTPIngressPathBuilder()
                .withPathType("Prefix")
                .withPath(shinyProxy.subPath)
                .withNewBackend()
                    .withNewService()
                        .withName(ResourceNameFactory.createNameForService(shinyProxy, shinyProxyInstance))
                        .withNewPort()
                            .withNumber(80)
                        .endPort()
                    .endService()
                .endBackend()
        //@formatter:on

        return builder.build()
    }

}
