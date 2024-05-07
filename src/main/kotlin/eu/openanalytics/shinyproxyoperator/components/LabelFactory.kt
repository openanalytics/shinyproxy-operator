/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021-2024 Open Analytics
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

object LabelFactory {

    fun labelsForShinyProxyInstance(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): Map<String, String> {
        val hashOfSpec = shinyProxyInstance.hashOfSpec
        val labels = hashMapOf(
            APP_LABEL to APP_LABEL_VALUE,
            REALM_ID_LABEL to shinyProxy.realmId,
            INSTANCE_LABEL to hashOfSpec,
        )
        if (shinyProxyInstance.revision != null) {
            // only match on revision label, if a revision is set, ensure backwards compatibility
            labels[REVISION_LABEL] = shinyProxyInstance.revision.toString()
        }
        return labels
    }

    fun labelsForShinyProxy(shinyProxy: ShinyProxy): Map<String, String> {
        return mapOf(
            APP_LABEL to APP_LABEL_VALUE,
            REALM_ID_LABEL to shinyProxy.realmId
        )
    }

    const val APP_LABEL = "app"
    const val APP_LABEL_VALUE = "shinyproxy"
    const val REALM_ID_LABEL = "openanalytics.eu/sp-realm-id"
    const val INSTANCE_LABEL = "openanalytics.eu/sp-instance"
    const val REVISION_LABEL = "openanalytics.eu/sp-instance-revision"
    const val LATEST_INSTANCE_LABEL = "openanalytics.eu/sp-latest-instance"
    const val PROXIED_APP = "openanalytics.eu/sp-proxied-app"

}
