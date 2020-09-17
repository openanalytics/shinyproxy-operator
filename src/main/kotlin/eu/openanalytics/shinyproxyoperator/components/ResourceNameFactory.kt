package eu.openanalytics.shinyproxyoperator.components

import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import mu.KotlinLogging

object ResourceNameFactory {

    private val logger = KotlinLogging.logger {}

    fun createNameForService(shinyProxy: ShinyProxy): String {
        return "sp-${shinyProxy.metadata.name}-svc-${shinyProxy.calculateHashOfCurrentSpec()}".substring(0 until 63)
    }

    fun createNameForConfigMap(shinyProxy: ShinyProxy): String {
        return "sp-${shinyProxy.metadata.name}-cm-${shinyProxy.calculateHashOfCurrentSpec()}".substring(0 until 63)
    }

    fun createNameForPod(shinyProxy: ShinyProxy): String {
        return "sp-${shinyProxy.metadata.name}-pod-${shinyProxy.calculateHashOfCurrentSpec()}".substring(0 until 63)
    }

    fun createNameForReplicaSet(shinyProxy: ShinyProxy): String {
        return "sp-${shinyProxy.metadata.name}-rs-${shinyProxy.calculateHashOfCurrentSpec()}".substring(0 until 63)
    }

}