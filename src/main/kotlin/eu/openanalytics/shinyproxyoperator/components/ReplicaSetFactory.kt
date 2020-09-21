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
import eu.openanalytics.shinyproxyoperator.retry
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.internal.readiness.Readiness
import mu.KotlinLogging

class ReplicaSetFactory(private val kubeClient: KubernetesClient ) {

    private val logger = KotlinLogging.logger {}

    private val podTemplateSpecFactory = PodTemplateSpecFactory()

    suspend fun create(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): ReplicaSet {
       val replicaSetDefinition: ReplicaSet = ReplicaSetBuilder()
                .withNewMetadata()
                   .withName(ResourceNameFactory.createNameForReplicaSet(shinyProxy, shinyProxyInstance))
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
                    .withReplicas(1)
                    .withNewSelector()
                       .withMatchLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance))
                    .endSelector()
                    .withTemplate(podTemplateSpecFactory.create(shinyProxy, shinyProxyInstance))
                .endSpec()
                .build()

        val createdReplicaSet = kubeClient.apps().replicaSets().inNamespace(shinyProxy.metadata.namespace).create(replicaSetDefinition)
        if (retry(60, 1000) { Readiness.isReady(kubeClient.resource(createdReplicaSet).fromServer().get()) }) {
            logger.debug { "Created ReplicaSet with name ${createdReplicaSet.metadata.name}" }
            return kubeClient.resource(createdReplicaSet).fromServer().get()
        } else {
            throw RuntimeException("Could not create ReplicaSet ${createdReplicaSet.metadata.name}")
        }
    }

}