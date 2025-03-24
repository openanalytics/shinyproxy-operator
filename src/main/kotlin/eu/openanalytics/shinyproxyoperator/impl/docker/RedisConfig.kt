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

import eu.openanalytics.shinyproxyoperator.Config
import eu.openanalytics.shinyproxyoperator.FileManager
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mandas.docker.client.DockerClient
import org.mandas.docker.client.messages.ContainerConfig
import org.mandas.docker.client.messages.HostConfig
import java.nio.file.Files
import java.nio.file.Path

class RedisConfig(private val dockerClient: DockerClient, private val dockerActions: DockerActions, mainDataDir: Path, config: Config) {

    private val containerName = "sp-redis"
    private val dataDir: Path = mainDataDir.resolve(containerName)
    private val redisPassword: String
    private val logger = KotlinLogging.logger {}
    private val redisImage: String = config.readConfigValue("redis:7.2.4", "SPO_REDIS_IMAGE") { it }
    private val fileManager = FileManager()

    init {
        redisPassword = readPasswordFile("/run/secrets/redis_password")
            ?: readPasswordFile("redis_password.txt")
            ?: config.readConfigValue("", "SPO_REDIS_PASSWORD") { it }
        if (redisPassword == "") {
            error("Invalid redis password")
        }
    }

    fun getRedisPassword(): String {
        return redisPassword
    }

    suspend fun reconcile() {
        dockerActions.stopAndRemoveNotRunningContainer(containerName)
        if (dockerActions.getContainerByName(containerName) == null) {
            withContext(Dispatchers.IO) {
                fileManager.createDirectories(dataDir.resolve("data"))
            }
            logger.info { "[Redis] Pulling image" }
            dockerClient.pull(redisImage)

            withContext(Dispatchers.IO) {
                fileManager.writeFile(dataDir.resolve("redis.conf"),
                    """save 60 1
                    |appendonly yes
                    |requirepass $redisPassword
                    |""".trimMargin())
            }

            val hostConfig = HostConfig.builder()
                .networkMode(DockerOrchestrator.SHARED_NETWORK_NAME)
                .binds(HostConfig.Bind.builder()
                    .from(dataDir.resolve("data").toString())
                    .to("/data")
                    .build(),
                    HostConfig.Bind.builder()
                        .from(dataDir.resolve("redis.conf").toString())
                        .to("/etc/redis.conf")
                        .build()
                )
                .restartPolicy(HostConfig.RestartPolicy.always())
                .build()

            val containerConfig = ContainerConfig.builder()
                .image(redisImage)
                .hostConfig(hostConfig)
                .labels(mapOf("app" to "redis"))
                .cmd(listOf("redis-server", "/etc/redis.conf"))
                .user("1000")
                .build()

            logger.info { "[Redis] Creating new container" }
            val containerId = dockerClient.createContainer(containerConfig, containerName).id()!!
            dockerClient.startContainer(containerId)
        }
    }

    private fun readPasswordFile(path: String): String? {
        val nPath = Path.of(path)
        if (!Files.exists(nPath)) {
            return null
        }
        return try {
            Files.readString(nPath).trim()
        } catch (e: Exception) {
            null
        }
    }

}
