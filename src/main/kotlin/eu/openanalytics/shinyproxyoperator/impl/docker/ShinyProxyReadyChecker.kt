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

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import eu.openanalytics.shinyproxyoperator.event.ShinyProxyEvent
import eu.openanalytics.shinyproxyoperator.event.ShinyProxyEventType
import eu.openanalytics.shinyproxyoperator.logPrefix
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import org.mandas.docker.client.DockerClient
import org.mandas.docker.client.messages.Container
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ShinyProxyReadyChecker(private val channel: Channel<ShinyProxyEvent>, private val dockerActions: DockerActions, private val dockerClient: DockerClient, private val dataDir: Path) {

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

    fun add(shinyProxyInstance: ShinyProxyInstance) {
        val containers = dockerActions.getContainers(shinyProxyInstance)
        val key = TaskKey.of(shinyProxyInstance)
        if (!tasks.contains(key)) {
            tasks[TaskKey.of(shinyProxyInstance)] = scope.async {
                checkInstances(containers, shinyProxyInstance)
            }
        }
    }

    fun isReady(shinyProxyInstance: ShinyProxyInstance): TaskStatus {
        val task = tasks[TaskKey.of(shinyProxyInstance)] ?: error("No task found")
        if (!task.isCompleted) {
            return TaskStatus.WIP
        }
        return task.getCompleted()
    }

    fun stop() {
        scope.cancel()
    }

    private suspend fun checkInstances(containers: List<Container>, shinyProxyInstance: ShinyProxyInstance): TaskStatus {
        if (containers.isEmpty()) {
            logger.info { "${logPrefix(shinyProxyInstance)} failed, no containers" }
            return TaskStatus.FAILED
        }
        val checks = containers.associateWith { container ->
            scope.async {
                checkInstance(container, shinyProxyInstance)
            }
        }

        var failed = false
        for ((container, check) in checks) {
            val status = check.await()
            if (!failed && status == TaskStatus.FAILED) {
                failed = true
                val containerName = container.name()
                val message = if (containerName != null) {
                    val path = dataDir.resolve("logs").resolve(containerName).resolve("shinyproxy.log")
                    "Full log file available at '${path}', last output: " + readTerminationMessage(container)
                } else {
                    readTerminationMessage(container)
                }
                channel.send(ShinyProxyEvent(ShinyProxyEventType.FAILURE, shinyProxyInstance.realmId, shinyProxyInstance.name, shinyProxyInstance.namespace, shinyProxyInstance.hashOfSpec, message = message))
            }
        }

        return if (failed) {
            TaskStatus.FAILED
        } else {
            channel.send(ShinyProxyEvent(ShinyProxyEventType.RECONCILE, shinyProxyInstance.realmId, shinyProxyInstance.name, shinyProxyInstance.namespace, shinyProxyInstance.hashOfSpec))
            TaskStatus.READY
        }
    }

    private suspend fun checkInstance(container: Container, shinyProxyInstance: ShinyProxyInstance): TaskStatus {
        val ip = container.getSharedNetworkIpAddress()
        if (ip == null) {
            logger.info { "${logPrefix(shinyProxyInstance)} [Container: ${container.shortId()}] failed, no ip for container" }
            return TaskStatus.FAILED
        }
        for (checks in 0..MAX_CHECKS) {
            val resp = checkServer(ip)
            if (resp != null && resp.status.equals("up", ignoreCase = true)) {
                logger.info { "${logPrefix(shinyProxyInstance)} [Container: ${container.shortId()}] ready" }
                return TaskStatus.READY
            }
            if (containerWasRestarted(container)) {
                logger.info { "${logPrefix(shinyProxyInstance)} [Container: ${container.shortId()}] failed (container has been restarted) (${checks}/${MAX_CHECKS})" }
                return TaskStatus.FAILED
            }
            logger.info { "${logPrefix(shinyProxyInstance)} [Container: ${container.shortId()}] not ready yet (${checks}/${MAX_CHECKS})" }
            delay(5_000)
        }
        logger.info { "${logPrefix(shinyProxyInstance)} [Container: ${container.shortId()}] failed (${MAX_CHECKS}/${MAX_CHECKS})" }
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

    private fun containerWasRestarted(container: Container): Boolean {
        val containerInfo = dockerClient.inspectContainer(container.id())
        return containerInfo.restartCount() >= 1
    }

    fun remove(shinyProxyInstance: ShinyProxyInstance) {
        val task = tasks.remove(TaskKey.of(shinyProxyInstance))
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

    private fun readTerminationMessage(container: Container): String? {
        return try {
            val name = container.name() ?: return null
            dataDir.resolve(name).resolve("termination-log").toFile().readText()
        } catch (e: IOException) {
            null
        }
    }

    enum class TaskStatus {
        WIP,
        FAILED,
        READY
    }

    private data class Response(@JsonProperty("status") val status: String)

    private data class TaskKey(val realmId: String, val hashOfSpec: String, val revision: Int) {
        companion object {
            fun of(shinyProxyInstance: ShinyProxyInstance): TaskKey {
                return TaskKey(shinyProxyInstance.realmId, shinyProxyInstance.hashOfSpec, shinyProxyInstance.revision)
            }
        }
    }

}
