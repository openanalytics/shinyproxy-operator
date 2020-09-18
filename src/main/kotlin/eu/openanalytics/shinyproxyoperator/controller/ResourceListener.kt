package eu.openanalytics.shinyproxyoperator.controller

import eu.openanalytics.shinyproxyoperator.components.LabelFactory
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.fabric8.kubernetes.client.informers.cache.Lister
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

class ResourceListener<T : HasMetadata>(private val channel: SendChannel<ShinyProxyEvent>,
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
        val ownerReference = getControllerOf(resource) ?: return
        if (ownerReference.kind.toLowerCase() != "shinyproxy") {
            return
        }

        val shinyProxy = shinyProxyLister[ownerReference.name] ?: return
        val hashOfInstance = resource.metadata.labels[LabelFactory.INSTANCE_LABEL]
        if (hashOfInstance == null) {
            logger.warn { "Cannot find hash of instance for resource ${resource}, probably some labels are wrong." }
            return
        }

        val shinyProxyInstance = shinyProxy.status.getInstanceByHash(hashOfInstance)
        if (shinyProxyInstance == null) {
            logger.warn { "Cannot find instance based on hash for resource ${resource}, probably some labels are wrong." }
            return
        }

        channel.send(ShinyProxyEvent(ShinyProxyEventType.RECONCILE, shinyProxy, shinyProxyInstance))
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