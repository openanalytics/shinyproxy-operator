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
package eu.openanalytics.shinyproxyoperator

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.convertValue
import java.math.BigInteger
import java.security.MessageDigest

private val objectMapper = ObjectMapper(YAMLFactory())

fun String.sha1(): String {
    val digest = MessageDigest.getInstance("SHA-1")
    digest.reset()
    digest.update(this.toByteArray(Charsets.UTF_8))
    return String.format("%040x", BigInteger(1, digest.digest()))
}

fun JsonNode.convertToYamlString(): String {
    objectMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
    val specAsObject = objectMapper.convertValue<Any>(this)
    return objectMapper.writeValueAsString(specAsObject)
}

fun JsonNode.getSubPath(): String {
    if (get("server")?.get("servlet")?.get("context-path")?.isTextual == true) {
        val path = get("server").get("servlet").get("context-path").textValue()
        if (path.last() != '/') {
            return "$path/"
        }
        return path
    }
    return "/"
}
