package eu.openanalytics.shinyproxyoperator.crd

import io.fabric8.kubernetes.client.CustomResource

class ShinyProxy : CustomResource() {
    lateinit var spec: ShinyProxySpec
    lateinit var status: ShinyProxyStatus
}
