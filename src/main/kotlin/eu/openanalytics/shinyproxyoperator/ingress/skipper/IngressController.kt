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

import eu.openanalytics.shinyproxyoperator.components.LabelFactory
import eu.openanalytics.shinyproxyoperator.components.LabelFactory.INGRESS_IS_LATEST
import eu.openanalytics.shinyproxyoperator.components.ResourceNameFactory
import eu.openanalytics.shinyproxyoperator.controller.ResourceRetriever
import eu.openanalytics.shinyproxyoperator.controller.ShinyProxyEvent
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance
import eu.openanalytics.shinyproxyoperator.ingress.IIngressController
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.networking.v1.Ingress
import io.fabric8.kubernetes.api.model.networking.v1.IngressList
import io.fabric8.kubernetes.client.NamespacedKubernetesClient
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.Resource
import io.fabric8.kubernetes.client.informers.cache.Indexer
import io.fabric8.kubernetes.client.informers.cache.Lister
import io.fabric8.kubernetes.client.readiness.Readiness
import kotlinx.coroutines.channels.SendChannel
import mu.KotlinLogging

class IngressController(
    channel: SendChannel<ShinyProxyEvent>,
    private val kubernetesClient: NamespacedKubernetesClient,
    ingressClient: MixedOperation<Ingress, IngressList, Resource<Ingress>>
) : IIngressController {

    private val logger = KotlinLogging.logger {}
    private val ingressFactory = IngressFactory(kubernetesClient)

    // Note: do not move this to the DiContainer since it is a Skipper-specific implementation
    private val ingressListener = IngressListener(channel, kubernetesClient, ingressClient)
    private val routeGroupClient = kubernetesClient.resources(RouteGroup::class.java)
    private val metadataIngressFactory = MetadataRouteGroupFactory(routeGroupClient)
    private val routeGroupListener = RouteGroupListener(this, routeGroupClient)

    fun start(shinyProxyLister: Lister<ShinyProxy>): Indexer<Ingress> {
        routeGroupListener.start(shinyProxyLister)
        return ingressListener.start(shinyProxyLister)
    }

    override fun reconcile(resourceRetriever: ResourceRetriever, shinyProxy: ShinyProxy) {
        var failed = false
        for (instance in shinyProxy.status.instances) {
            try {
                reconcileSingleInstance(resourceRetriever, shinyProxy, instance)
            } catch (e: Exception) {
                logger.warn(e) { "${shinyProxy.logPrefix(instance)} Unable to reconcile Ingress" }
                failed = true
            }
        }
        if (failed) {
            throw RuntimeException("One or more ingresses failed to reconcile")
        }
        reconcileMetadataEndpoint(shinyProxy,false)
    }

    override fun onRemoveInstance(resourceRetriever: ResourceRetriever, shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance) {
        for (ingress in resourceRetriever.getIngressByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance), shinyProxy.metadata.namespace)) {
            kubernetesClient.resource(ingress).delete()
        }
    }

    override fun stop() {
        ingressListener.stop()
    }

    private fun reconcileSingleInstance(resourceRetriever: ResourceRetriever, shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance) {
        val ingresses = resourceRetriever.getIngressByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance), shinyProxy.metadata.namespace)

        val mustBeUpdated = if (ingresses.size < 3) {
            true
        } else {
            // if the label indicating this is the latest is different from the actual state -> reconcile
            ingresses[0].metadata.labels[INGRESS_IS_LATEST]?.toBoolean() != shinyProxyInstance.isLatestInstance ||
                ingresses[1].metadata.labels[INGRESS_IS_LATEST]?.toBoolean() != shinyProxyInstance.isLatestInstance ||
                ingresses[2].metadata.labels[INGRESS_IS_LATEST]?.toBoolean() != shinyProxyInstance.isLatestInstance
        }

        if (mustBeUpdated) {
            logger.debug { "${shinyProxy.logPrefix(shinyProxyInstance)} [Component/Ingress] Reconciling" }
            val replicaSet = getReplicaSet(resourceRetriever, shinyProxy, shinyProxyInstance)
            if (replicaSet == null) {
                logger.warn { "${shinyProxy.logPrefix(shinyProxyInstance)} [Component/Ingress] Cannot reconcile Ingress since it has no ReplicaSet - probably this resource is being deleted" }
                return
            }
            if (!Readiness.getInstance().isReady(replicaSet)) {
                logger.warn { "${shinyProxy.logPrefix(shinyProxyInstance)} [Component/Ingress] Cannot reconcile Ingress since the corresponding ReplicaSet is not ready yet - it is probably being created" }
                return
            }
            // ReplicaSet exists and is ready -> time to create ingress
            // By only creating the ingress now, we ensure no 502 bad gateways are generated
            ingressFactory.createOrReplaceIngress(shinyProxy, shinyProxyInstance, replicaSet)
        }
    }

    private fun getReplicaSet(resourceRetriever: ResourceRetriever, shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): ReplicaSet? {
        val replicaSets = resourceRetriever.getReplicaSetByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance), shinyProxy.metadata.namespace)
        if (replicaSets.isEmpty()) {
            return null
        }
        return replicaSets[0]
    }

    override fun reconcileMetadataEndpoint(shinyProxy: ShinyProxy, force: Boolean) {
        val existingObject = routeGroupClient.inNamespace(shinyProxy.metadata.namespace).withName(ResourceNameFactory.createNameForMetadataIngress(shinyProxy)).get()
        if (existingObject == null || force) {
            metadataIngressFactory.createOrReplaceRouteGroup(shinyProxy)
        }
    }

}
