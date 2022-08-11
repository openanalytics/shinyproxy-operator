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
package eu.openanalytics.shinyproxyoperator.ingress.skipper

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
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.util.*

// TODO this has some duplicate code with ResourceListener<T>
class RouteGroupListener(private val ingressController: IngressController,
                         private val routeGroupClient: MixedOperation<RouteGroup, KubernetesResourceList<RouteGroup>, Resource<RouteGroup>>) {

    private var informer: SharedIndexInformer<RouteGroup>? = null
    private val logger = KotlinLogging.logger {}

    fun start(shinyProxyLister: Lister<ShinyProxy>): Indexer<RouteGroup>? {
       val i = routeGroupClient.inform(object : ResourceEventHandler<RouteGroup> {
            override fun onAdd(resource: RouteGroup) {
                logger.trace { "${resource.kind}::OnAdd ${resource.metadata.name}" }
                runBlocking { enqueueResource(shinyProxyLister, "Add", resource) }
            }

            override fun onUpdate(resource: RouteGroup, newResource: RouteGroup) {
                logger.trace { "${resource.kind}::OnUpdate ${resource.metadata.name}" }
                runBlocking { enqueueResource(shinyProxyLister, "Update", resource) }
            }

            override fun onDelete(resource: RouteGroup, b: Boolean) {
                logger.trace { "${resource.kind}::OnDelete ${resource.metadata.name}" }
                runBlocking { enqueueResource(shinyProxyLister, "Delete", resource) }
            }
        })
        informer = i
        return i.indexer
    }

    fun stop() {
        informer?.stop()
        informer = null
    }

    private fun enqueueResource(shinyProxyLister: Lister<ShinyProxy>, trigger: String, resource: RouteGroup) {
        val ownerReference = getShinyProxyOwnerRefByKind(resource, "ShinyProxy") ?: return

        val shinyProxy = shinyProxyLister.namespace(resource.metadata.namespace)[ownerReference.name] ?: return
        if (!isInManagedNamespace(shinyProxy)) return
        logger.debug { "${shinyProxy.logPrefix()} [Event/${trigger} component] [Component/${resource.kind}]" }
        ingressController.reconcileMetadataEndpoint(shinyProxy)
    }

    private fun getShinyProxyOwnerRefByKind(resource: HasMetadata, kind: String): OwnerReference? {
        val ownerReferences = resource.metadata.ownerReferences
        for (ownerReference in ownerReferences) {
            if (ownerReference.kind.lowercase(Locale.getDefault()) == kind.lowercase(Locale.getDefault())) {
                return ownerReference
            }
        }

        return null
    }

}
