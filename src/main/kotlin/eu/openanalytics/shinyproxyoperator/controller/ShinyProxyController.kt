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

    private val workqueue = Channel<String>()
    private val shinyProxyLister = Lister(shinyProxyInformer.indexer, namespace)
    private val replicaSetLister = Lister(replicaSetInformer.indexer, namespace)
    private val configMapLister = Lister(configMapInformer.indexer, namespace)
    private val serviceLister = Lister(serviceInformer.indexer, namespace)
    private val shinyProxyListener = ResourceListener(workqueue, shinyProxyInformer, shinyProxyLister)
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
                val key = workqueue.receive()
                if (key.isEmpty() || !key.contains("/")) {
                    logger.warn { "invalid resource key: $key" }
                }

                val name = key.split("/").toTypedArray()[1]
                val shinyProxy: ShinyProxy? = shinyProxyLister[key.split("/").toTypedArray()[1]]

                if (shinyProxy == null) {
                    logger.warn { "ShinyProxy $name in workqueue no longer exists" }
                    return
                }

                reconcileSingleShinyProxy(shinyProxy)
            } catch (interruptedException: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.warn { "controller interrupted.." }
            }
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
