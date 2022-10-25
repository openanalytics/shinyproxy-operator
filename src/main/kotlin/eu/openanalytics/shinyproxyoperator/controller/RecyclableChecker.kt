/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021-2022 Open Analytics
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
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance
import mu.KotlinLogging
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit


class RecyclableChecker(
    private val podRetriever: PodRetriever,
) {

    private val logger = KotlinLogging.logger {}
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val objectMapper = ObjectMapper().registerKotlinModule()

    data class Response(@JsonProperty("isRecyclable") val isRecyclable: Boolean, @JsonProperty("activeConnections") val activeConnections: Int)

    fun isInstanceRecyclable(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): Boolean {
        val pods = podRetriever.getShinyProxyPods(shinyProxy, shinyProxyInstance)

        for (pod in pods) {
            val url = "http://${pod.status.podIP}:9090/actuator/recyclable"
            val request = Request.Builder()
                .url(url)
                .build()

            val body = try {
                client.newCall(request).execute().body?.string()
            } catch (e: IOException) {
                logger.warn { "${shinyProxy.logPrefix(shinyProxyInstance)} unreachable for recyclable check (using ${url})" }
                // server unreachable -> do not delete it yet
                return false
            }
            if (body == null) {
                // server unreachable -> do not delete it yet
                return false
            }
            val resp = objectMapper.readValue(body, Response::class.java)
            if (!resp.isRecyclable) {
                logger.info { "${shinyProxy.logPrefix(shinyProxyInstance)} Replica has ${resp.activeConnections} open websocket connections" }
                return false
            }
        }

        return true
    }

}
