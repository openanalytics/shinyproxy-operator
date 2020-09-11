package eu.openanalytics.shinyproxyoperator.crd

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.fabric8.kubernetes.api.model.KubernetesResource

@JsonDeserialize(using = JsonDeserializer.None::class)
class ShinyProxySpec : KubernetesResource {

    @JsonProperty("application.yml")
    lateinit var applicationYaml: String
}
