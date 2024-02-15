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
import io.fabric8.kubernetes.api.model.networking.v1.Ingress
import mu.KotlinLogging
import javax.json.JsonPatch
import javax.json.JsonStructure

class IngressPatcher {
    private val mapper = ObjectMapper(YAMLFactory())
    private val logger = KotlinLogging.logger { }

    init {
        mapper.registerModule(JSR353Module())
    }

    /**
     * Applies a JsonPatch to the given Ingress.
     */
    fun patch(ingress: Ingress, patch: JsonPatch?): Ingress {
        if (patch == null) {
            return ingress
        }

        logger.debug { "Original Ingress (before applying patches): ${mapper.writeValueAsString(ingress)}" }

        // 1. convert PodTemplate to javax.json.JsonValue object.
        // This conversion does not actually convert to a string, but some internal
        // representation of Jackson.
        val podAsJsonValue: JsonStructure = mapper.convertValue(ingress, JsonStructure::class.java)
        // 2. apply patch
        val patchedPodAsJsonValue: JsonStructure = patch.apply(podAsJsonValue)
        // 3. convert back to a PodTemplate
        val patchesIngress = mapper.convertValue(patchedPodAsJsonValue, Ingress::class.java)

        logger.debug { "Patched Ingress (after applying patches): ${mapper.writeValueAsString(patchesIngress)}" }

        return patchesIngress
    }

}
