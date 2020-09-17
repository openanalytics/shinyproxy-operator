package eu.openanalytics.shinyproxyoperator.crd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.datatype.jsr353.JSR353Module
import eu.openanalytics.shinyproxyoperator.sha1
import io.fabric8.kubernetes.client.CustomResource
import javax.json.JsonPatch


class ShinyProxy : CustomResource() {
    lateinit var spec: JsonNode

    val status = ShinyProxyStatus()

    val specAsYaml: String by lazy {
        val objectMapper = ObjectMapper(YAMLFactory())
        objectMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        objectMapper.writeValueAsString(spec)
    }

    @get:JsonIgnore
    val parsedKubernetesPodTemplateSpecPatches: JsonPatch? by lazy {
        if (spec.get("kubernetesPodTemplateSpecPatches")?.isTextual == true) {
            try {
                // convert the raw YAML string into a JsonPatch
                val yamlReader = ObjectMapper(YAMLFactory())
                yamlReader.registerModule(JSR353Module())
                return@lazy yamlReader.readValue(spec.get("kubernetesPodTemplateSpecPatches").textValue(), JsonPatch::class.java)
            } catch (exception: Exception) {
                exception.printStackTrace() // log the exception for easier debugging
                throw exception
            }

        }
        return@lazy null
    }

    val hashOfCurrentSpec: String by lazy {
        return@lazy specAsYaml.sha1()
    }

}
