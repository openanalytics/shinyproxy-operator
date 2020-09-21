/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2020 Open Analytics
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
import java.lang.IllegalStateException

object ResourceNameFactory {

    private val logger = KotlinLogging.logger {}

    fun createNameForService(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): String {
        if (shinyProxyInstance.hashOfSpec == null) {
            throw IllegalStateException("Cannot create name for ingress if hash of spec is unknown!")
        }
        return "sp-${shinyProxy.metadata.name}-svc-${shinyProxyInstance.hashOfSpec}".substring(0 until 63)
    }

    fun createNameForConfigMap(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): String {
        if (shinyProxyInstance.hashOfSpec == null) {
            throw IllegalStateException("Cannot create name for ingress if hash of spec is unknown!")
        }
        return "sp-${shinyProxy.metadata.name}-cm-${shinyProxyInstance}".substring(0 until 63)
    }

    fun createNameForPod(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): String {
        if (shinyProxyInstance.hashOfSpec == null) {
            throw IllegalStateException("Cannot create name for ingress if hash of spec is unknown!")
        }
        return "sp-${shinyProxy.metadata.name}-pod-${shinyProxyInstance}".substring(0 until 63)
    }

    fun createNameForReplicaSet(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): String {
        if (shinyProxyInstance.hashOfSpec == null) {
            throw IllegalStateException("Cannot create name for ingress if hash of spec is unknown!")
        }
        return "sp-${shinyProxy.metadata.name}-rs-${shinyProxyInstance.hashOfSpec}".substring(0 until 63)
    }

    fun createNameForIngress(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): String {
        if (shinyProxyInstance.hashOfSpec == null) {
            throw IllegalStateException("Cannot create name for ingress if hash of spec is unknown!")
        }
        return "sp-${shinyProxy.metadata.name}-ing-${shinyProxyInstance.hashOfSpec}".substring(0 until 63)
    }

}