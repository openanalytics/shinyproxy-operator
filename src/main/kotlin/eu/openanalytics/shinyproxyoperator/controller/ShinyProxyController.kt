package eu.openanalytics.shinyproxyoperator.controller

import eu.openanalytics.shinyproxyoperator.components.ConfigMapFactory
import eu.openanalytics.shinyproxyoperator.components.LabelFactory
import eu.openanalytics.shinyproxyoperator.components.ReplicaSetFactory
import eu.openanalytics.shinyproxyoperator.components.ServiceFactory
import eu.openanalytics.shinyproxyoperator.crd.DoneableShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyList
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.Resource
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.fabric8.kubernetes.client.informers.cache.Lister
import kotlinx.coroutines.channels.Channel
import mu.KotlinLogging


class ShinyProxyController(private val kubernetesClient: KubernetesClient,
                           private val shinyProxyClient: MixedOperation<ShinyProxy, ShinyProxyList, DoneableShinyProxy, Resource<ShinyProxy, DoneableShinyProxy>>,
                           private val replicaSetInformer: SharedIndexInformer<ReplicaSet>,
                           serviceInformer: SharedIndexInformer<Service>,
                           configMapInformer: SharedIndexInformer<ConfigMap>,
                           private val shinyProxyInformer: SharedIndexInformer<ShinyProxy>,
                           namespace: String) {

    private val workqueue = Channel<ShinyProxyEvent>(10000)
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
                        reconcileSingleShinyProxy(event.shinyProxy, event.shinyProxyInstance)
                    }
                    ShinyProxyEventType.UPDATE -> {
                        // TODO calculate hash -> reconcile
                        reconcileSingleShinyProxy(event.shinyProxy, event.shinyProxyInstance)
                    }
                    ShinyProxyEventType.DELETE -> {
                        // DELETE is not needed
//                        deleteSingleShinyProxy(event.shinyProxy)
                    }
                    ShinyProxyEventType.UPDATE_DEPENDENCY -> {
                        reconcileSingleShinyProxy(event.shinyProxy, event.shinyProxyInstance)
                    }
                }

            } catch (interruptedException: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.warn { "controller interrupted.." }
            }
        }
    }

//    private fun deleteSingleShinyProxy(shinyProxy: ShinyProxy) {
//        logger.info { "DeleteSingleShinyProxy: ${shinyProxy.metadata.name}" }
//        for (service in resourceRetriever.getServiceByLabel(APP_LABEL, shinyProxy.metadata.name)) {
//            kubernetesClient.resource(service).delete()
//        }
//        for (replicaSet in resourceRetriever.getReplicaSetByLabel(APP_LABEL, shinyProxy.metadata.name)) {
//            kubernetesClient.resource(replicaSet).delete()
//        }
//        for (configMap in resourceRetriever.getConfigMapByLabel(APP_LABEL, shinyProxy.metadata.name)) {
//            kubernetesClient.resource(configMap).delete()
//        }
//    }

    private suspend fun reconcileSingleShinyProxy(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance?) {
        logger.info { "ReconcileSingleShinyProxy: ${shinyProxy.metadata.name}" }

        if (shinyProxy.status.instances.isEmpty()) {
            val instance = ShinyProxyInstance()
            instance.hashOfSpec = shinyProxy.calculateHashOfCurrentSpec()
            instance.isLatestInstance = true
            shinyProxy.status.instances.add(instance)
            shinyProxyClient.updateStatus(shinyProxy)
            return
        }

        if (shinyProxyInstance == null) {
            TODO("Should not happend")
        }

        if (shinyProxyInstance.hashOfSpec == null) {
            TODO("Should not happend")
        }

        val configMaps = resourceRetriever.getConfigMapByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance))
        if (configMaps.isEmpty()) {
            logger.debug { "0 ConfigMaps found -> creating ConfigmMap" }
            configMapFactory.create(shinyProxy)
            return
        }

        val replicaSets = resourceRetriever.getReplicaSetByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance))
        if (replicaSets.isEmpty()) {
            logger.debug { "0 ReplicaSets found -> creating ReplicaSet" }
            replicaSetFactory.create(shinyProxy)
            return
        }
        val services = resourceRetriever.getServiceByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance))
        if (services.isEmpty()) {
            logger.debug { "0 Services found -> creating Service" }
            serviceFactory.create(shinyProxy)
            return
        }
    }


}
