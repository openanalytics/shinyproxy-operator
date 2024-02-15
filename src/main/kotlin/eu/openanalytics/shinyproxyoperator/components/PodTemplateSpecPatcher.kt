/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021-2024 Open Analytics
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
package eu.openanalytics.shinyproxyoperator.components

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.datatype.jsr353.JSR353Module
import io.fabric8.kubernetes.api.model.HTTPGetAction
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.PodTemplateSpec
import mu.KotlinLogging
import javax.json.JsonPatch
import javax.json.JsonStructure

class PodTemplateSpecPatcher {
    private val mapper = ObjectMapper(YAMLFactory())
    private val logger = KotlinLogging.logger { }

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

        logger.debug { "Original PodTemplateSpec (before applying patches): ${mapper.writeValueAsString(pod)}" }

        // 1. convert PodTemplate to javax.json.JsonValue object.
        // This conversion does not actually convert to a string, but some internal
        // representation of Jackson.
        val podAsJsonValue: JsonStructure = mapper.convertValue(pod, JsonStructure::class.java)
        // 2. apply patch
        val patchedPodAsJsonValue: JsonStructure = patch.apply(podAsJsonValue)
        // 3. convert back to a PodTemplate
        val patchedPodTemplateSpec = mapper.convertValue(patchedPodAsJsonValue, PodTemplateSpec::class.java)

        for (container in patchedPodTemplateSpec.spec.containers) {
            container.livenessProbe?.httpGet?.let { patchHttpGet(it) }
            container.readinessProbe?.httpGet?.let { patchHttpGet(it) }
            container.startupProbe?.httpGet?.let { patchHttpGet(it) }
        }

        logger.debug { "Patched PodTemplateSpec (after applying patches): ${mapper.writeValueAsString(patchedPodTemplateSpec)}" }

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
