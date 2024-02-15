/**
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
package eu.openanalytics.shinyproxyoperator.controller

import eu.openanalytics.shinyproxyoperator.components.IngressFactory
import eu.openanalytics.shinyproxyoperator.components.LabelFactory
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import io.fabric8.kubernetes.client.KubernetesClient
import mu.KotlinLogging

class IngressController(
    kubeClient: KubernetesClient
) {

    private val logger = KotlinLogging.logger {}
    private val ingressFactory = IngressFactory(kubeClient)

    fun reconcile(resourceRetriever: ResourceRetriever, shinyProxy: ShinyProxy) {
        reconcileLatestInstance(resourceRetriever, shinyProxy)
    }

    private fun reconcileLatestInstance(resourceRetriever: ResourceRetriever, shinyProxy: ShinyProxy) {
        val latestInstance = shinyProxy.status.latestInstance() ?: return

        val ingresses = resourceRetriever.getIngressByLabels(LabelFactory.labelsForShinyProxy(shinyProxy), shinyProxy.metadata.namespace)
        val mustBeUpdated = ingresses.isEmpty() || ingresses[0].metadata?.labels?.get(LabelFactory.LATEST_INSTANCE_LABEL) != latestInstance.hashOfSpec

        if (mustBeUpdated) {
            logger.debug { "${shinyProxy.logPrefix()} [Component/Ingress] Reconciling" }
            ingressFactory.create(shinyProxy, latestInstance)
        }
    }

}
