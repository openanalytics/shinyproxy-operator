package eu.openanalytics.shinyproxyoperator.components

import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance
import java.lang.RuntimeException

object LabelFactory {

    fun labelsForCurrentShinyProxyInstance(shinyProxy: ShinyProxy): Map<String, String> {
        return mapOf(
                APP_LABEL to APP_LABEL_VALUE,
                NAME_LABEL to shinyProxy.metadata.name,
                INSTANCE_LABEL to shinyProxy.hashOfCurrentSpec
        )
    }
    
    fun labelsForShinyProxyInstance(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): Map<String, String> {
        val hashOfSpec = shinyProxyInstance.hashOfSpec ?: throw RuntimeException("Cannot create label for ShinyProxyInstance without hash of spec")
        return mapOf(
                APP_LABEL to APP_LABEL_VALUE,
                NAME_LABEL to shinyProxy.metadata.name,
                INSTANCE_LABEL to hashOfSpec
        )
    }

    const val APP_LABEL = "app"
    const val APP_LABEL_VALUE = "shinyproxy"
    const val NAME_LABEL = "openanalytics.eu/sp-resource-name"
    const val INSTANCE_LABEL = "openanalytics.eu/sp-instance"
    const val PROXIED_APP =  "openanalytics.eu/containerproxy-proxied-app"

}