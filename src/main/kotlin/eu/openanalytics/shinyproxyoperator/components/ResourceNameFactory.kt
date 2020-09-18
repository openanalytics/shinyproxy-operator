package eu.openanalytics.shinyproxyoperator.components

import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance
import mu.KotlinLogging
import java.lang.IllegalStateException

object ResourceNameFactory {

    private val logger = KotlinLogging.logger {}

    // TODO take shinyProxyInstance into account?
    fun createNameForService(shinyProxy: ShinyProxy): String {
        return "sp-${shinyProxy.metadata.name}-svc-${shinyProxy.hashOfCurrentSpec}".substring(0 until 63)
    }

    fun createNameForService(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): String {
        if (shinyProxyInstance.hashOfSpec == null) {
            throw IllegalStateException("Cannot create name for ingress if hash of spec is unknown!")
        }
        return "sp-${shinyProxy.metadata.name}-svc-${shinyProxyInstance.hashOfSpec}".substring(0 until 63)
    }

    fun createNameForConfigMap(shinyProxy: ShinyProxy): String {
        return "sp-${shinyProxy.metadata.name}-cm-${shinyProxy.hashOfCurrentSpec}".substring(0 until 63)
    }

    fun createNameForPod(shinyProxy: ShinyProxy): String {
        return "sp-${shinyProxy.metadata.name}-pod-${shinyProxy.hashOfCurrentSpec}".substring(0 until 63)
    }

    fun createNameForReplicaSet(shinyProxy: ShinyProxy): String {
        return "sp-${shinyProxy.metadata.name}-rs-${shinyProxy.hashOfCurrentSpec}".substring(0 until 63)
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