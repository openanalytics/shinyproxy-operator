package eu.openanalytics.shinyproxyoperator.controller

import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.client.informers.cache.Lister
import mu.KotlinLogging
import java.util.AbstractMap

class ResourceRetriever(private val replicaSetLister: Lister<ReplicaSet>,
                        private val configMapLister: Lister<ConfigMap>,
                        private val serviceLister: Lister<Service>) {

    private val logger = KotlinLogging.logger {}

    fun getConfigMapByLabel(label: String, shinyProxyName: String): List<ConfigMap> {
        val configMaps = arrayListOf<ConfigMap>()
        for (configmap in configMapLister.list()) {
            if (configmap?.metadata?.labels?.entries?.contains(AbstractMap.SimpleEntry(label, shinyProxyName)) == true) {
                configMaps.add(configmap)
                logger.debug { "Found ConfigMap ${configmap.metadata.name}" }
            }
        }
        logger.info { "ConfigMapCount: ${configMaps.size}, ${configMaps.map { it.metadata.name }}" }
        return configMaps
    }

    fun getReplicaSetByLabel(label: String, shinyProxyName: String): ArrayList<ReplicaSet> {
        val replicaSets = arrayListOf<ReplicaSet>()
        for (replicaSet in replicaSetLister.list()) {
            if (replicaSet?.metadata?.labels?.entries?.contains(AbstractMap.SimpleEntry(label, shinyProxyName)) == true) {
                replicaSets.add(replicaSet)
                logger.debug { "Found ReplicaSet ${replicaSet.metadata.name} phase => ${replicaSet.status}" }
            }
        }
        logger.info { "ReplicaSetCount: ${replicaSets.size}, ${replicaSets.map { it.metadata.name }}" }
        return replicaSets
    }

    fun getServiceByLabel(label: String, shinyProxyName: String): List<Service> {
        val services = arrayListOf<Service>()
        for (service in serviceLister.list()) {
            if (service?.metadata?.labels?.entries?.contains(AbstractMap.SimpleEntry(label, shinyProxyName)) == true) {
                services.add(service)
                logger.debug { "Found ReplicaSet ${service.metadata.name} phase => ${service.status}" }
            }
        }
        logger.info { "ServiceCount: ${services.size}, ${services.map { it.metadata.name }}" }
        return services
    }

}