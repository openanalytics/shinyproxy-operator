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
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance
import eu.openanalytics.shinyproxyoperator.components.IngressFactory
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.networking.v1.Ingress
import io.fabric8.kubernetes.api.model.networking.v1.IngressList
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.Resource
import io.fabric8.kubernetes.client.readiness.Readiness
import mu.KotlinLogging

class IngressController(
    ingressClient: MixedOperation<Ingress, IngressList, Resource<Ingress>>
) {

    private val logger = KotlinLogging.logger {}
    private val ingressFactory = IngressFactory(ingressClient)

    fun reconcile(resourceRetriever: ResourceRetriever, shinyProxy: ShinyProxy) {
        reconcileLatestInstance(resourceRetriever, shinyProxy)
    }

    fun onRemoveInstance(resourceRetriever: ResourceRetriever, shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance) {
        reconcileLatestInstance(resourceRetriever, shinyProxy)
    }

    private fun reconcileLatestInstance(resourceRetriever: ResourceRetriever, shinyProxy: ShinyProxy) {
        val latestInstance = shinyProxy.status.latestInstance() ?: return

        val ingresses = resourceRetriever.getIngressByLabels(LabelFactory.labelsForShinyProxy(shinyProxy), shinyProxy.metadata.namespace)
        val mustBeUpdated = ingresses.isEmpty()
            || ingresses[0].metadata?.labels?.get(LabelFactory.LATEST_INSTANCE_LABEL) != latestInstance.hashOfSpec
            || ingresses[0].spec?.rules?.get(0)?.http?.paths?.size != shinyProxy.status.instances.size + 1

        if (mustBeUpdated) {
            val replicaSet = getReplicaSet(resourceRetriever, shinyProxy, latestInstance)
            if (replicaSet == null) {
                logger.warn { "${shinyProxy.logPrefix(latestInstance)} [Component/Ingress] Cannot reconcile Ingress since it has no ReplicaSet - probably this resource is being deleted" }
                return
            }
            if (!Readiness.getInstance().isReady(replicaSet)) {
                logger.warn { "${shinyProxy.logPrefix(latestInstance)} [Component/Ingress] Cannot reconcile Ingress since the corresponding ReplicaSet is not ready yet - it is probably being created" }
                return
            }
            // ReplicaSet exists and is ready -> time to create ingress
            // By only creating the ingress now, we ensure no 502 bad gateways are generated
            logger.debug { "${shinyProxy.logPrefix(latestInstance)} [Component/Ingress] Reconciling" }
            ingressFactory.createOrReplaceIngress(shinyProxy, latestInstance)
        }
    }

    private fun getReplicaSet(resourceRetriever: ResourceRetriever, shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): ReplicaSet? {
        val replicaSets = resourceRetriever.getReplicaSetByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance), shinyProxy.metadata.namespace)
        if (replicaSets.isEmpty()) {
            return null
        }
        return replicaSets[0]
    }

}
