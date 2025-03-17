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
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.components.ServiceFactory
import eu.openanalytics.shinyproxyoperator.logPrefix
import eu.openanalytics.shinyproxyoperator.model.ShinyProxy
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.ServiceList
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.apps.ReplicaSetList
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.RollableScalableResource
import io.fabric8.kubernetes.client.dsl.ServiceResource
import io.fabric8.kubernetes.client.readiness.Readiness
import io.github.oshai.kotlinlogging.KotlinLogging

class ServiceController(
    serviceClient: MixedOperation<Service, ServiceList, ServiceResource<Service>>,
    private val serviceListener: ResourceListener<Service, ServiceList, ServiceResource<Service>>,
    private val replicaSetListener: ResourceListener<ReplicaSet, ReplicaSetList, RollableScalableResource<ReplicaSet>>
) {

    private val logger = KotlinLogging.logger {}
    private val serviceFactory = ServiceFactory(serviceClient)

    fun reconcile(shinyProxy: ShinyProxy, latestInstance: ShinyProxyInstance, shinyProxyUid: String) {
        val services = serviceListener.getByShinyProxy(shinyProxy)
        val mustBeUpdated = services.isEmpty()
        || services[0].metadata?.labels?.get(LabelFactory.LATEST_INSTANCE_LABEL) != latestInstance.hashOfSpec
        || services[0].metadata?.labels?.get(LabelFactory.REVISION_LABEL) != latestInstance.revision.toString()

        if (mustBeUpdated) {
            val replicaSet = getReplicaSet(latestInstance) ?: return
            if (!Readiness.getInstance().isReady(replicaSet)) {
                return
            }
            // ReplicaSet exists and is ready -> time to create ingress
            // By only creating the ingress now, we ensure no 502 bad gateways are generated
            logger.debug { "${logPrefix(latestInstance)} [Component/Service] Reconciling" }
            serviceFactory.create(shinyProxy, latestInstance, shinyProxyUid)
        }
    }

    private fun getReplicaSet(shinyProxyInstance: ShinyProxyInstance): ReplicaSet? {
        val replicaSets = replicaSetListener.getByInstance(shinyProxyInstance)
        if (replicaSets.isEmpty()) {
            return null
        }
        return replicaSets[0]
    }

}
