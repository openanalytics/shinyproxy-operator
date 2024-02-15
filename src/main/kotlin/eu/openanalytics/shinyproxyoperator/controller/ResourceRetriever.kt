/**
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
package eu.openanalytics.shinyproxyoperator.controller

import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.networking.v1.Ingress
import io.fabric8.kubernetes.client.informers.cache.Lister
import mu.KotlinLogging

class ResourceRetriever(private val replicaSetLister: Lister<ReplicaSet>,
                        private val configMapLister: Lister<ConfigMap>,
                        private val serviceLister: Lister<Service>,
                        private val ingressLister: Lister<Ingress>) {

    private val logger = KotlinLogging.logger {}

    fun getConfigMapByLabels(labels: Map<String, String>, namespace: String): List<ConfigMap> {
        val configMaps = arrayListOf<ConfigMap>()
        logger.debug { "Looking for configmap with labels: $labels in namespace $namespace" }
        for (configmap in configMapLister.namespace(namespace).list()) {
            if (configmap?.metadata?.labels?.entries?.containsAll(labels.entries) == true) {
                configMaps.add(configmap)
            }
        }
        logger.info { "ConfigMapCount: ${configMaps.size}, ${configMaps.map { it.metadata.name }}" }
        return configMaps
    }

    fun getReplicaSetByLabels(labels: Map<String, String>, namespace: String): ArrayList<ReplicaSet> {
        val replicaSets = arrayListOf<ReplicaSet>()
        logger.debug { "Looking for ReplicaSets with labels: $labels" }
        for (replicaSet in replicaSetLister.namespace(namespace).list()) {
            if (replicaSet?.metadata?.labels?.entries?.containsAll(labels.entries) == true) {
                replicaSets.add(replicaSet)
            }
        }
        logger.info { "ReplicaSetCount: ${replicaSets.size}, ${replicaSets.map { it.metadata.name }}" }
        return replicaSets
    }

    fun getServiceByLabels(labels: Map<String, String>, namespace: String): List<Service> {
        val services = arrayListOf<Service>()
        logger.debug { "Looking for Services with labels: $labels" }
        for (service in serviceLister.namespace(namespace).list()) {
            if (service?.metadata?.labels?.entries?.containsAll(labels.entries) == true) {
                services.add(service)
            }
        }
        logger.info { "ServiceCount: ${services.size}, ${services.map { it.metadata.name }}" }
        return services
    }


    fun getIngressByLabels(labels: Map<String, String>, namespace: String): List<Ingress> {
        val ingresses = arrayListOf<Ingress>()
        logger.debug { "Looking for Pods with labels: $labels" }
        for (ingress in ingressLister.namespace(namespace).list()) {
            if (ingress?.metadata?.labels?.entries?.containsAll(labels.entries) == true) {
                ingresses.add(ingress)
            }
        }
        logger.info { "IngressCount: ${ingresses.size}, ${ingresses.map { it.metadata.name }}" }
        return ingresses
    }

}
