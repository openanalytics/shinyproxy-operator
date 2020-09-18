package eu.openanalytics.shinyproxyoperator.controller

import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.client.informers.cache.Lister
import mu.KotlinLogging

class ResourceRetriever(private val replicaSetLister: Lister<ReplicaSet>,
                        private val configMapLister: Lister<ConfigMap>,
                        private val serviceLister: Lister<Service>,
                        private val podLister: Lister<Pod>) {

    private val logger = KotlinLogging.logger {}

    fun getConfigMapByLabels(labels: Map<String, String>): List<ConfigMap> {
        val configMaps = arrayListOf<ConfigMap>()
        logger.debug { "Looking for configmap with labels: $labels" }
        for (configmap in configMapLister.list()) {
            logger.debug { "Found ConfigMap ${configmap.metadata.name}" }
            if (configmap?.metadata?.labels?.entries?.containsAll(labels.entries) == true) {
                configMaps.add(configmap)
            }
        }
        logger.info { "ConfigMapCount: ${configMaps.size}, ${configMaps.map { it.metadata.name }}" }
        return configMaps
    }

    fun getReplicaSetByLabels(labels: Map<String, String>): ArrayList<ReplicaSet> {
        val replicaSets = arrayListOf<ReplicaSet>()
        logger.debug { "Looking for Repliacas with labels: $labels" }
        for (replicaSet in replicaSetLister.list()) {
            if (replicaSet?.metadata?.labels?.entries?.containsAll(labels.entries) == true) {
                replicaSets.add(replicaSet)
            }
        }
        logger.info { "ReplicaSetCount: ${replicaSets.size}, ${replicaSets.map { it.metadata.name }}" }
        return replicaSets
    }

    fun getServiceByLabels(labels: Map<String, String>): List<Service> {
        val services = arrayListOf<Service>()
        logger.debug { "Looking for Services with labels: $labels" }
        for (service in serviceLister.list()) {
            if (service?.metadata?.labels?.entries?.containsAll(labels.entries) == true) {
                services.add(service)
            }
        }
        logger.info { "ServiceCount: ${services.size}, ${services.map { it.metadata.name }}" }
        return services
    }

    fun getPodByLabels(labels: Map<String, String>): List<Pod> {
        val pods = arrayListOf<Pod>()
        logger.debug { "Looking for Pods with labels: $labels" }
        for (service in podLister.list()) {
            if (service?.metadata?.labels?.entries?.containsAll(labels.entries) == true) {
                pods.add(service)
            }
        }
        logger.info { "PodCount: ${pods.size}, ${pods.map { it.metadata.name }}" }
        return pods
    }

}