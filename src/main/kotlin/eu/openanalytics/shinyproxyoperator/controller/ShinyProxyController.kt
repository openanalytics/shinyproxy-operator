package eu.openanalytics.shinyproxyoperator.controller

import eu.openanalytics.shinyproxyoperator.components.ConfigMapFactory
import eu.openanalytics.shinyproxyoperator.components.ReplicaSetFactory
import eu.openanalytics.shinyproxyoperator.components.ServiceFactory
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.fabric8.kubernetes.client.informers.cache.Lister
import kotlinx.coroutines.channels.Channel
import mu.KotlinLogging


class ShinyProxyController(private val kubernetesClient: KubernetesClient,
                           private val replicaSetInformer: SharedIndexInformer<ReplicaSet>,
                           serviceInformer: SharedIndexInformer<Service>,
                           configMapInformer: SharedIndexInformer<ConfigMap>,
                           private val shinyProxyInformer: SharedIndexInformer<ShinyProxy>,
                           namespace: String) {

    private val workqueue = Channel<ShinyProxyEvent>()
    private val shinyProxyLister = Lister(shinyProxyInformer.indexer, namespace)
    private val replicaSetLister = Lister(replicaSetInformer.indexer, namespace)
    private val configMapLister = Lister(configMapInformer.indexer, namespace)
    private val serviceLister = Lister(serviceInformer.indexer, namespace)
    private val shinyProxyListener = ShinyProxyListener(workqueue, shinyProxyInformer, shinyProxyLister)
    private val replicaSetListener = ResourceListener(workqueue, replicaSetInformer, shinyProxyLister)
    private val serviceListener = ResourceListener(workqueue, serviceInformer, shinyProxyLister)
    private val configMapListener = ResourceListener(workqueue, configMapInformer, shinyProxyLister)
    private val resourceRetriever = ResourceRetriever(replicaSetLister, configMapLister, serviceLister)
    private val configMapFactory = ConfigMapFactory(kubernetesClient)
    private val serviceFactory = ServiceFactory(kubernetesClient)
    private val replicaSetFactory = ReplicaSetFactory(kubernetesClient)

    private val logger = KotlinLogging.logger {}

    suspend fun run() {
        logger.info("Starting PodSet controller")
        while (!replicaSetInformer.hasSynced() || !shinyProxyInformer.hasSynced()) {
            // Wait till Informer syncs
        }
        while (true) {
            try {
                val event = workqueue.receive()

                when (event.eventType) {
                    ShinyProxyEventType.ADD -> {
                        reconcileSingleShinyProxy(event.shinyProxy)
                    }
                    ShinyProxyEventType.UPDATE -> {
                        // TODO calculate hash -> reconcile
                    }
                    ShinyProxyEventType.DELETE -> {
                        deleteSingleShinyProxy(event.shinyProxy)
                    }
                    ShinyProxyEventType.UPDATE_DEPENDENCY -> {
                        reconcileSingleShinyProxy(event.shinyProxy)
                    }
                }

            } catch (interruptedException: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.warn { "controller interrupted.." }
            }
        }
    }

    private fun deleteSingleShinyProxy(shinyProxy: ShinyProxy) {
        logger.info { "DeleteSingleShinyProxy: ${shinyProxy.metadata.name}" }
        for (service in resourceRetriever.getServiceByLabel(APP_LABEL, shinyProxy.metadata.name)) {
            kubernetesClient.resource(service).delete()
        }
        for (replicaSet in resourceRetriever.getReplicaSetByLabel(APP_LABEL, shinyProxy.metadata.name)) {
            kubernetesClient.resource(replicaSet).delete()
        }
        for (configMap in resourceRetriever.getConfigMapByLabel(APP_LABEL, shinyProxy.metadata.name)) {
            kubernetesClient.resource(configMap).delete()
        }
    }

    private suspend fun reconcileSingleShinyProxy(shinyProxy: ShinyProxy) {
        logger.info { "ReconcileSingleShinyProxy: ${shinyProxy.metadata.name}" }
        val configMaps = resourceRetriever.getConfigMapByLabel(APP_LABEL, shinyProxy.metadata.name)
        if (configMaps.isEmpty()) {
            logger.debug { "0 ConfigMaps found -> creating ConfigmMap" }
            configMapFactory.create(shinyProxy)
            return
        }

        val replicaSets = resourceRetriever.getReplicaSetByLabel(APP_LABEL, shinyProxy.metadata.name)
        if (replicaSets.isEmpty()) {
            logger.debug { "0 ReplicaSets found -> creating ReplicaSet" }
            val configMap = configMaps[0]
            replicaSetFactory.create(shinyProxy, configMap)
            return
        }
        val services = resourceRetriever.getServiceByLabel(APP_LABEL, shinyProxy.metadata.name)
        if (services.isEmpty()) {
            logger.debug { "0 Services found -> creating Service" }
            serviceFactory.create(shinyProxy)
            return
        }
    }


    companion object {
        const val APP_LABEL = "app"
    }

}
