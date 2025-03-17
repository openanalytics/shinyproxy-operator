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

import io.github.oshai.kotlinlogging.KotlinLogging

open class Config {

    protected val logger = KotlinLogging.logger {}

    open fun <T> readConfigValue(default: T?, envVarName: String, convertor: (String) -> T): T {
        val e = System.getenv(envVarName)
        val res = when {
            e != null -> convertor(e)
            default == null -> error("No value provided for required config option '${envVarName}'")
            else -> default
        }
        logger.info { "Using $res for property $envVarName" }
        return res
    }

}
