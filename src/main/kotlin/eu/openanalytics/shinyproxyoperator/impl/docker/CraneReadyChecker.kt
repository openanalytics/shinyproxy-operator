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
package eu.openanalytics.shinyproxyoperator.impl.docker

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import eu.openanalytics.shinyproxyoperator.logPrefix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import mu.KotlinLogging
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class CraneReadyChecker {

    private val scope = CoroutineScope(Dispatchers.Default)
    private val tasks = ConcurrentHashMap<TaskKey, Deferred<TaskStatus>>()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val logger = KotlinLogging.logger { }

    companion object {
        // 120 seconds
        private const val MAX_CHECKS = 24
    }

    fun add(ip: String, realmId: String, hashOfSpec: String) {
        tasks.computeIfAbsent(TaskKey(realmId, hashOfSpec)) {
            scope.async {
                checkInstance(ip, realmId)
            }
        }
    }

    fun isReady(realmId: String, hashOfSpec: String): TaskStatus {
        val task = tasks[TaskKey(realmId, hashOfSpec)] ?: error("No task found")
        if (!task.isCompleted) {
            return TaskStatus.WIP
        }
        return task.getCompleted()
    }

    private suspend fun checkInstance(ip: String, realmId: String): TaskStatus {
        for (checks in 0..MAX_CHECKS) {
            val resp = checkServer(ip)
            if (resp != null && resp.status.equals("up", ignoreCase = true)) {
                logger.info { "${logPrefix(realmId)} [Crane] Ready (waiting for ingress)" }
                return TaskStatus.READY
            }
            logger.info { "${logPrefix(realmId)} [Crane] Not ready yet (${checks}/${MAX_CHECKS})" }
            delay(5_000)
        }
        logger.info { "${logPrefix(realmId)} [Crane] Failed (${MAX_CHECKS}/${MAX_CHECKS})" }
        return TaskStatus.FAILED
    }

    private fun checkServer(ip: String): Response? {
        val url = "http://${ip}:9090/actuator/health/readiness"
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

    fun remove(realmId: String, hashOfSpec: String) {
        val task = tasks.remove(TaskKey(realmId, hashOfSpec))
        if (task != null && !task.isCompleted) {
            task.cancel()
        }
    }

    suspend fun remove(realmId: String) {
        val keys = tasks.keys()
        for (key in keys) {
            if (key.realmId == realmId) {
                tasks.remove(key)?.await()
            }
        }
    }

    enum class TaskStatus {
        WIP,
        FAILED,
        READY
    }

    private data class Response(@JsonProperty("status") val status: String)

    private data class TaskKey(val realmId: String, val hashOfSpec: String)

}
