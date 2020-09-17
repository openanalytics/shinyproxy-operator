package eu.openanalytics.shinyproxyoperator.crd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.datatype.jsr353.JSR353Module
import io.fabric8.kubernetes.client.CustomResource
import java.math.BigInteger
import java.security.MessageDigest
import javax.json.JsonPatch


class ShinyProxy : CustomResource() {
    lateinit var spec: JsonNode
    var kubernetesPodTemplateSpecPatches: String? = null
    val status = ShinyProxyStatus()

    val specAsYaml: String by lazy {
        val objectMapper = ObjectMapper(YAMLFactory())
        objectMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        objectMapper.writeValueAsString(spec)
    }

    @get:JsonIgnore
    val parsedKubernetesPodTemplateSpecPatches: JsonPatch? by lazy {
        try {
            // convert the raw YAML string into a JsonPatch
            val yamlReader = ObjectMapper(YAMLFactory())
            yamlReader.registerModule(JSR353Module())
            return@lazy yamlReader.readValue(kubernetesPodTemplateSpecPatches, JsonPatch::class.java)
        } catch (exception: Exception) {
            exception.printStackTrace() // log the exception for easier debugging
            throw exception
        }
    }

    // TODO lazy
    fun calculateHashOfCurrentSpec(): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.reset()
        digest.update(specAsYaml.toByteArray(Charsets.UTF_8))
        return String.format("%040x", BigInteger(1, digest.digest()))
    }
}
