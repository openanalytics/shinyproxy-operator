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
package eu.openanalytics.shinyproxyoperator.controller

import eu.openanalytics.shinyproxyoperator.components.LabelFactory
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.NamespacedKubernetesClient
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import mu.KotlinLogging

class PodRetriever(private val client: NamespacedKubernetesClient) {

    private val logger = KotlinLogging.logger {}
    private val namespaces = HashSet<String>()

    fun addNamespace(namespace: String) {
        if (namespaces.add(namespace)) {
            logger.warn { "Now watching pods in the $namespace namespace. (total count = ${namespaces.size})" }
        }
    }

    fun getPodsForShinyProxyInstance(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): List<Pod> {
        val pods = arrayListOf<Pod>()
        val labels = mapOf(
            LabelFactory.PROXIED_APP to "true",
            LabelFactory.INSTANCE_LABEL to shinyProxyInstance.hashOfSpec
        )

        val namespacesToCheck = if (shinyProxyInstance.isLatestInstance) {
            shinyProxy.namespacesOfCurrentInstance
        } else {
            // We don't know the exact namespaces used by older ShinyProxyInstance, therefore we have to look into all namespaces.
            // We could save the list of namespaces in the status of the instance, if it turns out this is a performance bottleneck.
            // Note: that currently this function is only called for older SP instances and thus this else statement is actually always executed...
            namespaces
        }

        logger.debug { "Looking for Pods managed by ${shinyProxyInstance.hashOfSpec} using $labels in $namespacesToCheck" }

        for (namespace in namespacesToCheck) {
            pods.addAll(client.pods().inNamespace(namespace).withLabels(labels).list().items)
        }

        logger.info { "PodCount: ${pods.size}, ${pods.map { it.metadata.namespace + "/" + it.metadata.name }}" }
        return pods
    }

    fun addNamespaces(namespaces: List<String>) {
        namespaces.forEach { addNamespace(it) }
    }

    fun getNamespaces(): Set<String> {
        return namespaces
    }

    fun stop() {
        informers.forEach { it.value.stop() }
    }

}
