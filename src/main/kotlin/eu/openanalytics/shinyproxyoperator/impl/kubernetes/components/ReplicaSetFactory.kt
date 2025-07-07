/*
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021-2025 Open Analytics
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

import eu.openanalytics.shinyproxyoperator.Config
import eu.openanalytics.shinyproxyoperator.LabelFactory
import eu.openanalytics.shinyproxyoperator.logPrefix
import eu.openanalytics.shinyproxyoperator.model.ShinyProxy
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.oshai.kotlinlogging.KotlinLogging

class ReplicaSetFactory(private val kubeClient: KubernetesClient, config: Config) {

    private val logger = KotlinLogging.logger {}

    private val podTemplateSpecFactory = PodTemplateSpecFactory(config)

    fun create(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance, shinyProxyUid: String) {
        //@formatter:off
        val replicaSetDefinition: ReplicaSet = ReplicaSetBuilder()
                .withNewMetadata()
                   .withName(ResourceNameFactory.createNameForReplicaSet(shinyProxy, shinyProxyInstance))
                   .withNamespace(shinyProxy.namespace)
                   .withLabels<String, String>(LabelFactory.labelsForShinyProxyInstance(shinyProxyInstance))
                   .addNewOwnerReference()
                        .withController(true)
                        .withKind("ShinyProxy")
                        .withApiVersion("openanalytics.eu/v1")
                        .withName(shinyProxy.name)
                        .withUid(shinyProxyUid)
                    .endOwnerReference()
                .endMetadata()
                .withNewSpec()
                    .withReplicas(shinyProxy.replicas)
                    .withNewSelector()
                       .withMatchLabels<String, String>(LabelFactory.labelsForShinyProxyInstance(shinyProxyInstance))
                    .endSelector()
                    .withTemplate(podTemplateSpecFactory.create(shinyProxy, shinyProxyInstance))
                .endSpec()
                .build()
        //@formatter:on

        val createdReplicaSet = kubeClient.apps().replicaSets().inNamespace(shinyProxy.namespace).resource(replicaSetDefinition).forceConflicts().serverSideApply()
        logger.debug { "${logPrefix(shinyProxyInstance)} [Component/ReplicaSet] Created ${createdReplicaSet.metadata.name}" }
    }

}
