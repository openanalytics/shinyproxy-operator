package eu.openanalytics.shinyproxyoperator

import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyList
import eu.openanalytics.shinyproxyoperator.crd.DoneableShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.Resource
import mu.KotlinLogging

typealias ShinyProxyClient = MixedOperation<ShinyProxy, ShinyProxyList, DoneableShinyProxy, Resource<ShinyProxy, DoneableShinyProxy>>

suspend fun main() {
    val logger = KotlinLogging.logger {}
    try {
        val operator = Operator()
        operator.run()
    } catch (exception: KubernetesClientException) {
        logger.warn { "Kubernetes Client Exception : ${exception.message}" }
        exception.printStackTrace()
    }
}