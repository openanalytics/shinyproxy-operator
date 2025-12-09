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
package eu.openanalytics.shinyproxyoperator.impl.docker.helpers

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import eu.openanalytics.shinyproxyoperator.Config
import eu.openanalytics.shinyproxyoperator.controller.EventController
import eu.openanalytics.shinyproxyoperator.convertToYamlString
import eu.openanalytics.shinyproxyoperator.helpers.AwaitableEvenController
import eu.openanalytics.shinyproxyoperator.helpers.MockConfig
import eu.openanalytics.shinyproxyoperator.helpers.MockRecyclableChecker
import eu.openanalytics.shinyproxyoperator.impl.docker.DockerActions
import eu.openanalytics.shinyproxyoperator.impl.docker.DockerOperator
import eu.openanalytics.shinyproxyoperator.impl.docker.getSharedNetworkIpAddress
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance
import eu.openanalytics.shinyproxyoperator.sha1
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.EMPTY_REQUEST
import org.junit.jupiter.api.AfterEach
import org.mandas.docker.client.DockerClient
import org.mandas.docker.client.builder.jersey.JerseyDockerClientBuilder
import org.mandas.docker.client.exceptions.ContainerNotFoundException
import org.mandas.docker.client.exceptions.DockerException
import org.mandas.docker.client.exceptions.NetworkNotFoundException
import org.mandas.docker.client.messages.Container
import org.mandas.docker.client.messages.ContainerInfo
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull


abstract class IntegrationTestBase {

    private val objectMapper = ObjectMapper(YAMLFactory())
    private val httpClient = OkHttpClient.Builder().callTimeout(Duration.ofSeconds(120)).readTimeout(Duration.ofSeconds(120)).build()

    protected val scope = CoroutineScope(Dispatchers.Default)
    val dockerClient: DockerClient = JerseyDockerClientBuilder()
        .fromEnv()
        .uri("unix://" + Config().readConfigValue("/var/run/docker.sock", "SPO_DOCKER_SOCKET") { it })
        .readTimeoutMillis(0) // no timeout, needed for startContainer and logs, #32606
        .build()
    protected val dockerActions = DockerActions(dockerClient, Config())

    @AfterEach
    fun cleanup() {
        runBlocking {
            scope.cancel()
            deleteContainers()
        }
    }

    protected fun setup(block: suspend (Path, Path, DockerOperator, AwaitableEvenController, DockerAssertions, MockRecyclableChecker, Config) -> Unit) {
        setup(mapOf(), block)
    }

    protected fun setup(config: Map<String, String>, block: suspend (Path, Path, DockerOperator, AwaitableEvenController, DockerAssertions, MockRecyclableChecker, Config) -> Unit) {
        runBlocking {
            // 1. cleanup docker containers
            deleteContainers()

            // 2. setup operator
            val dataDir = createTempDirectory("kubernetes-operator-data-it-")
            val inputDir = createTempDirectory("kubernetes-operator-it-")

            val mockConfig = MockConfig(config + mapOf(
                "SPO_DOCKER_DATA_DIR" to dataDir.toString(),
                "SPO_INPUT_DIR" to inputDir.toString(),
                "SPO_FILE_POLL_INTERVAL" to "10"
            ))
            val eventController = AwaitableEvenController()
            val mockRecyclableChecker = MockRecyclableChecker()
            val operator = DockerOperator(mockConfig, eventController, mockRecyclableChecker)
            eventController.setDelegate(EventController(operator.orchestrator))

            val dockerAssertions = DockerAssertions(this@IntegrationTestBase, dataDir, inputDir, operator.orchestrator)

            try {
                // 3. run test
                block(dataDir, inputDir, operator, eventController, dockerAssertions, mockRecyclableChecker, mockConfig)
            } finally {
                scope.cancel()
                // 4. stop operator
                operator.stop()
                // 4.  cleanup docker containers
                deleteContainers(operator.orchestrator.getRedisConfig().getContainerName(), operator.orchestrator.getCaddyConfig().getContainerName())
            }
        }

    }

    private fun deleteContainers(redisContainerName: String = "sp-redis", caddyContainerName: String = "sp-caddy") {
        stopAndRemoveContainer(getContainerByName(redisContainerName))
        stopAndRemoveContainer(getContainerByName(caddyContainerName))

        val containers = dockerClient
            .listContainers(DockerClient.ListContainersParam.allContainers())
            .filter { it.labels()["app"] == "shinyproxy" || it.labels()["app"] == "crane" || it.image() == "openanalytics/shinyproxy-integration-test-app" }

        containers.forEach { container -> stopAndRemoveContainer(container) }
        removeNetwork("sp-network-default-realm1")
        removeNetwork("sp-network-default-realm2")
    }

    fun inspectContainer(container: Container?): ContainerInfo? {
        if (container == null) {
            return null
        }
        return dockerClient.inspectContainer(container.id())
    }

    fun getContainerByName(name: String): Container? {
        val containers = dockerClient
            .listContainers(DockerClient.ListContainersParam.allContainers())
            .filter { it.names().any { containerName -> containerName == "/$name" } }
        if (containers.isEmpty()) {
            return null
        }
        if (containers.size > 1) {
            throw IllegalStateException("Found more than one '$name' container")
        }
        return containers[0]
    }

    private fun stopAndRemoveContainer(container: Container?) {
        if (container == null) {
            return
        }
        try {
            dockerClient.stopContainer(container.id(), 0)
            dockerClient.removeContainer(container.id())
        } catch (_: ContainerNotFoundException) {
        } catch (_: DockerException) {
        }
    }

    private fun removeNetwork(networkName: String) {
        try {
            dockerClient.removeNetwork("sp-network-default-realm2")
        } catch (_: NetworkNotFoundException) {

        }
    }

    protected fun createInputFile(inputDir: Path, resourceName: String, destinationName: String, replacements: Map<String, String> = mapOf()): String {
        var config = this.javaClass.getResource("/docker/${resourceName}")?.readText() ?: throw IllegalStateException("No or invalid resource file '${resourceName}'")
        for ((key, value) in replacements) {
            config = config.replace(key, value)
        }
        inputDir.resolve(destinationName).writeText(config)
        val spec = objectMapper.readValue<JsonNode>(config)
        return spec.convertToYamlString().sha1()
    }

    protected fun createRawInputFile(inputDir: Path, resourceName: String, destinationName: String, replacements: Map<String, String> = mapOf()): String {
        var config = this.javaClass.getResource("/docker/${resourceName}")?.readText() ?: throw IllegalStateException("No or invalid resource file '${resourceName}'")
        for ((key, value) in replacements) {
            config = config.replace(key, value)
        }
        inputDir.resolve(destinationName).createParentDirectories()
        inputDir.resolve(destinationName).writeText(config)
        return config
    }

    protected fun deleteInputFile(inputDir: Path, destinationName: String) {
        inputDir.resolve(destinationName).deleteIfExists()
    }

    protected fun getSingleShinyProxyContainer(shinyProxyInstance: ShinyProxyInstance): Container {
        val shinyProxyContainers = dockerActions.getContainers(shinyProxyInstance)
        assertEquals(1, shinyProxyContainers.size)
        return shinyProxyContainers[0]
    }

    protected suspend fun startApp(shinyProxyInstance: ShinyProxyInstance): String {
        val ip = getSingleShinyProxyContainer(shinyProxyInstance).getSharedNetworkIpAddress()!!
        val request = Request.Builder()
            .post(EMPTY_REQUEST)
            .url("http://$ip:8080/api/proxy/01_hello")
            .header("Authorization", "Basic ZGVtbzpkZW1v")
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string()
        assertEquals(201, response.code, "Failed to start app: ${response.code}, $body ")
        assertNotNull(body)
        val json = objectMapper.readValue<JsonNode>(body)
        val proxyId = json.get("data")!!.get("id").textValue()

        withTimeout(60_000) {
            while (true) {
                val statusRequest = Request.Builder()
                    .get()
                    .url("http://$ip:8080/api/proxy/${proxyId}/status")
                    .header("Authorization", "Basic ZGVtbzpkZW1v")
                    .build()

                val statusResponse = httpClient.newCall(statusRequest).execute()
                val statusBody = statusResponse.body?.string()
                assertEquals(200, statusResponse.code, "Failed to retrieve app status: ${statusResponse.code}, $statusBody ")
                assertNotNull(statusBody)
                val statusJson = objectMapper.readValue<JsonNode>(statusBody)
                if (statusJson.get("data")!!.get("status").textValue() == "Up") {
                    break
                }
                delay(1_000)
            }
        }
        return proxyId
    }

    protected fun assertAppIsRunning(shinyProxyInstance: ShinyProxyInstance, proxyId: String) {
        val ip = getSingleShinyProxyContainer(shinyProxyInstance).getSharedNetworkIpAddress()!!
        val request = Request.Builder()
            .get()
            .url("http://$ip:8080/api/proxy/${proxyId}")
            .header("Authorization", "Basic ZGVtbzpkZW1v")
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string()
        assertEquals(200, response.code, "Failed to retrieve app: ${response.code}, $body ")
    }

}
