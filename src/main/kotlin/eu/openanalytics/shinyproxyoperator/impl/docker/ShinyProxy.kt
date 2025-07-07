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
package eu.openanalytics.shinyproxyoperator.impl.docker

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import eu.openanalytics.shinyproxyoperator.model.ShinyProxy
import java.nio.file.Path

fun ShinyProxy.getCaddyTlsCertFile(): Path? {
    if (getSpec().get("caddyTlsCertFile")?.isTextual == true) {
        return Path.of(getSpec().get("caddyTlsCertFile").textValue())
    }
    return null
}

fun ShinyProxy.getCaddyTlsKeyFile(): Path? {
    if (getSpec().get("caddyTlsKeyFile")?.isTextual == true) {
        return Path.of(getSpec().get("caddyTlsKeyFile").textValue())
    }
    return null
}

fun ShinyProxy.getCaddyRedirects(): List<CaddyRedirect> {
    if (getSpec().get("caddyRedirects")?.isArray == true) {
        return jacksonObjectMapper().convertValue(getSpec().get("caddyRedirects"))
    }
    return listOf()
}

data class CaddyRedirect(val from: String, val to: String, val statusCode: Int = 302)
