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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import eu.openanalytics.shinyproxyoperator.Config
import eu.openanalytics.shinyproxyoperator.impl.docker.DockerOrchestrator
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance
import org.mandas.docker.client.messages.Container
import org.mandas.docker.client.messages.PortBinding
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DockerAssertions(private val base: IntegrationTestBase,
                       private val dataDir: Path,
                       private val inputDir: Path,
                       private val orchestrator: DockerOrchestrator) {

    private val objectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
    private val dockerGID = Config().readConfigValue(null, "SPO_DOCKER_GID") { it }
    private val dockerSocket = Config().readConfigValue("/var/run/docker.sock", "SPO_DOCKER_SOCKET") { it }
    private val redisContainerName = orchestrator.getRedisConfig().getContainerName()
    private val caddyContainerName = orchestrator.getCaddyConfig().getContainerName()

    fun assertRedisContainer() {
        val redisContainer = base.inspectContainer(this.base.getContainerByName(redisContainerName))
        assertNotNull(redisContainer)
        assertEquals(true, redisContainer.state().running())
        assertEquals("sp-shared-network", redisContainer.hostConfig().networkMode())
        assertEquals(listOf(
            "${dataDir}/sp-redis/data:/data",
            "${dataDir}/sp-redis/redis.conf:/etc/redis.conf",
        ), redisContainer.hostConfig().binds())
        assertEquals("always", redisContainer.hostConfig().restartPolicy().name())
        assertEquals(listOf("redis-server", "/etc/redis.conf"), redisContainer.config().cmd())
        assertEquals("redis", redisContainer.config().labels()["app"])

        // remove generated password from file
        val redisConfig = dataDir.resolve("sp-redis").resolve("redis.conf").readText().dropLast(33)
        val expectedRedisconfig = readExpectedFile("redis.conf").dropLast(20)
        assertEquals(expectedRedisconfig, redisConfig)
    }

    fun assertCaddyContainer(expectedName: String, replacements: Map<String, String>, tls: Boolean = false) {
        val caddyContainer = base.inspectContainer(this.base.getContainerByName(caddyContainerName))
        assertNotNull(caddyContainer)
        assertEquals(true, caddyContainer.state().running())
        assertEquals("sp-shared-network", caddyContainer.hostConfig().networkMode())
        assertEquals(listOf(
            "${dataDir}/sp-caddy/Caddyfile.json:/etc/caddy/Caddyfile.json",
            "${dataDir}/sp-caddy/data:/data",
            "${dataDir}/sp-caddy/config:/config",
            "${dataDir}/sp-caddy/certs:/certs",
        ), caddyContainer.hostConfig().binds())
        assertEquals("always", caddyContainer.hostConfig().restartPolicy().name())
        if (tls) {
            assertEquals(listOf(PortBinding.of("0.0.0.0", "443"), PortBinding.of("0.0.0.0", "80")), caddyContainer.hostConfig().portBindings().values.flatten())
        } else {
            assertEquals(listOf(PortBinding.of("0.0.0.0", "80")), caddyContainer.hostConfig().portBindings().values.flatten())
        }

        val caddyConfig = prettyPrintJson(dataDir.resolve("sp-caddy").resolve("Caddyfile.json").readText())
        assertEquals(prettyPrintJson(readExpectedFile(expectedName, replacements)), caddyConfig)
    }

    private fun readExpectedFile(fileName: String, replacements: Map<String, String> = mapOf()): String {
        var text = this.javaClass.getResource("/docker/expected/${fileName}")?.readText() ?: throw IllegalStateException("No or invalid expected file '${fileName}'")
        for ((key, value) in replacements) {
            text = text.replace(key, value)
        }
        return text
    }

    private fun prettyPrintJson(input: String): String {
        val value = objectMapper.readValue<Any>(input)
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)
    }

    private fun assertLabels(expected: Map<String, String?>, actual: Map<String, String>) {
        for ((key, value) in expected) {
            assertTrue(actual.containsKey(key), "No label '$key'")
            if (value != null) {
                assertEquals(value, actual[key], "Invalid value for label '$key'")
            }
        }
    }

    private fun assertEnv(expected: Map<String, String?>, actual: MutableList<String>) {
        val actualParsed = actual.associate { it.split("=", limit = 2).let { it[0] to it[1] } }
        for ((key, value) in expected) {
            assertTrue(actualParsed.containsKey(key), "No env '$key'")
            if (value != null) {
                assertEquals(value, actualParsed[key], "Invalid env for label '$key'")
            }
        }
    }

    fun assertShinyProxyContainer(shinyProxyContainer: Container, shinyProxyInstance: ShinyProxyInstance) {
        val containerInfo = base.dockerClient.inspectContainer(shinyProxyContainer.id())
        assertEquals(true, containerInfo.state().running())
        assertEquals("sp-shared-network", containerInfo.hostConfig().networkMode())
        assertEquals(listOf("sp-network-${shinyProxyInstance.realmId}", "sp-shared-network"), containerInfo.networkSettings().networks().keys.toList())
        assertEquals(listOf(
            "$dockerSocket:/var/run/docker.sock:ro",
            "${dataDir}${containerInfo.name()}/application.yml:/opt/shinyproxy/application.yml:ro",
            "${dataDir}${containerInfo.name()}/generated.yml:/opt/shinyproxy/generated.yml:ro",
            "${dataDir}${containerInfo.name()}/templates:/opt/shinyproxy/templates:ro",
            "${dataDir}/logs${containerInfo.name()}:/opt/shinyproxy/logs",
            "${dataDir}${containerInfo.name()}/termination-log:/dev/termination-log",
        ), containerInfo.hostConfig().binds())
        assertEquals(listOf(dockerGID), containerInfo.hostConfig().groupAdd())
        assertEquals("always", containerInfo.hostConfig().restartPolicy().name())
        assertLabels(mapOf(
            "app" to "shinyproxy",
            "openanalytics.eu/sp-instance" to shinyProxyInstance.hashOfSpec,
            "openanalytics.eu/sp-instance-revision" to shinyProxyInstance.revision.toString(),
            "openanalytics.eu/sp-realm-id" to shinyProxyInstance.realmId,
            "openanalytics.eu/sp-version" to null
        ), containerInfo.config().labels())
        assertEnv(mapOf(
            "PROXY_VERSION" to null,
            "PROXY_REALM_ID" to shinyProxyInstance.realmId,
            "SPRING_CONFIG_IMPORT" to "/opt/shinyproxy/generated.yml"
        ), containerInfo.config().env())
    }

}
