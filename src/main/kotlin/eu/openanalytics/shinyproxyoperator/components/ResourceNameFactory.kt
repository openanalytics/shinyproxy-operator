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
package eu.openanalytics.shinyproxyoperator.components

import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance
import mu.KotlinLogging

object ResourceNameFactory {

    private val logger = KotlinLogging.logger {}

    const val KUBE_RESOURCE_NAME_MAX_LENGTH = 63

    fun createNameForService(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): String {
        return "sp-${shinyProxy.metadata.name}-svc-${shinyProxyInstance.hashOfSpec}".take(KUBE_RESOURCE_NAME_MAX_LENGTH)
    }

    fun createNameForConfigMap(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): String {
        return "sp-${shinyProxy.metadata.name}-cm-${shinyProxyInstance.hashOfSpec}".take(KUBE_RESOURCE_NAME_MAX_LENGTH)
    }

    fun createNameForPod(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): String {
        return "sp-${shinyProxy.metadata.name}-pod-${shinyProxyInstance.hashOfSpec}".take(KUBE_RESOURCE_NAME_MAX_LENGTH)
    }

    fun createNameForReplicaSet(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): String {
        return "sp-${shinyProxy.metadata.name}-rs-${shinyProxyInstance.hashOfSpec}".take(KUBE_RESOURCE_NAME_MAX_LENGTH)
    }

    fun createNameForIngress(shinyProxy: ShinyProxy): String {
        return "sp-${shinyProxy.metadata.name}-ing".take(KUBE_RESOURCE_NAME_MAX_LENGTH)
    }

}
