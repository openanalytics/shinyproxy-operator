package eu.openanalytics.shinyproxyoperator.controller

import ShinyProxyList
import eu.openanalytics.shinyproxyoperator.components.ConfigMapCreator
import eu.openanalytics.shinyproxyoperator.components.ReplicaSetCreator
import eu.openanalytics.shinyproxyoperator.components.ServiceCreator
import eu.openanalytics.shinyproxyoperator.crd.DoneableShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.Resource
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.fabric8.kubernetes.client.informers.cache.Cache
import io.fabric8.kubernetes.client.informers.cache.Lister
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.util.*
import java.util.AbstractMap.SimpleEntry
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue


class ShinyProxyController(private val kubernetesClient: KubernetesClient,
                           private val shinyProxyClient: MixedOperation<ShinyProxy, ShinyProxyList, DoneableShinyProxy, Resource<ShinyProxy, DoneableShinyProxy>>,
                           private val replicaSetInformer: SharedIndexInformer<ReplicaSet>,
                           private val serviceIndexInformer: SharedIndexInformer<Service>,
                           private val configMapInformer: SharedIndexInformer<ConfigMap>,
                           private val shinyProxyInformer: SharedIndexInformer<ShinyProxy>,
                           namespace: String) {

    private val workqueue: BlockingQueue<String> = ArrayBlockingQueue(1024)
    private val shinyProxyLister = Lister(shinyProxyInformer.indexer, namespace)
    private val replicaSetLister = Lister(replicaSetInformer.indexer, namespace)
    private val configMapLister = Lister(configMapInformer.indexer, namespace)
    private val serviceLister = Lister(serviceIndexInformer.indexer, namespace)

    private val logger = KotlinLogging.logger {}

    fun create() {
        shinyProxyInformer.addEventHandler(object : ResourceEventHandler<ShinyProxy> {
            override fun onAdd(shinyProxy: ShinyProxy) {
                logger.debug { "ShinyProxy::OnAdd ${shinyProxy.metadata.name}" }
                enqueuePodSet(shinyProxy)
            }

            override fun onUpdate(shinyProxy: ShinyProxy, newShinyProxy: ShinyProxy) {
                logger.debug { "ShinyProxy::OnUpdate ${shinyProxy.metadata.name}" }
                enqueuePodSet(newShinyProxy)
            }

            override fun onDelete(shinyProxy: ShinyProxy, b: Boolean) {
                logger.debug { "ShinyProxy::OnDelete ${shinyProxy.metadata.name}" }
                enqueuePodSet(shinyProxy)
            }
        })

        replicaSetInformer.addEventHandler(object : ResourceEventHandler<ReplicaSet> {
            override fun onAdd(replicaSet: ReplicaSet) {
                logger.debug { "ReplicaSet::OnAdd ${replicaSet.metadata.name}" }
                enqueuReplicaSet(replicaSet)
            }

            override fun onUpdate(replicaSet: ReplicaSet, newReplicaSet: ReplicaSet) {
                logger.debug { "ReplicaSet::OnUpdate ${replicaSet.metadata.name}" }
                enqueuReplicaSet(replicaSet)
            }

            override fun onDelete(replicaSet: ReplicaSet, b: Boolean) {
                logger.debug { "ReplicaSet::OnDelete ${replicaSet.metadata.name}" }
                enqueuReplicaSet(replicaSet)
            }

        })

        serviceIndexInformer.addEventHandler(object : ResourceEventHandler<Service> {
            override fun onAdd(service: Service) {
                logger.debug { "Service::OnAdd ${service.metadata.name}" }
                enqueuService(service)
            }

            override fun onUpdate(service: Service, newService: Service) {
                logger.debug { "Service::OnUpdate ${service.metadata.name}" }
                enqueuService(service)
            }

            override fun onDelete(service: Service, b: Boolean) {
                logger.debug { "Service::OnDelete ${service.metadata.name}" }
                enqueuService(service)
            }

        })
    }

    fun run() {
        logger.info("Starting PodSet controller")
        while (!replicaSetInformer.hasSynced() || !shinyProxyInformer.hasSynced()) {
            // Wait till Informer syncs
        }
        while (true) {
            try {
                val key = workqueue.take()
                Objects.requireNonNull(key, "key can't be null")
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

    /**
     * Tries to achieve the desired state for podset.
     *
     * @param shinyProxy specified podset
     */
    private fun reconcileSingleShinyProxy(shinyProxy: ShinyProxy) {
        logger.info { "ReconcileSingleShinyProxy: ${shinyProxy.metadata.name}" }
        val configMaps = configMMapCountByLabel(APP_LABEL, shinyProxy.metadata.name)
        if (configMaps.isEmpty()) {
            logger.debug { "0 ConfigMaps found -> creating ConfigmMap" }
            runBlocking {
                val configMap = ConfigMapCreator(shinyProxy).create(kubernetesClient)
            }
            return
        }

        val replicaSets = replicaSetCountByLabel(APP_LABEL, shinyProxy.metadata.name)
        if (replicaSets.isEmpty()) {
            logger.debug { "0 ReplicaSets found -> creating ReplicaSet" }
            runBlocking {
                val configMap = configMaps[0]
                val replicaSet = ReplicaSetCreator(shinyProxy, configMap).create(kubernetesClient)
            }
            return
        }
        val services = serviceCountByLabel(APP_LABEL, shinyProxy.metadata.name)
        if (services.isEmpty()) {
            logger.debug { "0 Services found -> creating Service" }
            runBlocking {
                val service = ServiceCreator(shinyProxy).create(kubernetesClient)
            }
            return
        }
    }

    private fun configMMapCountByLabel(label: String, shinyProxyName: String): List<ConfigMap> {
        val configMapNames = arrayListOf<ConfigMap>()
        for (configmap in configMapLister.list()) {
            if (configmap?.metadata?.labels?.entries?.contains(SimpleEntry(label, shinyProxyName)) == true) {
                configMapNames.add(configmap)
                logger.debug { "Found ConfigMap ${configmap.metadata.name}" }
            }
        }
        logger.info { "ConfigMapCount: ${configMapNames.size}, $configMapNames" }
        return configMapNames
    }

    private fun replicaSetCountByLabel(label: String, shinyProxyName: String): List<String> {
        val replicaSetNames = arrayListOf<String>()
        for (replicaSet in replicaSetLister.list()) {
            if (replicaSet?.metadata?.labels?.entries?.contains(SimpleEntry(label, shinyProxyName)) == true) {
                replicaSetNames.add(replicaSet.metadata.name)
                logger.debug { "Found ReplicaSet ${replicaSet.metadata.name} phase => ${replicaSet.status}" }
            }
        }
        logger.info { "ReplicaSetCount: ${replicaSetNames.size}, $replicaSetNames" }
        return replicaSetNames
    }

    private fun serviceCountByLabel(label: String, shinyProxyName: String): List<String> {
        val serviceNames = arrayListOf<String>()
        for (service in serviceLister.list()) {
            if (service?.metadata?.labels?.entries?.contains(SimpleEntry(label, shinyProxyName)) == true) {
                serviceNames.add(service.metadata.name)
                logger.debug { "Found ReplicaSet ${service.metadata.name} phase => ${service.status}" }
            }
        }
        logger.info { "ServiceCount: ${serviceNames.size}, $serviceNames" }
        return serviceNames
    }

    private fun enqueuePodSet(shinyProxy: ShinyProxy) {
        val key = Cache.metaNamespaceKeyFunc(shinyProxy)
        if (key != null && key.isNotEmpty()) {
            workqueue.add(key)
        }
    }

    private fun enqueuReplicaSet(replicaSet: ReplicaSet) {
        val ownerReference = getControllerOf(replicaSet) ?: return
        if (ownerReference.kind.toLowerCase() != "shinyproxy") {
            return
        }
        val podSet = shinyProxyLister[ownerReference.name] ?: return
        enqueuePodSet(podSet)
    }

    private fun enqueuService(service: Service) {
        val ownerReference = getControllerOf(service) ?: return
        if (ownerReference.kind.toLowerCase() != "shinyproxy") {
            return
        }
        val podSet = shinyProxyLister[ownerReference.name] ?: return
        enqueuePodSet(podSet)
    }

    private fun getControllerOf(resource: HasMetadata): OwnerReference? {
        val ownerReferences = resource.metadata.ownerReferences
        for (ownerReference in ownerReferences) {
            if (ownerReference.controller) {
                return ownerReference
            }
        }
        return null
    }

    companion object {
        const val APP_LABEL = "app"
    }

}
