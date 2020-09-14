package eu.openanalytics.shinyproxyoperator.crd

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.fabric8.kubernetes.client.CustomResource
import java.math.BigInteger
import java.security.MessageDigest

class ShinyProxy : CustomResource() {
    lateinit var spec: JsonNode
    val status = ShinyProxyStatus()

    val specAsYaml: String by lazy {
        val objectMapper = ObjectMapper(YAMLFactory())
        objectMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        objectMapper.writeValueAsString(spec)
    }

    fun calculateHashOfCurrentSpec(): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.reset()
        digest.update(specAsYaml.toByteArray(Charsets.UTF_8))
        return String.format("%040x", BigInteger(1, digest.digest()))
    }
}
