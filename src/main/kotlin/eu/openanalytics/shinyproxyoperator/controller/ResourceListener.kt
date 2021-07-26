/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021 Open Analytics
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
                logger.trace { "${resource.kind}::OnAdd ${resource.metadata.name}" }
                runBlocking { enqueueResource(resource) }
            }

            override fun onUpdate(resource: T, newResource: T) {
                logger.trace { "${resource.kind}::OnUpdate ${resource.metadata.name}" }
                runBlocking { enqueueResource(newResource) }
            }

            override fun onDelete(resource: T, b: Boolean) {
                logger.trace { "${resource.kind}::OnDelete ${resource.metadata.name}" }
                runBlocking { enqueueResource(resource) }
            }
        })
    }

    private suspend fun enqueueResource(resource: T) {
        val ownerReference = getShinyProxyOwnerRef(resource) ?: return

        val shinyProxy = shinyProxyLister.namespace(resource.metadata.namespace)[ownerReference.name] ?: return
        if (!isInManagedNamespace(shinyProxy)) return
        val hashOfInstance = resource.metadata.labels[LabelFactory.INSTANCE_LABEL]
        if (hashOfInstance == null) {
            logger.warn { "[${resource.kind}] [${resource.metadata.namespace}/${resource.metadata.name}] Cannot find hash of instance for this resource - probably the resource is being deleted" }
            return
        }

        val shinyProxyInstance = shinyProxy.status.getInstanceByHash(hashOfInstance)
        if (shinyProxyInstance == null) {
            logger.warn { "[${resource.kind}] [${resource.metadata.namespace}/${resource.metadata.name}] Cannot find hash of instance for this resource - probably the resource is being deleted" }
            return
        }

        logger.debug { "${shinyProxy.logPrefix(shinyProxyInstance)} [Event/Update of component] [Component/${resource.kind}]" }
        channel.send(ShinyProxyEvent(ShinyProxyEventType.RECONCILE, shinyProxy, shinyProxyInstance))
    }


    private fun getShinyProxyOwnerRef(resource: HasMetadata): OwnerReference? {
        val ownerReferences = resource.metadata.ownerReferences
        for (ownerReference in ownerReferences) {
            if (ownerReference.kind.toLowerCase() == "shinyproxy") {
                return ownerReference
            }
        }

        return null
    }

}
