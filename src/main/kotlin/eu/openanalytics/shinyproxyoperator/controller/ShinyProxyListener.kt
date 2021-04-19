/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2020 Open Analytics
 *
 * ===========================================================================
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License as published by
 * The Apache Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Apache License for more details.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/>
 */
package eu.openanalytics.shinyproxyoperator.controller

import eu.openanalytics.shinyproxyoperator.Operator
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.isInManagedNamespace
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
                logger.debug { "ShinyProxy::OnAdd ${shinyProxy.metadata.name} in namespace ${shinyProxy.metadata.namespace}" }
                if (!isInManagedNamespace(shinyProxy)) return
                runBlocking { channel.send(ShinyProxyEvent(ShinyProxyEventType.ADD, shinyProxy, null)) }
            }

            override fun onUpdate(shinyProxy: ShinyProxy, newShinyProxy: ShinyProxy) {
                logger.debug { "ShinyProxy::OnUpdate ${shinyProxy.metadata.name} in namespace ${shinyProxy.metadata.namespace}: old hash ${shinyProxy.hashOfCurrentSpec}, new hash: ${newShinyProxy.hashOfCurrentSpec}" }
                if (!isInManagedNamespace(shinyProxy)) return

                if (shinyProxy.hashOfCurrentSpec == newShinyProxy.hashOfCurrentSpec) {
                    val shinyProxyInstance = newShinyProxy.status.getInstanceByHash(shinyProxy.hashOfCurrentSpec)
                    if (shinyProxyInstance == null) {
                        logger.warn { "Received update of latest ShinyProxyInstance but did not found such an instance (looking for ${shinyProxy.hashOfCurrentSpec}, status: ${shinyProxy.status})." }
                        return
                    }
                    runBlocking { channel.send(ShinyProxyEvent(ShinyProxyEventType.RECONCILE, shinyProxy, shinyProxyInstance)) }
                } else {
                    if (shinyProxy.subPath != newShinyProxy.subPath) {
                        logger.warn { "Cannot update subpath of an existing ShinyProxy Instance ${shinyProxy.metadata.name}" }
                        return
                    }

                    runBlocking { channel.send(ShinyProxyEvent(ShinyProxyEventType.UPDATE_SPEC, newShinyProxy, null)) }
                }
            }

            override fun onDelete(shinyProxy: ShinyProxy, b: Boolean) {
                logger.debug { "ShinyProxy::OnDelete ${shinyProxy.metadata.name} in namespace ${shinyProxy.metadata.namespace}" }
                if (!isInManagedNamespace(shinyProxy)) return
                runBlocking { channel.send(ShinyProxyEvent(ShinyProxyEventType.DELETE, shinyProxy, null)) }
            }
        })
    }


}