/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2020 Open Analytics
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
        val hashOfSpec = shinyProxyInstance.hashOfSpec
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
    const val PROXIED_APP =  "openanalytics.eu/sp-proxied-app"
    const val INGRESS_IS_LATEST = "openanalytics.eu/ingress-is-latest"

}