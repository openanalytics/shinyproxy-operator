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
package eu.openanalytics.shinyproxyoperator.crd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.datatype.jsr353.JSR353Module
import eu.openanalytics.shinyproxyoperator.sha1
import io.fabric8.kubernetes.api.model.Namespaced
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.Group
import io.fabric8.kubernetes.model.annotation.Version
import javax.json.JsonPatch

@Version("v1")
@Group("openanalytics.eu")
class ShinyProxy : CustomResource<JsonNode, ShinyProxyStatus>(), Namespaced {

    override fun initStatus(): ShinyProxyStatus {
        return ShinyProxyStatus()
    }


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

    @get:JsonIgnore
    val replicas: Int by lazy {
        if (spec.get("replicas")?.isInt == true) {
            val replicas = spec.get("replicas").intValue()
            return@lazy replicas
        }
        return@lazy 1
    }

    @get:JsonIgnore
    val namespacesOfCurrentInstance: List<String> by lazy {
        val namespaces = hashSetOf<String>()

        val kubernetesNamespace = spec.get("proxy")?.get("kubernetes")?.get("namespace")?.textValue()
        if (kubernetesNamespace != null) {
            namespaces.add(kubernetesNamespace)
        }

        namespaces.add(metadata.namespace)

        val appNamespaces = spec.get("appNamespaces")
        if (appNamespaces != null) {
            for (idx in 0 until appNamespaces.size()) {
                val namespace = appNamespaces.get(idx)?.textValue() ?: continue
                namespaces.add(namespace)
            }
        }

        return@lazy namespaces.toList()
    }

    @get:JsonIgnore
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

    @get:JsonIgnore
    val parsedIngressPatches: JsonPatch? by lazy {
        if (spec.get("kubernetesIngressPatches")?.isTextual == true) {
            try {
                // convert the raw YAML string into a JsonPatch
                val yamlReader = ObjectMapper(YAMLFactory())
                yamlReader.registerModule(JSR353Module())
                return@lazy yamlReader.readValue(spec.get("kubernetesIngressPatches").textValue(), JsonPatch::class.java)
            } catch (exception: Exception) {
                exception.printStackTrace() // log the exception for easier debugging
                throw exception
            }

        }
        return@lazy null
    }

    @get:JsonIgnore
    val parsedServicePatches: JsonPatch? by lazy {
        if (spec.get("kubernetesServicePatches")?.isTextual == true) {
            try {
                // convert the raw YAML string into a JsonPatch
                val yamlReader = ObjectMapper(YAMLFactory())
                yamlReader.registerModule(JSR353Module())
                return@lazy yamlReader.readValue(spec.get("kubernetesServicePatches").textValue(), JsonPatch::class.java)
            } catch (exception: Exception) {
                exception.printStackTrace() // log the exception for easier debugging
                throw exception
            }

        }
        return@lazy null
    }

    @get:JsonIgnore
    val subPath: String by lazy {
        if (spec.get("server")?.get("servlet")?.get("context-path")?.isTextual == true) {
            val path = spec.get("server").get("servlet").get("context-path").textValue()
            if (path.last() != '/') {
                return@lazy "$path/"
            }
            return@lazy path
        }

        return@lazy "/"
    }

    @get:JsonIgnore
    val hashOfCurrentSpec: String by lazy {
        return@lazy specAsYaml.sha1()
    }

    @get:JsonIgnore
    val realmId: String by lazy {
        return@lazy "${metadata.name}-${metadata.namespace}"
    }


    fun logPrefix(shinyProxyInstance: ShinyProxyInstance): String {
        return "[${metadata.namespace}/${metadata.name}/${shinyProxyInstance.hashOfSpec}]"
    }

    fun logPrefix(): String {
        return "[${metadata.namespace}/${metadata.name}/global]"
    }

}
