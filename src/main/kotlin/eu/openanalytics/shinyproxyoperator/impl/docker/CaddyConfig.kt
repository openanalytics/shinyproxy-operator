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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.datatype.jsr353.JSR353Module
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import eu.openanalytics.shinyproxyoperator.Config
import eu.openanalytics.shinyproxyoperator.FileManager
import eu.openanalytics.shinyproxyoperator.logPrefix
import eu.openanalytics.shinyproxyoperator.model.ShinyProxy
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.mandas.docker.client.DockerClient
import org.mandas.docker.client.messages.ContainerConfig
import org.mandas.docker.client.messages.HostConfig
import org.mandas.docker.client.messages.PortBinding
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

class CaddyConfig(private val dockerClient: DockerClient, mainDataDir: Path, config: Config) {

    private val containerName: String = config.readConfigValue("sp-caddy", "SPO_CADDY_CONTAINER_NAME") { it }
    private val dataDir: Path = mainDataDir.resolve(containerName)
    private val shinyProxies = mutableMapOf<String, Pair<ShinyProxy, ShinyProxyInstance>>()
    private val craneServers = hashMapOf<String, CraneServer>()
    private val dockerActions = DockerActions(dockerClient, config)
    private val logger = KotlinLogging.logger {}
    private val objectMapper = ObjectMapper()
    private val yamlMapper = ObjectMapper(YAMLFactory())
    private val fileManager = FileManager()
    private val caddyImage: String = config.readConfigValue("docker.io/library/caddy:2.8", "SPO_CADDY_IMAGE") { it }
    private val enableTls = config.readConfigValue(false, "SPO_CADDY_ENABLE_TLS") { it.toBoolean() }
    private val caddyPortHttp: Int = config.readConfigValue(80, "SPO_CADDY_PORT_HTTP") { it.toInt() }
    private val caddyPortHttps: Int = config.readConfigValue(443, "SPO_CADDY_PORT_HTTPS") { it.toInt() }
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    // 120 seconds
    companion object {
        private const val MAX_CHECKS = 24
    }

    init {
        objectMapper.registerModule(JSR353Module())
        yamlMapper.registerModule(JSR353Module()).registerKotlinModule()
        fileManager.createDirectories(dataDir)
        fileManager.createDirectories(dataDir.resolve("certs"))
    }

    fun getContainerName(): String {
        return containerName
    }

    suspend fun removeRealm(realmId: String) {
        shinyProxies.remove(realmId)
        reconcile()
    }

    fun isTlsEnabled(): Boolean {
        return enableTls
    }

    fun addCraneServer(realmId: String, ip: String, subPath: String) {
        craneServers[realmId] = CraneServer(ip, subPath)
    }

    fun addShinyProxy(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance) {
        shinyProxies[shinyProxy.realmId] = Pair(shinyProxy, shinyProxyInstance)
    }

    suspend fun reconcile() {
        reconcileContainer(objectMapper.writeValueAsString((mapOf(
            "apps" to mapOf("http" to mapOf("servers" to mapOf("shinyproxy" to generateServer())), "tls" to generateTls())
        ))))
    }

    private fun generateServer(): Map<String, Any> {
        val listen = if (enableTls) listOf(":443") else listOf(":80")
        return mapOf("listen" to listen, "routes" to generateRoutes(), "tls_connection_policies" to generateTlsConnectionPolicies())
    }

    private fun generateRoutes(): List<Map<String, Any>> {
        return shinyProxies.values.flatMap { (shinyProxy, latestShinyProxyInstance) ->
            listOf(generateShinyProxyConfig(shinyProxy, latestShinyProxyInstance), generateCraneConfig(shinyProxy))
        }
            .filterNotNull()
            .sortedByDescending { it.second } // sort by longest sub-path first
            .map { it.first }
    }

    private fun generateShinyProxyConfig(shinyProxy: ShinyProxy, latestShinyProxyInstance: ShinyProxyInstance): Pair<Map<String, Any>, Int>? {
        val ipAddress = dockerActions.getContainers(latestShinyProxyInstance).map { it.getSharedNetworkIpAddress() }
        if (ipAddress.isEmpty()) {
            logger.warn { "No ip address found for realm ${shinyProxy.realmId}" }
            return null
        }
        return mapOf(
            "handle" to listOf(
                mapOf(
                    "handler" to "subroute",
                    "routes" to generateRedirects(shinyProxy) + listOf(mapOf(
                        "handle" to listOf(mapOf(
                            "handler" to "reverse_proxy",
                            "stream_close_delay" to 172800000000000,
                            "upstreams" to ipAddress.map { mapOf("dial" to "${it}:8080") }
                        ))
                    ))
                )
            ),
            "match" to listOf(
                mapOf("host" to shinyProxy.allFqdns, "path" to listOf(shinyProxy.subPath + "*")),
            ),
            "terminal" to true
        ) to shinyProxy.subPath.count { it == '/' }
    }

    private fun generateCraneConfig(shinyProxy: ShinyProxy): Pair<Map<String, Any>, Int>? {
        val craneServer = craneServers[shinyProxy.realmId] ?: return null
        return mapOf(
            "handle" to listOf(
                mapOf(
                    "handler" to "subroute",
                    "routes" to listOf(mapOf(
                        "handle" to listOf(mapOf(
                            "handler" to "reverse_proxy",
                            "stream_close_delay" to 172800000000000,
                            "upstreams" to listOf(mapOf("dial" to "${craneServer.ip}:8080"))
                        ))
                    ))
                )
            ),
            "match" to listOf(
                mapOf("host" to shinyProxy.allFqdns, "path" to listOf(craneServer.subPath + "*")),
            ),
            "terminal" to true
        ) to craneServer.subPath.count { it == '/' }
    }

    private fun generateRedirects(shinyProxy: ShinyProxy): List<Map<String, Any>> {
        try {
            return shinyProxy.getCaddyRedirects().mapNotNull {
                val to = if (it.to.startsWith("/") || it.to.startsWith("http://") || it.to.startsWith("https://")) {
                    it.to
                } else {
                    shinyProxy.subPath + it.to
                }
                if (it.from.startsWith("http://") || it.from.startsWith("https")) {
                    logger.warn { "${logPrefix(shinyProxy)} Invalid 'from' in redirect: '${it.from}', must be a path" }
                    return@mapNotNull null
                }
                val from = if (it.from.startsWith("/")) {
                    shinyProxy.subPath.dropLast(1) + it.from
                } else {
                    shinyProxy.subPath + it.from
                }
                return@mapNotNull  mapOf(
                    "handle" to listOf(
                        mapOf(
                            "handler" to "rewrite",
                            "strip_path_prefix" to shinyProxy.subPath.dropLast(1)
                        ),
                        mapOf(
                            "handler" to "static_response",
                            "headers" to mapOf("Location" to listOf(to)),
                            "status_code" to it.statusCode
                        )),
                    "match" to listOf(mapOf("path" to listOf(from)))
                ) to from.count { c -> c == '/' }
            }
            .sortedByDescending { it.second } // sort by longest sub-path first
            .map { it.first }
        } catch (e: Exception) {
            logger.error(e) { "Failed to generate redirect rules" }
            return listOf()
        }
    }

    private fun generateTls(): Map<String, Any> {
        val loadFiles = arrayListOf<Map<String, Any>>()
        val fqdnsWithTls = hashSetOf<String>()
        val fqdnsWithAutomaticTls = hashSetOf<String>()
        for (shinyProxy in shinyProxies.values) {
            val crt = shinyProxy.first.getCaddyTlsCertFile()
            val key = shinyProxy.first.getCaddyTlsKeyFile()
            val fqdn = shinyProxy.first.fqdn
            if (crt != null && key != null) {
                for (f in shinyProxy.first.allFqdns) {
                    if (fqdnsWithTls.contains(f)) {
                        logger.warn { "FQDN '$f' already has TLS certificates configured (by a different realm), ignoring configuration in '${shinyProxy.first.realmId}' realm" }
                        continue
                    }
                    if (fqdnsWithAutomaticTls.contains(f)) {
                        logger.warn { "FQDN '$f' already has automatic TLS configured (by a different realm), ignoring TLS configuration in '${shinyProxy.first.realmId}' realm" }
                        continue
                    }
                }
                // use existing TLS certificates
                Files.copy(crt, dataDir.resolve("certs").resolve("$fqdn.crt.pem"), StandardCopyOption.REPLACE_EXISTING)
                Files.copy(key, dataDir.resolve("certs").resolve("$fqdn.key.pem"), StandardCopyOption.REPLACE_EXISTING)
                loadFiles.add(mapOf(
                    "certificate" to "/certs/$fqdn.crt.pem",
                    "key" to "/certs/$fqdn.key.pem",
                    "tags" to listOf(fqdn)
                ))
                fqdnsWithTls.addAll(shinyProxy.first.allFqdns)
            } else {
                for (f in shinyProxy.first.allFqdns) {
                    if (fqdnsWithTls.contains(f)) {
                        logger.warn { "FQDN '$f' already has TLS certificates configured (by a different realm), not generating automatic certificates in '${shinyProxy.first.realmId}' realm" }
                        continue
                    }
                }
                // use automatic TLS certificates
                fqdnsWithAutomaticTls.addAll(shinyProxy.first.allFqdns)
            }
        }
        return mapOf("certificates" to mapOf("load_files" to loadFiles, "automate" to fqdnsWithAutomaticTls))
    }

    private fun generateTlsConnectionPolicies(): List<Map<String, Any>> {
        val policies = arrayListOf<Map<String, Any>>()
        for (shinyProxy in shinyProxies.values.sortedBy { it.first.realmId }) {
            val crt = shinyProxy.first.getCaddyTlsCertFile()
            val key = shinyProxy.first.getCaddyTlsKeyFile()
            if (crt != null && key != null) {
                // use existing TLS certificates
                policies.add(mapOf(
                    "match" to mapOf("sni" to shinyProxy.first.allFqdns),
                    "certificate_selection" to mapOf("any_tag" to listOf(shinyProxy.first.fqdn))
                ))
            } else {
                // use automatic TLS certificates
                policies.add(mapOf(
                    "match" to mapOf("sni" to shinyProxy.first.allFqdns),
                ))
            }
        }
        return policies
    }

    private suspend fun reconcileContainer(config: String) {
        // 1. write config
        withContext(Dispatchers.IO) {
            fileManager.writeFile(dataDir.resolve("Caddyfile.json"), config)
        }

        // 2. create caddy container
        dockerActions.stopAndRemoveNotRunningContainer(containerName)
        var containerId = dockerActions.getContainerByName(containerName)?.id()
        if (containerId == null) {
            logger.info { "[Caddy] Pulling image" }
            dockerActions.pullImage(caddyImage)

            val portBindings = if (enableTls) {
                mapOf(
                    "80" to listOf(PortBinding.of("0.0.0.0", caddyPortHttp.toString())),
                    "443" to listOf(PortBinding.of("0.0.0.0", caddyPortHttps.toString()))
                )
            } else {
                mapOf("80" to listOf(PortBinding.of("0.0.0.0", caddyPortHttp.toString())))
            }

            val ports = if (enableTls) listOf("80", "443") else listOf("80")

            val hostConfig = HostConfig.builder()
                .networkMode(DockerOrchestrator.SHARED_NETWORK_NAME)
                .binds(HostConfig.Bind.builder()
                    .from(dataDir.resolve("Caddyfile.json").toString())
                    .to("/etc/caddy/Caddyfile.json")
                    .build(),
                    HostConfig.Bind.builder()
                        .from(dataDir.resolve("data").toString())
                        .to("/data")
                        .build(),
                    HostConfig.Bind.builder()
                        .from(dataDir.resolve("config").toString())
                        .to("/config")
                        .build(),
                    HostConfig.Bind.builder()
                        .from(dataDir.resolve("certs").toString())
                        .to("/certs")
                        .build()
                ).portBindings(portBindings)
                .restartPolicy(HostConfig.RestartPolicy.always())
                .build()

            val containerConfig = ContainerConfig.builder()
                .image(caddyImage)
                .hostConfig(hostConfig)
                .labels(mapOf("app" to "caddy"))
                .cmd(listOf("caddy", "run", "--config", "/etc/caddy/Caddyfile.json"))
                .exposedPorts(ports)
                .build()

            logger.info { "[Caddy] Creating new container" }
            containerId = dockerClient.createContainer(containerConfig, containerName).id()!!
            dockerClient.startContainer(containerId)

            // 3. wait for container to startup
            waitUntilCaddyReady()
        } else {
            logger.info { "[Caddy] Reloading config" }
            dockerActions.exec(containerId, listOf("caddy", "reload", "--config", "/etc/caddy/Caddyfile.json"))
        }
    }

    private suspend fun waitUntilCaddyReady() {
        for (checks in 0..24) {
            if (check()) {
                logger.info { "[Caddy] Ready (${checks}/${MAX_CHECKS})" }
                return
            }
            logger.info { "[Caddy] Not ready yet (${checks}/${MAX_CHECKS})" }
            delay(500)
        }
        throw IllegalStateException("Caddy failed")
    }

    private fun check(): Boolean {
        val ip = dockerActions.getContainerByName(containerName)?.getSharedNetworkIpAddress() ?: return false

        val url = "http://${ip}/"
        val request = Request.Builder()
            .url(url)
            .build()

        try {
            val resp = client.newCall(request).execute()
            return resp.isSuccessful || resp.isRedirect
        } catch (e: IOException) {
            return false
        }
    }

    data class CraneServer(val ip: String, val subPath: String)

}
