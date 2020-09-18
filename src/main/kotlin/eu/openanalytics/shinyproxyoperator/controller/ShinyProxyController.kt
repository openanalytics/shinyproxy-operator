package eu.openanalytics.shinyproxyoperator.controller

import eu.openanalytics.shinyproxyoperator.ShinyProxyClient
import eu.openanalytics.shinyproxyoperator.components.ConfigMapFactory
import eu.openanalytics.shinyproxyoperator.components.LabelFactory
import eu.openanalytics.shinyproxyoperator.components.ReplicaSetFactory
import eu.openanalytics.shinyproxyoperator.components.ServiceFactory
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.networking.v1beta1.Ingress
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.fabric8.kubernetes.client.informers.cache.Lister
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KotlinLogging


class ShinyProxyController(private val kubernetesClient: KubernetesClient,
                           private val shinyProxyClient: ShinyProxyClient,
                           private val replicaSetInformer: SharedIndexInformer<ReplicaSet>,
                           serviceInformer: SharedIndexInformer<Service>,
                           configMapInformer: SharedIndexInformer<ConfigMap>,
                           podInformer: SharedIndexInformer<Pod>,
                           ingressInformer: SharedIndexInformer<Ingress>,
                           private val shinyProxyInformer: SharedIndexInformer<ShinyProxy>,
                           namespace: String) {

    private val workqueue = Channel<ShinyProxyEvent>(10000)
    private val shinyProxyLister = Lister(shinyProxyInformer.indexer, namespace)
    private val replicaSetLister = Lister(replicaSetInformer.indexer, namespace)
    private val configMapLister = Lister(configMapInformer.indexer, namespace)
    private val serviceLister = Lister(serviceInformer.indexer, namespace)
    private val ingressLister = Lister(ingressInformer.indexer, namespace)
    private val shinyProxyListener = ShinyProxyListener(workqueue, shinyProxyInformer, shinyProxyLister)
    private val replicaSetListener = ResourceListener(workqueue, replicaSetInformer, shinyProxyLister)
    private val serviceListener = ResourceListener(workqueue, serviceInformer, shinyProxyLister)
    private val configMapListener = ResourceListener(workqueue, configMapInformer, shinyProxyLister)
    private val podLister = Lister(podInformer.indexer)
    private val resourceRetriever = ResourceRetriever(replicaSetLister, configMapLister, serviceLister, podLister, ingressLister)
    private val configMapFactory = ConfigMapFactory(kubernetesClient)
    private val serviceFactory = ServiceFactory(kubernetesClient)
    private val replicaSetFactory = ReplicaSetFactory(kubernetesClient)
    private val ingressController = IngressController(workqueue, ingressInformer, shinyProxyLister, kubernetesClient, shinyProxyClient, resourceRetriever)

    private val logger = KotlinLogging.logger {}

    suspend fun run() {
        logger.info("Starting PodSet controller")
        while (!replicaSetInformer.hasSynced() || !shinyProxyInformer.hasSynced()) {
            // Wait till Informer syncs
        }
        GlobalScope.launch { scheduleAdditionalEvents() }
        while (true) {
            try {
                val event = workqueue.receive()

                when (event.eventType) {
                    ShinyProxyEventType.ADD -> {
                        if (event.shinyProxy == null) {
                            logger.warn { "Event of type ADD should have shinyproxy attached to it." }
                            continue
                        }
                        val newInstance = createNewInstance(event.shinyProxy)
                        reconcileSingleShinyProxyInstance(event.shinyProxy, newInstance)
                    }
                    ShinyProxyEventType.UPDATE_SPEC -> {
                        if (event.shinyProxy == null) {
                            logger.warn { "Event of type UPDATE_SPEC should have shinyproxy attached to it." }
                            continue
                        }
                        val newInstance = createNewInstance(event.shinyProxy)
                        reconcileSingleShinyProxyInstance(event.shinyProxy, newInstance)
                    }
                    ShinyProxyEventType.DELETE -> {
                        // DELETE is not needed
                    }
                    ShinyProxyEventType.RECONCILE -> {
                        if (event.shinyProxy == null) {
                            logger.warn { "Event of type RECONCILE should have shinyProxy attached to it." }
                            continue
                        }
                        if (event.shinyProxyInstance == null) {
                            logger.warn { "Event of type RECONCILE should have shinyProxyInstance attached to it." }
                            continue
                        }
                        reconcileSingleShinyProxyInstance(event.shinyProxy, event.shinyProxyInstance)
                    }
                    ShinyProxyEventType.CHECK_OBSOLETE_INSTANCES -> {
                        checkForObsoleteInstances()
                    }
                }

            } catch (interruptedException: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.warn { "controller interrupted.." }
            }
        }
    }

    private fun createNewInstance(shinyProxy: ShinyProxy): ShinyProxyInstance {
        val existingInstance = shinyProxy.status.getInstanceByHash(shinyProxy.hashOfCurrentSpec)

        if (existingInstance != null && existingInstance.isLatestInstance == true) {
            logger.warn { "Trying to create new instance which already exists and is the latest instance" }
            return existingInstance
        } else if (existingInstance != null && existingInstance.isLatestInstance == false) {
            // make the old existing instance again the latest instance
            // TODO ingressController
            shinyProxy.status.instances.forEach { it.isLatestInstance = false }
            existingInstance.isLatestInstance = true
            shinyProxyClient.updateStatus(shinyProxy)
            return existingInstance
        }

        // create new instance to replace old ones
        val newInstance = ShinyProxyInstance()
        newInstance.hashOfSpec = shinyProxy.hashOfCurrentSpec
        newInstance.isLatestInstance = true
        shinyProxy.status.instances.forEach { it.isLatestInstance = false }
        shinyProxy.status.instances.add(newInstance)
        shinyProxyClient.updateStatus(shinyProxy)

        return newInstance
    }

    private suspend fun reconcileSingleShinyProxyInstance(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance) {
        logger.info { "ReconcileSingleShinyProxy: ${shinyProxy.metadata.name} ${shinyProxyInstance.hashOfSpec}" }

        if (shinyProxyInstance.hashOfSpec == null) {
            logger.warn { "Cannot reconcile ShinProxyInstance $shinyProxyInstance because it has no hash." }
            return
        }

        if (!shinyProxy.status.instances.contains(shinyProxyInstance)) {
            logger.info { "Cannot reconcile ShinProxyInstance ${shinyProxyInstance.hashOfSpec} because it is begin deleted." }
            return
        }

        val configMaps = resourceRetriever.getConfigMapByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance))
        if (configMaps.isEmpty()) {
            logger.debug { "0 ConfigMaps found -> creating ConfigMap" }
            configMapFactory.create(shinyProxy, shinyProxyInstance)
            return
        }

        val replicaSets = resourceRetriever.getReplicaSetByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance))
        if (replicaSets.isEmpty()) {
            logger.debug { "0 ReplicaSets found -> creating ReplicaSet" }
            replicaSetFactory.create(shinyProxy, shinyProxyInstance)
            ingressController.onNewInstance(shinyProxy, shinyProxyInstance)
            return
        }

        val services = resourceRetriever.getServiceByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance))
        if (services.isEmpty()) {
            logger.debug { "0 Services found -> creating Service" }
            serviceFactory.create(shinyProxy, shinyProxyInstance)
            return
        }

        ingressController.reconcileInstance(shinyProxy, shinyProxyInstance)
    }

    private fun checkForObsoleteInstances() {
        for (shinyProxy in shinyProxyLister.list()) {
            if (shinyProxy.status.instances.size > 1) {
                // this SP has more than one instance -> check if some of them are obsolete
                // take a copy of the list to check to prevent concurrent modification
                val instancesToCheck = shinyProxy.status.instances.toList()
                for (shinyProxyInstance in instancesToCheck) {
                    if (shinyProxyInstance.isLatestInstance == true) continue
                    val hashOfSpec: String = shinyProxyInstance.hashOfSpec ?: continue
                    val pods = resourceRetriever.getPodByLabels(
                            mapOf(
                                    LabelFactory.PROXIED_APP to "true",
                                    LabelFactory.INSTANCE_LABEL to hashOfSpec
                            )
                    )

                    if (pods.isEmpty()) {
                        logger.info { "ShinyProxyInstance ${shinyProxyInstance.hashOfSpec} has no running apps and is not the latest version => removing this instance" }
                        deleteSingleShinyProxyInstance(shinyProxy, shinyProxyInstance)
                        shinyProxy.status.instances.remove(shinyProxyInstance)
                        shinyProxyClient.updateStatus(shinyProxy)
                    }
                }
            }
        }
    }

    private suspend fun scheduleAdditionalEvents() {
        while (true) {
            workqueue.send(ShinyProxyEvent(ShinyProxyEventType.CHECK_OBSOLETE_INSTANCES, null, null))
            delay(3000)
        }
    }

    private fun deleteSingleShinyProxyInstance(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance) {
        logger.info { "DeleteSingleShinyProxyInstance: ${shinyProxy.metadata.name} ${shinyProxyInstance.hashOfSpec}" }
        for (service in resourceRetriever.getServiceByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance))) {
            kubernetesClient.resource(service).delete()
        }
        for (replicaSet in resourceRetriever.getReplicaSetByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance))) {
            kubernetesClient.resource(replicaSet).delete()
        }
        for (configMap in resourceRetriever.getConfigMapByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance))) {
            kubernetesClient.resource(configMap).delete()
        }
    }


}
