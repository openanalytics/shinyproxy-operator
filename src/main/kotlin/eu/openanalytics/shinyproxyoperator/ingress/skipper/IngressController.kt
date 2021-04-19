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
package eu.openanalytics.shinyproxyoperator.ingress.skipper

import eu.openanalytics.shinyproxyoperator.components.LabelFactory
import eu.openanalytics.shinyproxyoperator.components.LabelFactory.INGRESS_IS_LATEST
import eu.openanalytics.shinyproxyoperator.controller.ResourceRetriever
import eu.openanalytics.shinyproxyoperator.controller.ShinyProxyEvent
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance
import eu.openanalytics.shinyproxyoperator.ingres.IIngressController
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.networking.v1beta1.Ingress
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.fabric8.kubernetes.client.informers.cache.Lister
import io.fabric8.kubernetes.client.internal.readiness.Readiness
import kotlinx.coroutines.channels.Channel
import mu.KotlinLogging

class IngressController(
        channel: Channel<ShinyProxyEvent>,
        ingressInformer: SharedIndexInformer<Ingress>,
        shinyProxyListener: Lister<ShinyProxy>,
        kubernetesClient: KubernetesClient,
        private val resourceRetriever: ResourceRetriever
) : IIngressController {

    private val logger = KotlinLogging.logger {}
    private val ingressFactory = IngressFactory(kubernetesClient)

    // Note: do not move this to the DiContainer since it is a Skipper-specific implementation
    private val ingressListener = IngressListener(channel, kubernetesClient, ingressInformer, shinyProxyListener)

    override fun reconcile(shinyProxy: ShinyProxy) {
        for (instance in shinyProxy.status.instances) {
            try {
                reconcileSingleInstance(shinyProxy, instance)
            } catch (e: Exception) {
                logger.warn(e) { "${shinyProxy.logPrefix(instance)} Unable to reconcile Ingress" }
            }
        }
    }

    private fun reconcileSingleInstance(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance) {
        val ingresses = resourceRetriever.getIngressByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance), shinyProxy.metadata.namespace)

        val mustBeUpdated = if (ingresses.isEmpty()) {
            true
        } else {
            // if the label indicating this is the latest is different from the actual state -> reconcile
            ingresses[0].metadata.labels[INGRESS_IS_LATEST]?.toBoolean() != shinyProxyInstance.isLatestInstance
        }

        if (mustBeUpdated) {
            logger.debug { "${shinyProxy.logPrefix(shinyProxyInstance)} Reconciling ingress" }
            val replicaSet = getReplicaSet(shinyProxy, shinyProxyInstance)
            if (replicaSet == null) {
                logger.warn { "${shinyProxy.logPrefix(shinyProxyInstance)} Cannot reconcile Ingress since it has no ReplicaSet - probably this resource is being deleted." }
                return
            }
            if (!Readiness.isReady(replicaSet)) {
                logger.warn { "${shinyProxy.logPrefix(shinyProxyInstance)} Cannot reconcile Ingress since the corresponding ReplicaSet is not ready yet - it is probably being created." }
                return
            }
            // ReplicaSet exists and is ready -> time to create ingress
            // By only creating the ingress now, we ensure no 502 bad gateways are generated
            ingressFactory.createOrReplaceIngress(shinyProxy, shinyProxyInstance, replicaSet)
        }
    }

    private fun getReplicaSet(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): ReplicaSet? {
        val replicaSets = resourceRetriever.getReplicaSetByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance), shinyProxy.metadata.namespace)
        if (replicaSets.isEmpty()) {
            return null
        }
        return replicaSets[0]
    }

}