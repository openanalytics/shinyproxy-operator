package eu.openanalytics.shinyproxyoperator.crd

import com.fasterxml.jackson.databind.JsonNode
import io.fabric8.kubernetes.client.CustomResource

class ShinyProxy : CustomResource() {
    lateinit var spec: JsonNode
    lateinit var status: ShinyProxyStatus
}
