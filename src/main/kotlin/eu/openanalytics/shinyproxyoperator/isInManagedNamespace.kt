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
package eu.openanalytics.shinyproxyoperator

import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import mu.KotlinLogging
import java.lang.IllegalStateException

val logger = KotlinLogging.logger {  }

fun isInManagedNamespace(shinyProxy: ShinyProxy): Boolean {
    when (Operator.getOperatorInstance().mode) {
        Mode.CLUSTERED -> return true
        Mode.NAMESPACED -> {
            if (shinyProxy.metadata.namespace == Operator.getOperatorInstance().namespace) {
                return true
            }
            logger.debug { "ShinyProxy ${shinyProxy.metadata.name} in namespace ${shinyProxy.metadata.namespace} isn't managed by this operator." }
            return false
        }
    }
}
