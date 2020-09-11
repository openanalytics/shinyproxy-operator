package eu.openanalytics.shinyproxyoperator.crd

import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.client.CustomResource


class ShinyProxy : CustomResource() {
    fun getSpec(): ShinyProxySpec {
        return spec
    }

    fun setSpec(spec: ShinyProxySpec) {
        this.spec = spec
    }

    fun getStatus(): ShinyProxyStatus {
        return status
    }

    fun setStatus(status: ShinyProxyStatus) {
        this.status = status
    }

    private lateinit var spec: ShinyProxySpec
    private lateinit var status: ShinyProxyStatus
    override fun getMetadata(): ObjectMeta {
        return super.getMetadata()
    }
}
