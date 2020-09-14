package eu.openanalytics.shinyproxyoperator.controller

import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy

data class ShinyProxyEvent(val eventType: ShinyProxyEventType, val shinyProxy: ShinyProxy)