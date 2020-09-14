package eu.openanalytics.shinyproxyoperator.components

import eu.openanalytics.shinyproxyoperator.controller.ShinyProxyController
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance

object LabelFactory {

    fun labelsForCurrentShinyProxyInstance(shinyProxy: ShinyProxy): Map<String, String> {
        return mapOf(
                APP_LABEL to APP_LABEL_VALUE,
                NAME_LABEL to shinyProxy.metadata.name,
                INSTANCE_LABEL to shinyProxy.calculateHashOfCurrentSpec()
        )
    }
    
    fun labelsForShinyProxyInstance(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): Map<String, String> {
        val hashOfSpec: String = shinyProxyInstance.hashOfSpec.let {
            it ?: TODO("Should not happend")
        }
        return mapOf(
                APP_LABEL to APP_LABEL_VALUE,
                NAME_LABEL to shinyProxy.metadata.name,
                INSTANCE_LABEL to hashOfSpec
        )
    }

    const val APP_LABEL = "app"
    const val APP_LABEL_VALUE = "shinyproxy"
    const val NAME_LABEL = "sp-name"
    const val INSTANCE_LABEL = "sp-instance"

}