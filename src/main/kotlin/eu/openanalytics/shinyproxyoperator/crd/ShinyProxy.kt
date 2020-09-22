/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2020 Open Analytics
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
package eu.openanalytics.shinyproxyoperator.crd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.datatype.jsr353.JSR353Module
import eu.openanalytics.shinyproxyoperator.sha1
import io.fabric8.kubernetes.client.CustomResource
import java.lang.IllegalStateException
import javax.json.JsonPatch


class ShinyProxy : CustomResource() {
    lateinit var spec: JsonNode

    @get:JsonIgnore
    val image: String by lazy {
        if (spec.get("image")?.isTextual == true) {
            return@lazy spec.get("image").textValue()
        }
        return@lazy "openanalytics/shinyproxy:latest"
    }

    @get:JsonIgnore
    val imagePullPolicy: String by lazy {
        if (spec.get("imagePullPolicy")?.isTextual == true) {
            return@lazy spec.get("imagePullPolicy").textValue()
        }
        return@lazy "Always"
    }

    @get:JsonIgnore
    val fqdn: String by lazy {
        if (spec.get("fqdn")?.isTextual == true) {
            return@lazy spec.get("fqdn").textValue()
        }
        throw IllegalStateException("Cannot create ShinyProxy instance when no FQDN is specified!")
    }

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
