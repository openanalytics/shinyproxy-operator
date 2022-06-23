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
package eu.openanalytics.shinyproxyoperator

import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import io.fabric8.kubernetes.api.model.KubernetesResourceList
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.Resource
import mu.KotlinLogging
import kotlin.system.exitProcess

typealias ShinyProxyClient = MixedOperation<ShinyProxy, KubernetesResourceList<ShinyProxy>, Resource<ShinyProxy>>

suspend fun main() {
    val logger = KotlinLogging.logger {}
    try {
        val operator = Operator()
        Operator.setOperatorInstance(operator)
        val (resourceRetriever, shinyProxyLister) = operator.prepare()
        operator.run(resourceRetriever, shinyProxyLister)
    } catch (exception: KubernetesClientException) {
        logger.warn { "Kubernetes Client Exception : ${exception.message}" }
        exception.printStackTrace()
        exitProcess(1)
    }
}
