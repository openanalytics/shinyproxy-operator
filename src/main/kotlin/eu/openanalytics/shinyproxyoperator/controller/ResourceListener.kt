package eu.openanalytics.shinyproxyoperator.controller

import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.fabric8.kubernetes.client.informers.cache.Cache
import io.fabric8.kubernetes.client.informers.cache.Lister
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

class ResourceListener<T : HasMetadata>(private val channel: SendChannel<String>,
                                        informer: SharedIndexInformer<T>,
                                        private val shinyProxyLister: Lister<ShinyProxy>) {

    private val logger = KotlinLogging.logger {}

    init {
        informer.addEventHandler(object : ResourceEventHandler<T> {
            override fun onAdd(resource: T) {
                logger.debug { "${resource.kind}::OnAdd ${resource.metadata.name}" }
                runBlocking { enqueuResource(resource) }
            }

            override fun onUpdate(resource: T, newResource: T) {
                logger.debug { "${resource.kind}::OnUpdate ${resource.metadata.name}" }
                runBlocking { enqueuResource(resource) }
            }

            override fun onDelete(resource: T, b: Boolean) {
                logger.debug { "${resource.kind}::OnDelete ${resource.metadata.name}" }
                runBlocking { enqueuResource(resource) }
            }
        })
    }

    private suspend fun enqueuResource(resource: T) {
        val shinyProxy = if (resource is ShinyProxy) {
            resource
        } else {
            val ownerReference = getControllerOf(resource) ?: return
            if (ownerReference.kind.toLowerCase() != "shinyproxy") {
                return
            }
            shinyProxyLister[ownerReference.name] ?: return
        }
        val key = Cache.metaNamespaceKeyFunc(shinyProxy)
        if (key != null && key.isNotEmpty()) {
            channel.send(key)
        }
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

}