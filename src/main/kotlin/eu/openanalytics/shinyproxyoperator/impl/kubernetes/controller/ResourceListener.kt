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
package eu.openanalytics.shinyproxyoperator.impl.kubernetes.controller

import eu.openanalytics.shinyproxyoperator.LabelFactory
import eu.openanalytics.shinyproxyoperator.event.ShinyProxyEvent
import eu.openanalytics.shinyproxyoperator.event.ShinyProxyEventType
import eu.openanalytics.shinyproxyoperator.model.ShinyProxy
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance
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
import io.github.oshai.kotlinlogging.KotlinLogging

class ResourceListener<T : HasMetadata, L : KubernetesResourceList<T>, R : Resource<T>>(
        private val channel: SendChannel<ShinyProxyEvent>, private val resourceClient: MixedOperation<T, L, R>
) {

    private val logger = KotlinLogging.logger {}

    private lateinit var informer: SharedIndexInformer<T>
    private lateinit var indexer: Indexer<T>
    private lateinit var lister: Lister<T>

    fun start() {
        informer = resourceClient.inform(object : ResourceEventHandler<T> {
            override fun onAdd(resource: T) {
                logger.trace { "${resource.kind}::OnAdd ${resource.metadata.name}" }
                runBlocking { enqueueResource("Add", resource) }
            }

            override fun onUpdate(resource: T, newResource: T) {
                logger.trace { "${resource.kind}::OnUpdate ${resource.metadata.name}" }
                runBlocking { enqueueResource("Update", newResource) }
            }

            override fun onDelete(resource: T, b: Boolean) {
                logger.trace { "${resource.kind}::OnDelete ${resource.metadata.name}" }
                runBlocking { enqueueResource("Delete", resource) }
            }
        })
        indexer = informer.indexer
        lister = Lister(indexer)
    }

    fun getByInstance(shinyProxyInstance: ShinyProxyInstance): List<T> {
        return getByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxyInstance), shinyProxyInstance.namespace)
    }

    fun getByShinyProxy(shinyProxy: ShinyProxy): List<T> {
        return getByLabels(LabelFactory.labelsForShinyProxy(shinyProxy.realmId), shinyProxy.namespace)
    }

    private fun getByLabels(labels: Map<String, String>, namespace: String): List<T> {
        val resources = arrayListOf<T>()
        for (configmap in lister.namespace(namespace).list()) {
            if (configmap?.metadata?.labels?.entries?.containsAll(labels.entries) == true) {
                resources.add(configmap)
            }
        }
        return resources
    }

    private suspend fun enqueueResource(trigger: String, resource: T) {
        val labels = resource.metadata.labels ?: return
        val realmId = labels.get(LabelFactory.REALM_ID_LABEL) ?: return
        val ownerReference = getShinyProxyOwnerRef(resource) ?: return
        val hashOfInstance = labels[LabelFactory.INSTANCE_LABEL]

        channel.send(ShinyProxyEvent(
                ShinyProxyEventType.RECONCILE,
                realmId,
                ownerReference.name,
                resource.metadata.namespace,
                hashOfInstance))
    }


    private fun getShinyProxyOwnerRef(resource: HasMetadata): OwnerReference? {
        val ownerReferences = resource.metadata.ownerReferences
        for (ownerReference in ownerReferences) {
            if (ownerReference.kind.lowercase() == "shinyproxy") {
                return ownerReference
            }
        }

        return null
    }

    fun stop() {
        if (::informer.isInitialized) {
            informer.stop()
        }
    }


}
