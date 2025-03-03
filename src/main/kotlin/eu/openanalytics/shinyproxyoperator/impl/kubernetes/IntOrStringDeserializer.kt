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

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import io.fabric8.kubernetes.api.model.IntOrString

class IntOrStringDeserializer : StdDeserializer<IntOrString>(IntOrString::class.java) {

    override fun deserialize(jsonParser: JsonParser, deserializationContext: DeserializationContext): IntOrString {
        val node = jsonParser.codec.readTree<JsonNode>(jsonParser)
        val value = node.asText()

        val asInt = value?.toIntOrNull()
        if (asInt != null) {
            return IntOrString(asInt)
        }

        return IntOrString(value)
    }

}
