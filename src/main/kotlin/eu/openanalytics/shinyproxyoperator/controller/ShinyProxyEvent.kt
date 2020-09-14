package eu.openanalytics.shinyproxyoperator.controller

import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance

data class ShinyProxyEvent(val eventType: ShinyProxyEventType, val shinyProxy: ShinyProxy?, val shinyProxyInstance: ShinyProxyInstance?)