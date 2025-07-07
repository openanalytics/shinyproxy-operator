/*
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021-2025 Open Analytics
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
package eu.openanalytics.shinyproxyoperator.model

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import eu.openanalytics.shinyproxyoperator.convertToYamlString
import eu.openanalytics.shinyproxyoperator.getSubPath
import eu.openanalytics.shinyproxyoperator.getTextValueOrNull
import eu.openanalytics.shinyproxyoperator.sha1

class ShinyProxy(private val spec: JsonNode, val name: String, val namespace: String) {

    @get:JsonIgnore
    val image: String by lazy {
        if (spec.get("image")?.isTextual == true) {
            return@lazy spec.get("image").textValue()
        }
        return@lazy "openanalytics/shinyproxy:latest"
    }

    @get:JsonIgnore
    val imagePullPolicy: String by lazy {
        if (getSpec().get("imagePullPolicy")?.isTextual == true) {
            return@lazy getSpec().get("imagePullPolicy").textValue()
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

    @get:JsonIgnore
    val additionalFqdns: List<String> by lazy {
        if (spec.get("additionalFqdns")?.isArray == true) {
            return@lazy spec.get("additionalFqdns").elements().asSequence().map { it.textValue() }.toList()
        }
        if (spec.get("additional-fqdns")?.isArray == true) {
            return@lazy spec.get("additional-fqdns").elements().asSequence().map { it.textValue() }.toList()
        }
        return@lazy listOf()
    }

    @get:JsonIgnore
    val allFqdns: List<String> by lazy {
        return@lazy listOf(fqdn) + additionalFqdns
    }

    @get:JsonIgnore
    val replicas: Int by lazy {
        if (spec.get("replicas")?.isInt == true) {
            val replicas = spec.get("replicas").intValue()
            return@lazy replicas
        }
        return@lazy 1
    }

    @get:JsonIgnore
    val specAsYaml: String by lazy {
        return@lazy spec.convertToYamlString()
    }

    @get:JsonIgnore
    val subPath: String by lazy {
        return@lazy spec.getSubPath()
    }

    @get:JsonIgnore
    val hashOfCurrentSpec: String by lazy {
        return@lazy specAsYaml.sha1()
    }

    @get:JsonIgnore
    val realmId: String by lazy {
        return@lazy "${namespace}-${name}"
    }

    @get:JsonIgnore
    val labels: Map<String, String> by lazy {
        if (getSpec().get("labels")?.isObject == true) {
            return@lazy jacksonObjectMapper().convertValue(getSpec().get("labels"))
        }
        return@lazy mapOf()
    }

    @get:JsonIgnore
    val memoryRequest: String? by lazy {
        return@lazy spec.getTextValueOrNull("memory-request") ?: spec.getTextValueOrNull("memoryRequest")
    }

    @get:JsonIgnore
    val memoryLimit: String? by lazy {
        return@lazy spec.getTextValueOrNull("memory-limit") ?: spec.getTextValueOrNull("memoryLimit")
    }

    @get:JsonIgnore
    val cpuRequest: String? by lazy {
        return@lazy spec.getTextValueOrNull("cpu-request") ?: spec.getTextValueOrNull("cpuRequest")
    }

    @get:JsonIgnore
    val cpuLimit: String? by lazy {
        return@lazy spec.getTextValueOrNull("cpu-limit") ?: spec.getTextValueOrNull("cpuLimit")
    }

    @get:JsonIgnore
    val dns: List<String> by lazy {
        if (spec.get("dns")?.isArray == true) {
            return@lazy spec.get("dns").elements().asSequence().map { it.textValue() }.toList()
        }
        return@lazy listOf()
    }

    fun getSpec(): JsonNode {
        return spec
    }

}
