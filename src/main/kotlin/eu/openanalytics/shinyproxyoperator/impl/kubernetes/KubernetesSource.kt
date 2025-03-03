/*
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021-2024 Open Analytics
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
package eu.openanalytics.shinyproxyoperator.impl.kubernetes

import eu.openanalytics.shinyproxyoperator.IShinyProxySource
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.crd.ShinyProxyCustomResource
import eu.openanalytics.shinyproxyoperator.event.ShinyProxyEvent
import eu.openanalytics.shinyproxyoperator.event.ShinyProxyEventType
import eu.openanalytics.shinyproxyoperator.logPrefix
import eu.openanalytics.shinyproxyoperator.model.ShinyProxy
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyStatus
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.fabric8.kubernetes.client.informers.cache.Lister
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

class KubernetesSource(private val shinyProxyClient: ShinyProxyClient,
                       private val channel: Channel<ShinyProxyEvent>,
                       private val mode: Mode,
                       private val managedNamespace: String?) : IShinyProxySource() {

    private lateinit var informer: SharedIndexInformer<ShinyProxyCustomResource>
    private lateinit var lister: Lister<ShinyProxyCustomResource>
    private val logger = KotlinLogging.logger {}

    override suspend fun init() {
        informer = shinyProxyClient.inform(object : ResourceEventHandler<ShinyProxyCustomResource> {
            override fun onAdd(shinyProxy: ShinyProxyCustomResource) {
                if (!isInManagedNamespace(shinyProxy)) return
                logger.debug { "${logPrefix(shinyProxy.realmId)} [Event/Add]" }
                runBlocking { channel.send(ShinyProxyEvent(ShinyProxyEventType.ADD, shinyProxy.realmId, shinyProxy.metadata.name, shinyProxy.metadata.namespace, null)) }
            }

            override fun onUpdate(shinyProxy: ShinyProxyCustomResource, newShinyProxy: ShinyProxyCustomResource) {
                if (!isInManagedNamespace(shinyProxy)) return

                if (shinyProxy.hashOfCurrentSpec == newShinyProxy.hashOfCurrentSpec) {
                    logger.debug { "${logPrefix(shinyProxy.realmId)} [Event/Update]" }
                    runBlocking {
                        channel.send(ShinyProxyEvent(
                                ShinyProxyEventType.RECONCILE,
                                shinyProxy.realmId,
                                shinyProxy.metadata.name,
                                shinyProxy.metadata.namespace,
                                shinyProxy.hashOfCurrentSpec)
                        )
                    }
                } else {
                    if (shinyProxy.subPath != newShinyProxy.subPath) {
                        logger.warn { "${logPrefix(shinyProxy.realmId)} Cannot update subpath of an existing ShinyProxy Instance ${shinyProxy.metadata.name}" }
                        return
                    }
                    logger.debug { "${logPrefix(shinyProxy.realmId)} [Event/Update of spec] old hash ${shinyProxy.hashOfCurrentSpec}, new hash: ${newShinyProxy.hashOfCurrentSpec}" }

                    runBlocking {
                        channel.send(ShinyProxyEvent(
                                ShinyProxyEventType.UPDATE_SPEC,
                                shinyProxy.realmId,
                                shinyProxy.metadata.name,
                                shinyProxy.metadata.namespace,
                                shinyProxy.hashOfCurrentSpec)
                        )
                    }
                }
            }

            override fun onDelete(shinyProxy: ShinyProxyCustomResource, b: Boolean) {
            }
        }, 10 * 60 * 1000.toLong())
        lister = Lister(informer.indexer)
    }

    override suspend fun run() {
    }

    override suspend fun get(namespace: String, name: String): ShinyProxy? {
        val sp = shinyProxyClient.inNamespace(namespace).withName(name).get() ?: return null
        return ShinyProxy(sp.spec, name, namespace)
    }

    fun listStatus(): List<ShinyProxyStatus> {
        return lister.list().map { it.getSpStatus() }
    }

    fun stop() {
        if (::informer.isInitialized) {
            informer.stop()
        }
    }

    private fun isInManagedNamespace(shinyProxy: ShinyProxyCustomResource): Boolean {
        when (mode) {
            Mode.CLUSTERED -> return true
            Mode.NAMESPACED -> {
                if (shinyProxy.metadata.namespace == managedNamespace) {
                    return true
                }
                logger.debug { "ShinyProxy ${shinyProxy.metadata.name} in namespace ${shinyProxy.metadata.namespace} isn't managed by this operator." }
                return false
            }
        }
    }

}
