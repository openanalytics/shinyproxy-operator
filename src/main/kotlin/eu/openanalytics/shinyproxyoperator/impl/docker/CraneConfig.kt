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

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.readValue
import eu.openanalytics.shinyproxyoperator.FileManager
import eu.openanalytics.shinyproxyoperator.LabelFactory
import eu.openanalytics.shinyproxyoperator.logPrefix
import eu.openanalytics.shinyproxyoperator.model.ShinyProxy
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance
import eu.openanalytics.shinyproxyoperator.sha1
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.lang3.RandomStringUtils
import org.mandas.docker.client.DockerClient
import org.mandas.docker.client.messages.Container
import org.mandas.docker.client.messages.ContainerConfig
import org.mandas.docker.client.messages.HostConfig
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

class CraneConfig(private val dockerClient: DockerClient,
                  private val dockerActions: DockerActions,
                  private val dataDir: Path,
                  private val inputDir: Path,
                  private val redisConfig: RedisConfig,
                  private val caddyConfig: CaddyConfig,
                  private val persistentState: PersistentState,
                  private val dataDirUid: Int) {

    private val yamlMapper = ObjectMapper(YAMLFactory())
    private val fileManager = FileManager()
    private val logger = KotlinLogging.logger { }
    private val scope = CoroutineScope(Dispatchers.Default)
    private val deletedContainers = ConcurrentHashMap.newKeySet<String>()
    private val craneReadyChecker = CraneReadyChecker(dockerClient, dataDir)

    init {
        yamlMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        yamlMapper.enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
    }


    companion object {
        const val CRANE_INSTANCE_LABEL = "openanalytics.eu/sp-crane-instance"
        const val CRANE_APP_LABEL_VALUE = "crane"
    }

    suspend fun reconcile(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance) {
        val config = getConfig(shinyProxy)
        if (config == null) {
            remove(shinyProxy.realmId)
            return
        }

        val spec = parseConfig(shinyProxy, config) ?: return
        val hash = hash(spec)
        var containerId = dockerActions.getCraneContainer(shinyProxy, hash, deletedContainers.toList())?.id()
        if (containerId == null) {
            logger.info { "${logPrefix(shinyProxyInstance)} [Crane] Pulling image" }
            val image = getImage(spec)
            dockerActions.pullImage(image)

            val suffix = RandomStringUtils.randomAlphanumeric(10)
            val containerName = "sp-crane-${shinyProxyInstance.realmId}-${hash}-${suffix}"

            val dir = dataDir.resolve(containerName)
            val logsDir = dataDir.resolve("logs").resolve(containerName)
            logsDir.createDirectories()
            withContext(Dispatchers.IO) {
                fileManager.createDirectories(dir)
                fileManager.writeFile(
                    dir.resolve("application.yml"),
                    config
                )
                fileManager.writeFile(
                    dir.resolve("generated.yml"),
                    generateConfig()
                )
            }

            val binds = arrayListOf(
                HostConfig.Bind.builder()
                    .from(dir.resolve("application.yml").toString())
                    .to("/opt/crane/application.yml")
                    .readOnly(true)
                    .build(),
                HostConfig.Bind.builder()
                    .from(dir.resolve("generated.yml").toString())
                    .to("/opt/crane/generated.yml")
                    .readOnly(true)
                    .build(),
                HostConfig.Bind.builder()
                    .from(logsDir.toString())
                    .to("/opt/crane/logs")
                    .build(),
            )

            val mount = getMount(spec)
            if (mount != null) {
                binds.add(HostConfig.Bind.builder()
                    .from(mount)
                    .to("/mnt")
                    .build())
            }

            val hostConfig = HostConfig.builder()
                .networkMode(DockerOrchestrator.SHARED_NETWORK_NAME)
                .binds(*binds.toTypedArray())
                .restartPolicy(HostConfig.RestartPolicy.always())
                .build()

            val containerConfig = ContainerConfig.builder()
                .image(image)
                .hostConfig(hostConfig)
                .labels(dockerActions.labelsForCrane(shinyProxy.realmId, hash))
                .env("SPRING_CONFIG_IMPORT=/opt/crane/generated.yml")
                .user(dataDirUid.toString())
                .build()

            logger.info { "${logPrefix(shinyProxyInstance)} [Crane] Creating new container" }
            containerId = dockerClient.createContainer(containerConfig, containerName).id()
            dockerClient.startContainer(containerId)
        }

        if (containerId != null) {
            val ip = getIp(containerId, shinyProxy) ?: return
            craneReadyChecker.add(ip, shinyProxy.realmId, hash, containerId)
            val status = craneReadyChecker.isReady(shinyProxy.realmId, hash)
            if (status == CraneReadyChecker.TaskStatus.READY) {
                logger.info { "${logPrefix(shinyProxyInstance)} [Crane] Container ready" }
                caddyConfig.addCraneServer(shinyProxy.realmId, ip, getSubPath(spec))
                caddyConfig.reconcile()
                persistentState.storeLatestCrane(shinyProxy.realmId, hash)
                deleteOldContainers(shinyProxy.realmId, hash)
            }
        }
    }

    private fun parseConfig(shinyProxy: ShinyProxy, config: String): JsonNode? {
        try {
            return yamlMapper.readValue<JsonNode>(config)
        } catch (e: Exception) {
            logger.warn(e) { "${logPrefix(shinyProxy)} Failed to parse crane config" }
            return null
        }
    }

    suspend fun remove(realmId: String) {
        val containers = dockerActions.getCraneContainers(realmId)
        for (container in containers) {
            removeContainer(realmId, container)
        }
        craneReadyChecker.remove(realmId)
    }

    suspend fun init(shinyProxy: ShinyProxy) {
        try {
            val config = getConfig(shinyProxy)
            if (config == null) {
                remove(shinyProxy.realmId)
                return
            }
            val spec = yamlMapper.readValue<JsonNode>(config)
            val hashOfSpec = hash(spec)
            val oldRealmState = persistentState.readState().realms
            val craneLatestInstance = oldRealmState[shinyProxy.realmId]?.craneLatestInstance
            val container = getLatestContainer(shinyProxy, craneLatestInstance, hashOfSpec) ?: return
            logger.info { "${logPrefix(shinyProxy)} [Crane] Found container" }
            val ip = container.getSharedNetworkIpAddress()
            if (ip == null) {
                logger.warn { "${logPrefix(shinyProxy)} [Crane] No ip address found for container" }
                return
            }
            val hashOfContainer = container.getLabelOrNull(CRANE_INSTANCE_LABEL)
            if (hashOfContainer == null) {
                logger.warn { "${logPrefix(shinyProxy)} [Crane] No hash label found for container" }
                return
            }
            craneReadyChecker.add(ip, shinyProxy.realmId, hashOfContainer, container.id())
            caddyConfig.addCraneServer(shinyProxy.realmId, ip, getSubPath(spec))
        } catch (e: Exception) {
            logger.warn(e) { "Error while initializing CraneConfig" }
        }
    }

    private fun deleteOldContainers(realmId: String, currentHash: String) {
        val containers = dockerActions.getCraneContainers(realmId)

        val toDelete = arrayListOf<Container>()
        for (container in containers) {
            if (container.getLabelOrNull(CRANE_INSTANCE_LABEL) != currentHash) {
                toDelete.add(container)
                deletedContainers.add(container.id())
                logger.warn { "${logPrefix(realmId)} [Crane] Container ${container.shortId()} will be deleted" }
            }
        }

        if (toDelete.isNotEmpty()) {
            scope.launch {
                delay(120_000)
                for (container in toDelete) {
                    removeContainer(realmId, container)
                }
            }
        }
    }

    private fun removeContainer(realmId: String, container: Container) {
        if (dockerActions.stopAndRemoveContainer(container)) {
            logger.warn { "${logPrefix(realmId)} [Crane] Container ${container.shortId()} deleted" }
        }
        deletedContainers.remove(container.id())
        val hash = container.getLabelOrNull(CRANE_INSTANCE_LABEL)
        if (hash != null) {
            craneReadyChecker.remove(realmId, hash)
        }
        val containerName = container.name()
        if (containerName != null) {
            fileManager.removeDirectory(dataDir.resolve(containerName))
        }
    }

    private fun generateConfig(): String {
        val config = hashMapOf(
            "spring" to hashMapOf<String, Any>(
                "data" to mapOf(
                    "redis" to mapOf(
                        "password" to redisConfig.getRedisPassword(),
                        "host" to redisConfig.getContainerName()
                    )
                )
            ),
            "logging" to hashMapOf<String, Any>(
                "file" to mapOf(
                    "name" to "/opt/crane/logs/crane.log"
                )
            )
        )
        return yamlMapper.writeValueAsString(config)
    }

    private fun hash(spec: JsonNode): String {
        val specAsObject = yamlMapper.convertValue<Any>(spec)
        return yamlMapper.writeValueAsString(specAsObject).sha1()
    }

    private fun getSubPath(spec: JsonNode): String {
        if (spec.get("server")?.get("servlet")?.get("context-path")?.isTextual == true) {
            val path = spec.get("server").get("servlet").get("context-path").textValue()
            if (path.last() != '/') {
                return "$path/"
            }
            return path
        }

        return "/"
    }

    private fun getMount(spec: JsonNode): String? {
        if (spec.get("mount")?.isTextual == true) {
            return spec.get("mount").textValue()
        }
        return null
    }

    private suspend fun getConfig(shinyProxy: ShinyProxy): String? = withContext(Dispatchers.IO) {
        val configFiles = listOf(
            "${shinyProxy.name}.crane.yml", "${shinyProxy.name}.crane.yaml",
            "${shinyProxy.realmId}.crane.yml", "${shinyProxy.realmId}.crane.yaml",
        )
        for (configFile in configFiles) {
            val path = inputDir.resolve(configFile)
            if (path.exists()) {
                return@withContext path.readText()
            }
        }
        return@withContext null
    }

    private fun getImage(spec: JsonNode): String {
        if (spec.get("image")?.isTextual == true) {
            return spec.get("image").textValue()
        }
        return "openanalytics/crane:latest"
    }

    private fun getIp(containerId: String, shinyProxy: ShinyProxy): String? {
        val container = dockerClient.inspectContainer(containerId)
        val ip = container.getSharedNetworkIpAddress()
        if (ip.isNullOrBlank()) {
            logger.warn { "${logPrefix(shinyProxy)} [Crane] No ip address found for container" }
            return null
        }
        return ip
    }

    private fun getLatestContainer(shinyProxy: ShinyProxy, craneLatestInstance: String?, hash: String): Container? {
        val containers = dockerClient.listContainers(
            DockerClient.ListContainersParam.withStatusRunning(),
            DockerClient.ListContainersParam.withLabel(LabelFactory.APP_LABEL, CRANE_APP_LABEL_VALUE),
            DockerClient.ListContainersParam.withLabel(LabelFactory.REALM_ID_LABEL, shinyProxy.realmId)
        )
        if (containers.isEmpty()) {
            logger.warn { "${logPrefix(shinyProxy)} [Crane] No containers found" }
            return null
        }
        if (craneLatestInstance != null) {
            val latestContainer = containers.firstOrNull { it.getLabelOrNull(CRANE_INSTANCE_LABEL) == craneLatestInstance }
            if (latestContainer != null) {
                return latestContainer
            }
            logger.warn { "${logPrefix(shinyProxy)} [Crane] Latest instance in state is ${craneLatestInstance}, but no container with this hash found" }
        }
        val hashContainer = containers.firstOrNull { it.getLabelOrNull(CRANE_INSTANCE_LABEL) == hash }
        if (hashContainer != null) {
            return hashContainer
        }
        return containers.maxBy { it.created() }
    }

    fun stop() {
        craneReadyChecker.stop()
    }

}
