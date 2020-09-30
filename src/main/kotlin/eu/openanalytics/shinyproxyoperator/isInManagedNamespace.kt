package eu.openanalytics.shinyproxyoperator

import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import mu.KotlinLogging
import java.lang.IllegalStateException

val logger = KotlinLogging.logger {  }

fun isInManagedNamespace(shinyProxy: ShinyProxy): Boolean {
    Operator.operatorInstance.let {operator ->
        if (operator == null) {
            throw IllegalStateException("Cannot execute isInManagedNamespace when there is no global Operator object")
        }
        when (operator.mode) {
            Mode.CLUSTERED -> return true
            Mode.NAMESPACED -> {
                if (shinyProxy.metadata.namespace == operator.namespace) {
                    return true
                }
                logger.debug { "ShinyProxy ${shinyProxy.metadata.name} in namespace ${shinyProxy.metadata.namespace} isn't managed by this operator." }
                return false
            }
        }
    }
}
