/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021-2022 Open Analytics
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

import eu.openanalytics.shinyproxyoperator.ShinyProxyClient
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.isInManagedNamespace
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.fabric8.kubernetes.client.informers.cache.Indexer
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

class ShinyProxyListener(private val channel: SendChannel<ShinyProxyEvent>, private val shinyProxyClient: ShinyProxyClient) {

    private val logger = KotlinLogging.logger {}

    private var informer: SharedIndexInformer<ShinyProxy>? = null

    fun start(): Indexer<ShinyProxy> {
       val i = shinyProxyClient.inform(object : ResourceEventHandler<ShinyProxy> {
            override fun onAdd(shinyProxy: ShinyProxy) {
                if (!isInManagedNamespace(shinyProxy)) return
                logger.debug { "${shinyProxy.logPrefix()} [Event/Add]" }
                runBlocking { channel.send(ShinyProxyEvent(ShinyProxyEventType.ADD, shinyProxy, null)) }
            }

            override fun onUpdate(shinyProxy: ShinyProxy, newShinyProxy: ShinyProxy) {
                if (!isInManagedNamespace(shinyProxy)) return

                if (shinyProxy.hashOfCurrentSpec == newShinyProxy.hashOfCurrentSpec) {
                    val shinyProxyInstance = newShinyProxy.status.getInstanceByHash(shinyProxy.hashOfCurrentSpec)
                    if (shinyProxyInstance == null) {
                        logger.warn { "${shinyProxy.logPrefix()} Received update of latest ShinyProxyInstance but did not found such an instance (looking for ${shinyProxy.hashOfCurrentSpec}, status: ${shinyProxy.status})." }
                        return
                    }
                    logger.debug { "${shinyProxy.logPrefix(shinyProxyInstance)} [Event/Update]" }
                    runBlocking { channel.send(ShinyProxyEvent(ShinyProxyEventType.RECONCILE, shinyProxy, shinyProxyInstance)) }
                } else {
                    if (shinyProxy.subPath != newShinyProxy.subPath) {
                        logger.warn { "${shinyProxy.logPrefix()} Cannot update subpath of an existing ShinyProxy Instance ${shinyProxy.metadata.name}" }
                        return
                    }
                    logger.debug { "${shinyProxy.logPrefix()} [Event/Update of spec] old hash ${shinyProxy.hashOfCurrentSpec}, new hash: ${newShinyProxy.hashOfCurrentSpec}" }

                    runBlocking { channel.send(ShinyProxyEvent(ShinyProxyEventType.UPDATE_SPEC, newShinyProxy, null)) }
                }
            }

            override fun onDelete(shinyProxy: ShinyProxy, b: Boolean) {
                logger.debug { "${shinyProxy.logPrefix()} [Event/Delete]" }
                if (!isInManagedNamespace(shinyProxy)) return
                runBlocking { channel.send(ShinyProxyEvent(ShinyProxyEventType.DELETE, shinyProxy, null)) }
            }
        }, 10 * 60 * 1000.toLong())
        informer = i
        return i.indexer!!
    }

    fun stop() {
        informer?.stop()
        informer = null
    }


}
