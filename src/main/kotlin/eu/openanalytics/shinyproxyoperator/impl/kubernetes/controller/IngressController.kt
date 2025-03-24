/*
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021-2025 Open Analytics
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
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.components.IngressFactory
import eu.openanalytics.shinyproxyoperator.logPrefix
import eu.openanalytics.shinyproxyoperator.model.ShinyProxy
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance
import io.fabric8.kubernetes.api.model.networking.v1.Ingress
import io.fabric8.kubernetes.api.model.networking.v1.IngressList
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.Resource
import io.github.oshai.kotlinlogging.KotlinLogging

class IngressController(
    kubeClient: KubernetesClient,
    private val ingressListener: ResourceListener<Ingress, IngressList, Resource<Ingress>>
) {

    private val logger = KotlinLogging.logger {}
    private val ingressFactory = IngressFactory(kubeClient)

    fun reconcile(shinyProxy: ShinyProxy, latestShinyProxyInstance: ShinyProxyInstance, shinyProxyUid: String) {
        val ingresses = ingressListener.getByShinyProxy(shinyProxy)
        val mustBeUpdated = ingresses.isEmpty() || ingresses[0].metadata?.labels?.get(LabelFactory.LATEST_INSTANCE_LABEL) != latestShinyProxyInstance.hashOfSpec

        if (mustBeUpdated) {
            logger.debug { "${logPrefix(shinyProxy)} [Component/Ingress] Reconciling" }
            ingressFactory.create(shinyProxy, latestShinyProxyInstance, shinyProxyUid)
        }
    }

}
