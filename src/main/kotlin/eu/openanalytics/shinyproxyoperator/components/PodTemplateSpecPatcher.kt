package eu.openanalytics.shinyproxyoperator.components

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.datatype.jsr353.JSR353Module
import io.fabric8.kubernetes.api.model.HTTPGetAction
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.PodTemplateSpec
import javax.json.JsonPatch
import javax.json.JsonStructure

class PodTemplateSpecPatcher {
    private val mapper = ObjectMapper(YAMLFactory())

    init {
        mapper.registerModule(JSR353Module())
    }

    /**
     * Applies a JsonPatch to the given Pod.
     */
    fun patch(pod: PodTemplateSpec, patch: JsonPatch?): PodTemplateSpec {
        if (patch == null) {
            return pod
        }
        // 1. convert PodTemplate to javax.json.JsonValue object.
        // This conversion does not actually convert to a string, but some internal
        // representation of Jackson.
        val podAsJsonValue: JsonStructure = mapper.convertValue(pod, JsonStructure::class.java)
        // 2. apply patch
        val patchedPodAsJsonValue: JsonStructure = patch.apply(podAsJsonValue)
        // 3. convert back to a PodTemplate
        val patchedPodTemplateSpec = mapper.convertValue(patchedPodAsJsonValue, PodTemplateSpec::class.java)

        for (container in patchedPodTemplateSpec.spec.containers) {
            patchHttpGet(container.livenessProbe.httpGet)
            patchHttpGet(container.readinessProbe.httpGet)
            patchHttpGet(container.startupProbe.httpGet)
        }

        return patchedPodTemplateSpec
    }

    private fun patchHttpGet(httpGet: HTTPGetAction) {
        if (httpGet.port.intVal == null) {
            val asInt = httpGet.port.strVal.toIntOrNull()
            if (asInt != null) {
                httpGet.port = IntOrString(asInt)
            }
        }
    }

}
