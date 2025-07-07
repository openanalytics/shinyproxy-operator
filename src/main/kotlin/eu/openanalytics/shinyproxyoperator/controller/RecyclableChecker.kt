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
package eu.openanalytics.shinyproxyoperator.controller

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import eu.openanalytics.shinyproxyoperator.IOrchestrator
import eu.openanalytics.shinyproxyoperator.IRecyclableChecker
import eu.openanalytics.shinyproxyoperator.logPrefix
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit


class RecyclableChecker(private val orchestrator: IOrchestrator) : IRecyclableChecker {

    private val logger = KotlinLogging.logger {}
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val objectMapper = ObjectMapper().registerKotlinModule()

    data class Response(@JsonProperty("isRecyclable") val isRecyclable: Boolean, @JsonProperty("activeConnections") val activeConnections: Int)

    override suspend fun isInstanceRecyclable(shinyProxyInstance: ShinyProxyInstance): Boolean {
        for (i in 1..5) {
            try {
                val ips = orchestrator.getContainerIPs(shinyProxyInstance)
                if (ips.isEmpty()) {
                    delay(500)
                    continue
                }
                for (ip in ips) {
                    val resp = checkServer(ip)
                    if (resp == null) {
                        // no response received, try to check again
                        logger.warn { "${logPrefix(shinyProxyInstance)} unreachable for recyclable check (using $ip)" }
                        delay(500)
                        continue
                    }
                    if (!resp.isRecyclable) {
                        logger.info { "${logPrefix(shinyProxyInstance)} Replica is not recyclable." }
                        return false
                    }
                }
            } catch (e: Throwable) {
                logger.warn(e) { "${logPrefix(shinyProxyInstance)} exception during recyclable check" }
                delay(500)
            }
        }

        return true
    }

    private fun checkServer(ip: String): Response? {
        val url = "http://${ip}:9090/actuator/recyclable"
        val request = Request.Builder()
            .url(url)
            .build()

        val body = try {
            client.newCall(request).execute().body?.string() ?: return null
        } catch (e: IOException) {
            return null
        }
        return objectMapper.readValue(body, Response::class.java)
    }

}
