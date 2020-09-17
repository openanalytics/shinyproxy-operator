package eu.openanalytics.shinyproxyoperator.components


/**
 * ContainerProxy
 *
 * Copyright (C) 2016-2020 Open Analytics
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
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr353.JSR353Module
import io.fabric8.kubernetes.api.model.PodTemplate
import io.fabric8.kubernetes.api.model.PodTemplateSpec
import javax.json.JsonPatch
import javax.json.JsonStructure


class PodTemplateSpecPatcher {
    private val mapper = ObjectMapper()

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
        return mapper.convertValue(patchedPodAsJsonValue, PodTemplateSpec::class.java)
    }

//    /**
//     * Applies a JsonPatch to the given Pod. When proxy.kubernetes.debug-patches is
//     * enabled the original and patched specification will be logged as YAML.
//     */
//    @Throws(JsonProcessingException::class)
//    fun patchWithDebug(pod: Pod, patch: JsonPatch?): Pod {
//        if (loggingEnabled) {
//            log.info("Original Pod: " + SerializationUtils.dumpAsYaml(pod))
//        }
//        val patchedPod = patch(pod, patch)
//        if (loggingEnabled) {
//            log.info("Patched Pod: " + SerializationUtils.dumpAsYaml(patchedPod))
//        }
//        return patchedPod
//    }
//
//    companion object {
//        private const val DEBUG_PROPERTY = "proxy.kubernetes.debug-patches"
//    }
}
