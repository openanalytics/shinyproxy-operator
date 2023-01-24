/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021-2023 Open Analytics
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

import eu.openanalytics.shinyproxyoperator.components.LabelFactory
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.isInManagedNamespace
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.KubernetesResourceList
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.Resource
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.fabric8.kubernetes.client.informers.cache.Indexer
import io.fabric8.kubernetes.client.informers.cache.Lister
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.util.*

class ResourceListener<T : HasMetadata, L : KubernetesResourceList<T>, R : Resource<T>>(
    private val channel: SendChannel<ShinyProxyEvent>, private val resourceClient: MixedOperation<T, L, R>
) {

    private val logger = KotlinLogging.logger {}

    private var informer: SharedIndexInformer<T>? = null

    fun start(shinyProxyLister: Lister<ShinyProxy>): Indexer<T>? {
        val i = resourceClient.inform(object : ResourceEventHandler<T> {
            override fun onAdd(resource: T) {
                logger.trace { "${resource.kind}::OnAdd ${resource.metadata.name}" }
                runBlocking { enqueueResource(shinyProxyLister, "Add", resource) }
            }

            override fun onUpdate(resource: T, newResource: T) {
                logger.trace { "${resource.kind}::OnUpdate ${resource.metadata.name}" }
                runBlocking { enqueueResource(shinyProxyLister, "Update", newResource) }
            }

            override fun onDelete(resource: T, b: Boolean) {
                logger.trace { "${resource.kind}::OnDelete ${resource.metadata.name}" }
                runBlocking { enqueueResource(shinyProxyLister, "Delete", resource) }
            }
        })
        informer = i
        return i.indexer
    }

    private suspend fun enqueueResource(shinyProxyLister: Lister<ShinyProxy>, trigger: String, resource: T) {
        val ownerReference = getShinyProxyOwnerRef(resource) ?: return

        val shinyProxy = shinyProxyLister.namespace(resource.metadata.namespace)[ownerReference.name] ?: return
        if (!isInManagedNamespace(shinyProxy)) return
        val hashOfInstance = resource.metadata.labels[LabelFactory.INSTANCE_LABEL]
            ?: shinyProxy.status.latestInstance()?.hashOfSpec
            ?: return

        val shinyProxyInstance = shinyProxy.status.getInstanceByHash(hashOfInstance)
        if (shinyProxyInstance == null) {
            logger.warn { "[${resource.kind}] [${resource.metadata.namespace}/${resource.metadata.name}] Cannot find hash of instance for this resource - probably the resource is being deleted" }
            return
        }

        logger.debug { "${shinyProxy.logPrefix(shinyProxyInstance)} [Event/${trigger} component] [Component/${resource.kind}]" }
        channel.send(ShinyProxyEvent(ShinyProxyEventType.RECONCILE, shinyProxy, shinyProxyInstance))
    }


    private fun getShinyProxyOwnerRef(resource: HasMetadata): OwnerReference? {
        val ownerReferences = resource.metadata.ownerReferences
        for (ownerReference in ownerReferences) {
            if (ownerReference.kind.lowercase(Locale.getDefault()) == "shinyproxy") {
                return ownerReference
            }
        }

        return null
    }

    fun stop() {
        informer?.stop()
        informer = null
    }

}
