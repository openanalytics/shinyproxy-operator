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
package eu.openanalytics.shinyproxyoperator.impl.kubernetes

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.datatype.jsr353.JSR353Module
import eu.openanalytics.shinyproxyoperator.model.ShinyProxy
import io.github.oshai.kotlinlogging.KotlinLogging
import javax.json.JsonPatch

val logger = KotlinLogging.logger {}

fun ShinyProxy.getParsedKubernetesPodTemplateSpecPatches(): JsonPatch? {
    if (getSpec().get("kubernetesPodTemplateSpecPatches")?.isTextual == true) {
        try {
            // convert the raw YAML string into a JsonPatch
            val yamlReader = ObjectMapper(YAMLFactory())
            yamlReader.registerModule(JSR353Module())
            return yamlReader.readValue(getSpec().get("kubernetesPodTemplateSpecPatches").textValue(), JsonPatch::class.java)
        } catch (exception: Exception) {
            logger.warn(exception) { "Error while parsing 'kubernetesPodTemplateSpecPatches" }
            throw RuntimeException("Error while parsing 'kubernetesPodTemplateSpecPatches': " + exception.javaClass.simpleName + ": " + exception.message)
        }
    }
    return null
}

fun ShinyProxy.getAntiAffinityRequired(): Boolean {
    if (getSpec().get("antiAffinityRequired")?.isBoolean == true) {
        return getSpec().get("antiAffinityRequired").booleanValue()
    }
    return false
}

fun ShinyProxy.getAntiAffinityTopologyKey(): String {
    if (getSpec().get("antiAffinityTopologyKey")?.isTextual == true) {
        return getSpec().get("antiAffinityTopologyKey").textValue()
    }
    return "kubernetes.io/hostname"
}

fun ShinyProxy.getImagePullPolicy(): String {
    if (getSpec().get("imagePullPolicy")?.isTextual == true) {
        return getSpec().get("imagePullPolicy").textValue()
    }
    return "Always"
}

fun ShinyProxy.getParsedServicePatches(): JsonPatch? {
    if (getSpec().get("kubernetesServicePatches")?.isTextual == true) {
        try {
            // convert the raw YAML string into a JsonPatch
            val yamlReader = ObjectMapper(YAMLFactory())
            yamlReader.registerModule(JSR353Module())
            return yamlReader.readValue(getSpec().get("kubernetesServicePatches").textValue(), JsonPatch::class.java)
        } catch (exception: Exception) {
            logger.warn(exception) { "Error while parsing 'kubernetesServicePatches" }
            throw RuntimeException("Error while parsing 'kubernetesServicePatches': " + exception.javaClass.simpleName + ": " + exception.message)
        }
    }
    return null
}


fun ShinyProxy.getParsedIngressPatches(): JsonPatch? {
    if (getSpec().get("kubernetesIngressPatches")?.isTextual == true) {
        try {
            // convert the raw YAML string into a JsonPatch
            val yamlReader = ObjectMapper(YAMLFactory())
            yamlReader.registerModule(JSR353Module())
            return yamlReader.readValue(getSpec().get("kubernetesIngressPatches").textValue(), JsonPatch::class.java)
        } catch (exception: Exception) {
            logger.warn(exception) { "Error while parsing 'kubernetesIngressPatches" }
            throw RuntimeException("Error while parsing 'kubernetesServicePatches': " + exception.javaClass.simpleName + ": " + exception.message)
        }
    }
    return null
}
