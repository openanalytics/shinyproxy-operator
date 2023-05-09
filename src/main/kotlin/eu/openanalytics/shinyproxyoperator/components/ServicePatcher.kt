/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021-2023 Open Analytics
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
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.ServicePort
import mu.KotlinLogging
import javax.json.JsonPatch
import javax.json.JsonStructure

class ServicePatcher {
    private val mapper = ObjectMapper(YAMLFactory())
    private val logger = KotlinLogging.logger { }

    init {
        mapper.registerModule(JSR353Module())
    }

    /**
     * Applies a JsonPatch to the given Ingress.
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
        val patchedPodAsJsonValue: JsonStructure = patch.apply(podAsJsonValue)
        // 3. convert back to a Service
        val patchedService = mapper.convertValue(patchedPodAsJsonValue, Service::class.java)

        for (port in patchedService.spec.ports) {
            port.setTargetPort(IntOrString(port.targetPort.strVal.toIntOrNull()))
        }

        logger.debug { "Patched Service (after applying patches): ${mapper.writeValueAsString(patchedService)}" }

        return patchedService
    }

}
