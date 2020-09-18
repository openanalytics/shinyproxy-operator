package eu.openanalytics.shinyproxyoperator.ingres

import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance

interface IIngressController {

    fun onNewInstance(shinyProxy: ShinyProxy, newInstance: ShinyProxyInstance)

    fun reconcileInstance(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance)

}