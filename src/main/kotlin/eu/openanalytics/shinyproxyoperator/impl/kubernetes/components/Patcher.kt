/*
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
package eu.openanalytics.shinyproxyoperator.impl.kubernetes.components

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.datatype.jsr353.JSR353Module
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.IntOrStringDeserializer
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.PodTemplateSpec
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.networking.v1.Ingress
import io.github.oshai.kotlinlogging.KotlinLogging
import javax.json.JsonPatch
import javax.json.JsonStructure


class Patcher {
    private val mapper = ObjectMapper(YAMLFactory())
    private val logger = KotlinLogging.logger { }

    init {
        mapper.registerModule(JSR353Module())
        val module = SimpleModule()
        module.addDeserializer(IntOrString::class.java, IntOrStringDeserializer())
        mapper.registerModule(module)
    }

    /**
     * Applies a JsonPatch to the given Service.
     */
    fun patch(service: Service, patch: JsonPatch?): Service {
        if (patch == null) {
            return service
        }

        logger.debug { "Original Service (before applying patches): ${mapper.writeValueAsString(service)}" }

        // 1. convert Service to javax.json.JsonValue object.
        // This conversion does not actually convert to a string, but some internal
        // representation of Jackson.
        val podAsJsonValue: JsonStructure = mapper.convertValue(service, JsonStructure::class.java)
        // 2. apply patch
        val patchedAsJsonValue: JsonStructure = patch.apply(podAsJsonValue)
        // 3. convert back to a Service
        val patchedService = mapper.convertValue(patchedAsJsonValue, Service::class.java)

        logger.debug { "Patched Service (after applying patches): ${mapper.writeValueAsString(patchedService)}" }

        return patchedService
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
        val patchedAsJsonValue: JsonStructure = patch.apply(podAsJsonValue)
        // 3. convert back to a PodTemplate
        val patchedIngress = mapper.convertValue(patchedAsJsonValue, Ingress::class.java)

        logger.debug { "Patched Ingress (after applying patches): ${mapper.writeValueAsString(patchedIngress)}" }

        return patchedIngress
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

        logger.debug { "Patched PodTemplateSpec (after applying patches): ${mapper.writeValueAsString(patchedPodTemplateSpec)}" }

        return patchedPodTemplateSpec
    }

}
