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
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import eu.openanalytics.shinyproxyoperator.Config
import eu.openanalytics.shinyproxyoperator.FileManager
import eu.openanalytics.shinyproxyoperator.IOrchestrator
import eu.openanalytics.shinyproxyoperator.IShinyProxySource
import eu.openanalytics.shinyproxyoperator.InternalException
import eu.openanalytics.shinyproxyoperator.LabelFactory
import eu.openanalytics.shinyproxyoperator.event.ShinyProxyEvent
import eu.openanalytics.shinyproxyoperator.impl.docker.monitoring.MonitoringConfig
import eu.openanalytics.shinyproxyoperator.impl.source.FileSource
import eu.openanalytics.shinyproxyoperator.logPrefix
import eu.openanalytics.shinyproxyoperator.model.ShinyProxy
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyStatus
import eu.openanalytics.shinyproxyoperator.prettyMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.apache.commons.lang3.RandomStringUtils
import org.mandas.docker.client.DockerClient
import org.mandas.docker.client.builder.jersey.JerseyDockerClientBuilder
import org.mandas.docker.client.exceptions.DockerException
import org.mandas.docker.client.messages.ContainerConfig
import org.mandas.docker.client.messages.HostConfig
import org.mandas.docker.client.messages.LogConfig
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.regex.Pattern
import kotlin.io.path.createDirectories

class DockerOrchestrator(channel: Channel<ShinyProxyEvent>,
                         config: Config,
                         private val dataDir: Path,
                         private val inputDir: Path) : IOrchestrator {

    private val dockerGID: Int = config.readConfigValue(null, "SPO_DOCKER_GID") { it.toInt() }
    private val dockerSocket: String = config.readConfigValue("/var/run/docker.sock", "SPO_DOCKER_SOCKET") { it }
    private val disableICC: Boolean = config.readConfigValue(false, "SPO_DISABLE_ICC") { it.toBoolean() }
    private var dataDirUid: Int
    private val state = mutableMapOf<String, ShinyProxyStatus>()

    private val logger = KotlinLogging.logger { }

    private val dockerClient: DockerClient
    private val objectMapper = ObjectMapper(YAMLFactory())
    private val caddyConfig: CaddyConfig
    private val dockerActions: DockerActions
    private val monitoringConfig: MonitoringConfig
    private val shinyProxyReadyChecker: ShinyProxyReadyChecker
    private val redisConfig: RedisConfig
    private val fileManager = FileManager()
    private val eventWriter: FileWriter
    private val eventObjectMapper: ObjectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS)
    private val craneConfig: CraneConfig
    private val persistentState = PersistentState(dataDir)
    private val logFilesCleaner: LogFilesCleaner

    init {
        initSharedNetworkName(config)

        if (!Files.exists(dataDir)) {
            throw InternalException("The data directory doesn't exist: '$dataDir'!")
        }
        if (!Files.isReadable(dataDir)) {
            throw InternalException("Missing read permission for the data directory: '$dataDir'!");
        }
        if (!Files.isWritable(dataDir)) {
            throw InternalException("Missing write permission for the data directory: '$dataDir'!");
        }
        objectMapper.registerKotlinModule()
        objectMapper.propertyNamingStrategy = PropertyNamingStrategies.KEBAB_CASE
        dockerClient = JerseyDockerClientBuilder()
            .fromEnv()
            .uri("unix://" + dockerSocket)
            .readTimeoutMillis(0) // no timeout, needed for startContainer and logs, #32606
            .build()
        // test Docker permissions
        try {
            dockerClient.listImages()
        } catch (e: DockerException) {
            if (e.message?.contains("permission denied", ignoreCase = true) == true) {
                throw InternalException("No permission to access Docker daemon, check the mount of the socket and the 'SPO_DOCKER_GID' environment variable.");
            }
            throw e
        }

        dataDirUid = getDatadirUId()
        caddyConfig = CaddyConfig(dockerClient, dataDir, config)
        dockerActions = DockerActions(dockerClient, config)
        shinyProxyReadyChecker = ShinyProxyReadyChecker(channel, dockerActions, dockerClient, dataDir)
        redisConfig = RedisConfig(dockerClient, dockerActions, persistentState, dataDir, dataDirUid, config)
        craneConfig = CraneConfig(dockerClient, dockerActions, dataDir, inputDir, redisConfig, caddyConfig, persistentState, dataDirUid)
        monitoringConfig = MonitoringConfig(dockerClient, dockerActions, dataDir, dataDirUid, caddyConfig, config, dockerSocket)
        logFilesCleaner = LogFilesCleaner(dataDir.resolve("logs"), fileManager, dockerActions)
        fileManager.createDirectories(dataDir)
        eventWriter = FileWriter(dataDir.resolve("events.json").toFile())
    }

    companion object {
        lateinit var SHARED_NETWORK_NAME: String
            private set

        fun initSharedNetworkName(config: Config) {
            SHARED_NETWORK_NAME = config.readConfigValue("sp-shared-network", "SPO_SHARED_NETWORK_NAME") { it }
        }
    }

    fun getRedisConfig(): RedisConfig {
        return redisConfig
    }

    fun getCaddyConfig(): CaddyConfig {
        return caddyConfig
    }

    override fun getShinyProxyStatus(shinyProxy: ShinyProxy): ShinyProxyStatus {
        return state.getOrPut(shinyProxy.realmId) { ShinyProxyStatus(shinyProxy.realmId, shinyProxy.hashOfCurrentSpec) }
    }

    override fun addNewInstanceToStatus(shinyProxy: ShinyProxy, newInstance: ShinyProxyInstance) {
        val status = getShinyProxyStatus(shinyProxy)
        val checkExistingInstance = status.getInstance(newInstance)
        val instances = ArrayList(status.instances)
        if (checkExistingInstance != null) {
            // status has already been updated (e.g. after an HTTP 409 Conflict response)
            // remove the existing instance and add the new one, to ensure that all values are correct.
            instances.remove(checkExistingInstance)
        }
        instances.add(newInstance)
        state[shinyProxy.realmId] = status.copy(instances = instances, hashOfCurrentSpec = newInstance.hashOfSpec)
    }

    override suspend fun removeInstanceFromStatus(instance: ShinyProxyInstance) {
        if (instance.isLatestInstance) {
            throw IllegalStateException("Instance being removed is latest")
        }
        val status = state[instance.realmId] ?: error("No status found") // TOOD
        val instances = ArrayList(status.instances)
        instances.remove(instance)
        state[instance.realmId] = status.copy(instances = instances)
    }

    override fun makeLatest(shinyProxy: ShinyProxy, instance: ShinyProxyInstance) {
        val status = getShinyProxyStatus(shinyProxy)
        val instances = status.instances.map { inst ->
            return@map if (inst.hashOfSpec == instance.hashOfSpec && inst.revision == instance.revision) {
                inst.copy(isLatestInstance = true)
            } else {
                inst.copy(isLatestInstance = false)
            }
        }
        state[shinyProxy.realmId] = status.copy(instances = ArrayList(instances))
        persistentState.storeLatest(shinyProxy.realmId, instance.hashOfSpec)
    }

    override suspend fun reconcileInstance(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): Boolean {
        redisConfig.reconcile()
        try {
            monitoringConfig.reconcile(shinyProxy)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to reconcile monitoring components" }
        }
        try {
            craneConfig.reconcile(shinyProxy, shinyProxyInstance)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to reconcile crane components" }
        }
        val containers = dockerActions.getContainers(shinyProxyInstance)
        if (containers.size < shinyProxy.replicas) {
            val networkName = "${SHARED_NETWORK_NAME}-internal-${shinyProxy.realmId}"
            if (!dockerActions.networkExists(networkName)) {
                logger.info { "${logPrefix(shinyProxyInstance)} [Docker] Creating network" }
                dockerActions.createNetwork(networkName, disableICC)
            }

            logger.info { "${logPrefix(shinyProxyInstance)} [Docker] Pulling image" }
            dockerActions.pullImage(shinyProxy.image, shinyProxy.imagePullPolicy)

            val version = if (shinyProxyInstance.hashOfSpec == shinyProxy.hashOfCurrentSpec) {
                System.currentTimeMillis()
            } else {
                0
            }

            var cpuPeriod: Long? = null
            var cpuQuota: Long? = null
            if (shinyProxy.cpuLimit != null) {
                cpuPeriod = 100_000
                try {
                    cpuQuota = getCpuQuota(cpuPeriod, shinyProxy.cpuLimit!!)
                } catch (e: Exception) {
                    throw RuntimeException("Invalid cpu limit: " + e.prettyMessage(), e)
                }
            }
            if (shinyProxy.cpuRequest != null) {
                logger.warn { "Realm '${shinyProxy.realmId}' has 'cpuRequest' configured: this is not supported in Docker and is ignored." }
            }

            repeat(shinyProxy.replicas - containers.size) {
                val suffix = RandomStringUtils.randomAlphanumeric(10)
                val containerName = "sp-${shinyProxyInstance.realmId}-${shinyProxyInstance.hashOfSpec}-${shinyProxyInstance.revision}-${suffix}"

                val dir = dataDir.resolve(containerName)
                val logsDir = dataDir.resolve("logs").resolve(containerName)
                logsDir.createDirectories()
                withContext(Dispatchers.IO) {
                    fileManager.createDirectories(dir)
                    fileManager.writeFile(
                        dir.resolve("application.yml"),
                        shinyProxy.specAsYaml
                    )
                    fileManager.writeFile(
                        dir.resolve("generated.yml"),
                        generateConfig(shinyProxy, networkName)
                    )
                    fileManager.writeFile(
                        dir.resolve("termination-log"),
                        ""
                    )
                }

                copyTemplates(shinyProxy, dir)
                fileManager.createDirectories(dir.resolve("logs"))

                val hostConfigBuilder = HostConfig.builder()
                    .networkMode(SHARED_NETWORK_NAME)
                    .binds(
                        HostConfig.Bind.builder()
                            .from(dockerSocket)
                            .to("/var/run/docker.sock")
                            .readOnly(true)
                            .build(),
                        HostConfig.Bind.builder()
                            .from(dir.resolve("application.yml").toString())
                            .to("/opt/shinyproxy/application.yml")
                            .readOnly(true)
                            .build(),
                        HostConfig.Bind.builder()
                            .from(dir.resolve("generated.yml").toString())
                            .to("/opt/shinyproxy/generated.yml")
                            .readOnly(true)
                            .build(),
                        HostConfig.Bind.builder()
                            .from(dir.resolve("templates").toString())
                            .to("/opt/shinyproxy/templates")
                            .readOnly(true)
                            .build(),
                        HostConfig.Bind.builder()
                            .from(logsDir.toString())
                            .to("/opt/shinyproxy/logs")
                            .build(),
                        HostConfig.Bind.builder()
                            .from(dir.resolve("termination-log").toString())
                            .to("/dev/termination-log")
                            .build(),
                    )
                    .groupAdd(dockerGID.toString())
                    .restartPolicy(HostConfig.RestartPolicy.always())
                    .memoryReservation(memoryToBytes(shinyProxy.memoryRequest))
                    .memory(memoryToBytes(shinyProxy.memoryLimit))
                    .cpuPeriod(cpuPeriod)
                    .cpuQuota(cpuQuota)
                    .dns(shinyProxy.dns)

                if (monitoringConfig.isEnabled()) {
                    hostConfigBuilder.logConfig(LogConfig.builder()
                        .logType("loki")
                        .logOptions(mapOf(
                            "loki-url" to monitoringConfig.grafanaLokiConfig.getLokiPushUrl(),
                            "mode" to "non-blocking",
                            "loki-external-labels" to "sp_realm_id=${shinyProxy.realmId},sp_instance=${shinyProxyInstance.hashOfSpec},namespace=${shinyProxy.namespace},app=shinyproxy"
                        ))
                        .build())
                }

                // Build environment variables list: default ones + operator environment variables
                val envVars = mutableListOf(
                    "PROXY_VERSION=${version}",
                    "PROXY_REALM_ID=${shinyProxy.realmId}",
                    "SPRING_CONFIG_IMPORT_0=/opt/shinyproxy/generated.yml"
                )
                
                // Add environment variables from the operator's environment
                // Any environment variable with prefix SHINYPROXY_ENV_ will be passed to the container
                // with the prefix stripped (e.g., SHINYPROXY_ENV_DATABASE_URL -> DATABASE_URL)
                System.getenv().forEach { (key, value) ->
                    if (key.startsWith("SHINYPROXY_ENV_")) {
                        val targetKey = key.removePrefix("SHINYPROXY_ENV_")
                        // Validate that the target key is not empty and value doesn't contain newlines
                        when {
                            targetKey.isEmpty() -> {
                                logger.warn { "${logPrefix(shinyProxyInstance)} [Docker] Ignoring invalid environment variable with empty key: $key" }
                            }
                            value.contains('\n') || value.contains('\r') -> {
                                logger.warn { "${logPrefix(shinyProxyInstance)} [Docker] Skipping environment variable '$targetKey' due to invalid value containing newline characters" }
                            }
                            else -> {
                                envVars.add("${targetKey}=${value}")
                                logger.info { "${logPrefix(shinyProxyInstance)} [Docker] Passing environment variable '$targetKey' to ShinyProxy container" }
                            }
                        }
                    }
                }

                val containerConfig = ContainerConfig.builder()
                    .image(shinyProxy.image)
                    .hostConfig(hostConfigBuilder.build())
                    .labels(shinyProxy.labels + LabelFactory.labelsForShinyProxyInstance(shinyProxyInstance, version))
                    .env(envVars)
                    .user(dataDirUid.toString())
                    .build()

                logger.info { "${logPrefix(shinyProxyInstance)} [Docker] Creating new container" }
                val containerId = dockerClient.createContainer(containerConfig, containerName).id()
                if (!disableICC) {
                    dockerClient.connectToNetwork(containerId, networkName)
                }
                dockerClient.startContainer(containerId)
            }
            shinyProxyReadyChecker.add(shinyProxyInstance)
        }

        return shinyProxyReadyChecker.isReady(shinyProxyInstance) == ShinyProxyReadyChecker.TaskStatus.READY
    }

    private fun copyTemplates(shinyProxy: ShinyProxy, dir: Path) {
        val source = getTemplateSource(shinyProxy) ?: return
        val destination = dir.resolve("templates")
        fileManager.createDirectories(destination)
        source.toFile().copyRecursively(destination.toFile(), true)
        logger.info { "${logPrefix(shinyProxy)} [Docker] Templates copied" }
    }

    private fun getTemplateSource(shinyProxy: ShinyProxy): Path? {
        val source = inputDir.resolve("templates").resolve(shinyProxy.name)
        if (Files.exists(source) && Files.isDirectory(source)) {
            return source
        }
        val source2 = inputDir.resolve("templates").resolve(shinyProxy.realmId)
        if (Files.exists(source2) && Files.isDirectory(source2)) {
            return source2
        }
        return null
    }

    override suspend fun deleteInstance(shinyProxyInstance: ShinyProxyInstance) {
        val containers = dockerActions.getContainers(shinyProxyInstance)
        containers.forEach { container ->
            dockerActions.stopAndRemoveContainer(container)
            val containerName = container.name() ?: return@forEach
            fileManager.removeDirectory(dataDir.resolve(containerName))
            logger.info { "${logPrefix(shinyProxyInstance)} DeleteInstance: removed container $containerName" }
        }
        shinyProxyReadyChecker.remove(shinyProxyInstance)
    }

    override suspend fun reconcileIngress(shinyProxy: ShinyProxy, latestShinyProxyInstance: ShinyProxyInstance) {
        caddyConfig.addShinyProxy(shinyProxy, latestShinyProxyInstance)
        caddyConfig.reconcile()
    }

    override suspend fun deleteRealm(realmId: String) {
        caddyConfig.removeRealm(realmId)
        monitoringConfig.grafanaConfig.removeRealm(realmId)
        val listParams = arrayListOf(
            DockerClient.ListContainersParam.withStatusRunning(),
        )
        for ((key, value) in LabelFactory.labelsForShinyProxy(realmId)) {
            listParams.add(DockerClient.ListContainersParam.withLabel(key, value))
        }
        val containers = dockerClient.listContainers(*listParams.toTypedArray())
        for (container in containers) {
            val containerName = container.name() ?: continue
            dockerActions.stopAndRemoveContainer(container)
            fileManager.removeDirectory(dataDir.resolve(containerName))
            logger.info { "${logPrefix(realmId)} DeleteRealm: removed container ${containerName}}" }
        }
        shinyProxyReadyChecker.remove(realmId)
        craneConfig.remove(realmId)
        state.remove(realmId)
    }

    override fun getShinyProxyStatuses(): List<ShinyProxyStatus> {
        return state.values.toList()
    }

    override fun getContainerIPs(shinyProxyInstance: ShinyProxyInstance): List<String> {
        return dockerActions.getContainers(shinyProxyInstance).mapNotNull { it.getSharedNetworkIpAddress() }
    }

    override fun logEvent(shinyProxyInstance: ShinyProxyInstance, type: String, action: String, message: String?) {
        synchronized(eventWriter) {
            try {
                val event = Event(shinyProxyInstance.realmId, shinyProxyInstance.hashOfSpec, shinyProxyInstance.revision, type, action, message)
                eventWriter.write(eventObjectMapper.writeValueAsString(event) + "\n")
                eventWriter.flush()
            } catch (e: Exception) {
                logger.info(e) { "${logPrefix(shinyProxyInstance)} Error while storing event" }
            }
        }
    }

    override fun logEvent(type: String, action: String, message: String?) {
        synchronized(eventWriter) {
            try {
                val event = Event("N/A", "N/A", 0, type, action, message)
                eventWriter.write(eventObjectMapper.writeValueAsString(event) + "\n")
                eventWriter.flush()
            } catch (e: Exception) {
                logger.info(e) { "Error while storing event" }
            }
        }
    }

    data class Event(val realmId: String,
                     val instance: String,
                     val revision: Int,
                     val type: String,
                     val action: String,
                     val message: String?,
                     val eventTime: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)
    )

    override suspend fun init(source: IShinyProxySource) {
        logger.info { "Initializing DockerOrchestrator" }
        redisConfig.init()
        val containers = dockerClient.listContainers(
            DockerClient.ListContainersParam.withStatusRunning(),
            DockerClient.ListContainersParam.withLabel(LabelFactory.APP_LABEL, LabelFactory.APP_LABEL_VALUE)
        )
        val newState = mutableMapOf<String, HashSet<Pair<ShinyProxyInstance, Long>>>()
        val oldRealmState = persistentState.readState().realms
        for (container in containers) {
            val realmId = container.getLabelOrNull(LabelFactory.REALM_ID_LABEL) ?: continue
            val instanceId = container.getLabelOrNull(LabelFactory.INSTANCE_LABEL) ?: continue
            val revision = container.getLabelOrNull(LabelFactory.REVISION_LABEL)?.toInt() ?: continue
            val version = container.getLabelOrNull(LabelFactory.VERSION_LABEL)?.toLong() ?: continue
            val instances = newState.getOrPut(realmId) { hashSetOf() }
            val name = realmId.removePrefix("default-")
            instances.add(Pair(ShinyProxyInstance(name, FileSource.NAMESPACE, realmId, instanceId, false, revision), version))
        }

        // If there is only one container for a realm, this automatically becomes the latest container.
        // If there are multiple containers for a single realm, the container with the highest version is the latest container
        for ((realmId, instances) in newState) {
            if (instances.size == 1) {
                val instance = instances.first()
                if (oldRealmState[realmId]?.latestInstance != null && oldRealmState[realmId]?.latestInstance != instance.first.hashOfSpec) {
                    logger.warn { "${logPrefix(realmId)} Latest instance in state is ${oldRealmState[realmId]?.latestInstance}, but found only one (different) container: ${instance.first.hashOfSpec}" }
                }
                val result = ShinyProxyStatus(instance.first.realmId, instance.first.hashOfSpec, arrayListOf(instance.first.copy(isLatestInstance = true)))
                state[realmId] = result

                logger.info { "${logPrefix(result.instances[0])} Found existing container" }
                shinyProxyReadyChecker.add(result.instances[0])
            } else {
                val shouldBeLatest = oldRealmState[realmId]?.latestInstance
                val latest = if (shouldBeLatest != null && instances.firstOrNull { it.first.hashOfSpec == shouldBeLatest } != null) {
                    instances.first { it.first.hashOfSpec == shouldBeLatest }.first
                } else {
                    if (shouldBeLatest != null) {
                        logger.warn { "${logPrefix(realmId)} Latest instance in state is ${oldRealmState[realmId]?.latestInstance}, but no container with this hash found" }
                    }
                    instances.maxBy { it.second }.first
                }
                val newInstances = arrayListOf<ShinyProxyInstance>()
                for ((instance, _) in instances) {
                    val newInstance = instance.copy(isLatestInstance = instance == latest)
                    newInstances.add(newInstance)
                    shinyProxyReadyChecker.add(newInstance)
                }

                val result = ShinyProxyStatus(realmId, latest.hashOfSpec, newInstances)
                state[realmId] = result
                logger.info { "${logPrefix(latest)} Found multiple containers for this realm" }
            }
            val latestInstance = state[realmId]?.instances?.first { it.isLatestInstance } ?: continue
            val name = realmId.removePrefix("default-")
            val shinyProxy = source.get(FileSource.NAMESPACE, name) ?: continue
            caddyConfig.addShinyProxy(shinyProxy, latestInstance) // not reconcile yet, wait for all ShinyProxy servers to be added
            craneConfig.init(shinyProxy)
        }
        logFilesCleaner.init()
    }

    fun stop() {
        shinyProxyReadyChecker.stop()
        craneConfig.stop()
        logFilesCleaner.stop()
    }

    private fun generateConfig(shinyProxy: ShinyProxy, networkName: String): String {
        val config = buildMap {
            put("spring", hashMapOf<String, Any>(
                "data" to mapOf(
                    "redis" to mapOf(
                        "password" to redisConfig.getRedisPassword(),
                        "host" to redisConfig.getContainerName()
                    )
                )
            ))
            put("proxy", buildMap {
                put("docker", buildMap {
                    put("default-container-network", networkName)
                    if (monitoringConfig.isEnabled()) {
                        put("loki-url", monitoringConfig.grafanaLokiConfig.getLokiPushUrl())
                    }
                })
                put("template-path", "/opt/shinyproxy/templates")
                if (monitoringConfig.isEnabled()) {
                    put("monitoring", mapOf("grafana-url" to monitoringConfig.grafanaConfig.getGrafanaUrl(shinyProxy)))
                    put("log-as-json", "true")
                    put("usage-stats-url", "micrometer")
                }
            })
            put("logging", buildMap {
                put("file", buildMap {
                    put("name", "/opt/shinyproxy/logs/shinyproxy.log")
                })
            })
            if (monitoringConfig.isEnabled()) {
                put("management", buildMap {
                    put("prometheus", buildMap {
                        put("metrics", buildMap {
                            put("export", buildMap {
                                put("enabled", true)
                            })
                        })
                    })
                })
            }
        }
        return objectMapper.writeValueAsString(config)
    }

    private fun memoryToBytes(memory: String?): Long? {
        if (memory.isNullOrEmpty()) return null
        val matcher = Pattern.compile("(\\d+\\.?\\d*)([bkmg]?)i?").matcher(memory.lowercase(Locale.getDefault()))
        if (!matcher.matches()) {
            throw IllegalArgumentException("Invalid memory argument: $memory")
        }
        val mem = matcher.group(1).toDouble()
        val unit = matcher.group(2)
        return when (unit) {
            "k" -> mem * 1024
            "m" -> mem * (1024 * 1024).toLong()
            "g" -> mem * (1024 * 1024 * 1024).toLong()
            else -> throw IllegalArgumentException("Invalid memory argument: $memory")
        }.toLong()
    }

    private fun getCpuQuota(cpuPeriod: Long, cpu: String): Long {
        val converted = if (cpu.endsWith("m")) {
            cpu.dropLast(1).toDouble() / 1_000
        } else {
            cpu.toDouble()
        }
        return (cpuPeriod.toDouble() * converted).toLong()
    }

    private fun getDatadirUId(): Int {
        try {
            val owner = Integer.parseInt(Files.getAttribute(dataDir, "unix:uid", LinkOption.NOFOLLOW_LINKS).toString())
            logger.info { "Owner of data dir is '$owner'" }
            return owner
        } catch (e: Exception) {
            logger.warn(e) { "Failed to determine owner of data dir - falling back to user 1000" }
            return 1000
        }
    }

}
