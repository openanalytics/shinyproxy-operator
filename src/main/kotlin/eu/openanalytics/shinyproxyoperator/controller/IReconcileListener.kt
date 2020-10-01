package eu.openanalytics.shinyproxyoperator.controller

import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance

interface IReconcileListener  {

    fun onInstanceFullyReconciled(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance)

}
