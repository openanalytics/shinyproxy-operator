package eu.openanalytics.shinyproxyoperator.controller

import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.fabric8.kubernetes.client.informers.cache.Lister
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

class ShinyProxyListener(private val channel: SendChannel<ShinyProxyEvent>,
                         informer: SharedIndexInformer<ShinyProxy>,
                         private val shinyProxyLister: Lister<ShinyProxy>) {

    private val logger = KotlinLogging.logger {}

    init {
        informer.addEventHandler(object : ResourceEventHandler<ShinyProxy> {
            override fun onAdd(shinyProxy: ShinyProxy) {
                logger.debug { "ShinyProxy::OnAdd ${shinyProxy.metadata.name}" }
                runBlocking { channel.send(ShinyProxyEvent(ShinyProxyEventType.ADD, shinyProxy, null)) }
            }

            override fun onUpdate(shinyProxy: ShinyProxy, newShinyProxy: ShinyProxy) {
                logger.debug { "ShinyProxy::OnUpdate ${shinyProxy.metadata.name}: old hash ${shinyProxy.hashOfCurrentSpec}, new hash: ${newShinyProxy.hashOfCurrentSpec}" }

                if (shinyProxy.hashOfCurrentSpec == newShinyProxy.hashOfCurrentSpec) {
                    val shinyProxyInstance = shinyProxy.status.getInstanceByHash(shinyProxy.hashOfCurrentSpec)
                    if (shinyProxyInstance == null) {
                        logger.warn { "Received update of latest ShinyProxyInstance but did not found such an instance." }
                        return
                    }
                    runBlocking { channel.send(ShinyProxyEvent(ShinyProxyEventType.RECONCILE, shinyProxy, shinyProxyInstance)) }
                } else {
                    runBlocking { channel.send(ShinyProxyEvent(ShinyProxyEventType.UPDATE_SPEC, newShinyProxy, null)) }
                }
            }

            override fun onDelete(shinyProxy: ShinyProxy, b: Boolean) {
                logger.debug { "ShinyProxy::OnDelete ${shinyProxy.metadata.name}" }
                runBlocking { channel.send(ShinyProxyEvent(ShinyProxyEventType.DELETE, shinyProxy, null)) }
            }
        })
    }

}