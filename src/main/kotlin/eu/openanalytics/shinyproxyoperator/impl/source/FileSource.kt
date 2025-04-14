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
package eu.openanalytics.shinyproxyoperator.impl.source

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import eu.openanalytics.shinyproxyoperator.Config
import eu.openanalytics.shinyproxyoperator.IEventController
import eu.openanalytics.shinyproxyoperator.IShinyProxySource
import eu.openanalytics.shinyproxyoperator.event.ShinyProxyEvent
import eu.openanalytics.shinyproxyoperator.event.ShinyProxyEventType
import eu.openanalytics.shinyproxyoperator.impl.docker.DockerOrchestrator
import eu.openanalytics.shinyproxyoperator.logPrefix
import eu.openanalytics.shinyproxyoperator.model.ShinyProxy
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.DirectoryFileFilter
import org.apache.commons.io.filefilter.RegexFileFilter
import java.nio.file.Path
import java.util.*
import kotlin.concurrent.timer

class FileSource(
    private val channel: Channel<ShinyProxyEvent>,
    config: Config,
    private val orchestrator: DockerOrchestrator,
    private val eventController: IEventController,
    private val inputDir: Path,
) : IShinyProxySource() {

    private val objectMapper = ObjectMapper(YAMLFactory())
    private val shinyProxies = mutableMapOf<String, ShinyProxy>()
    private val logger = KotlinLogging.logger {}
    private var timer: Timer? = null
    private val pollInterval: Int = config.readConfigValue(60, "SPO_FILE_POLL_INTERVAL") { it.toInt() }

    companion object {
        const val NAMESPACE = "default"
    }

    override suspend fun init() {
        objectMapper.enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
        runOnce()
        logger.info { "FileSource ready" }
    }

    override suspend fun run() {
        timer = timer(period = pollInterval * 1_000L, initialDelay = pollInterval * 1_000L) {
            runBlocking {
                try {
                    runOnce()
                } catch (e: Exception) {
                    logger.warn(e) { "Error while reading input files" }
                }
            }
        }
    }

    override suspend fun get(namespace: String, name: String): ShinyProxy? {
        return shinyProxies["${namespace}-${name}"]
    }

    private suspend fun runOnce() {
        // sort for deterministic ordering on each run
        val files = FileUtils.listFiles(inputDir.toFile(), RegexFileFilter("^.*\\.shinyproxy\\.(yml|yaml)$"), DirectoryFileFilter.DIRECTORY).sortedBy { it.name }

        var hasInputError = false
        val nameToFile = hashMapOf<String, String>()
        val urlToFile = hashMapOf<Pair<String, String>, String>()
        for (file in files) {
            try {
                val spec = objectMapper.readValue<JsonNode>(file)
                if (spec.get("proxy")?.get("realm-id")?.isTextual != true) {
                    throw IllegalStateException("No or invalid realm-id")
                }
                val name = spec.get("proxy").get("realm-id").textValue().lowercase()
                val shinyProxy = ShinyProxy(spec, name, "default")
                val realmId = shinyProxy.realmId

                // check for duplicate realms in files
                if (nameToFile.containsKey(realmId)) {
                    logger.warn { "Found duplicate realmId: '${shinyProxy.realmId}' in file: '${file.name}', already defined in '${nameToFile[realmId]}'" }
                    hasInputError = true
                    continue
                }
                nameToFile[realmId] = file.name

                if (!checkDuplicateUrl(urlToFile, shinyProxy, file.name)) {
                    hasInputError = true
                    continue
                }

                val existingShinyProxy = shinyProxies[shinyProxy.realmId]
                if (existingShinyProxy == null) {
                    logger.info { "${logPrefix(shinyProxy.realmId)} [Add]" }
                    shinyProxies[shinyProxy.realmId] = shinyProxy
                    channel.send(ShinyProxyEvent(ShinyProxyEventType.ADD, shinyProxy.realmId, name, NAMESPACE, null))
                } else {
                    if (existingShinyProxy.hashOfCurrentSpec == shinyProxy.hashOfCurrentSpec) {
                        logger.info { "${logPrefix(shinyProxy.realmId)} [Reconcile]" }
                        channel.send(ShinyProxyEvent(ShinyProxyEventType.RECONCILE, shinyProxy.realmId, name, NAMESPACE, shinyProxy.hashOfCurrentSpec))
                    } else {
                        logger.info { "${logPrefix(shinyProxy.realmId)} [Update]" }
                        shinyProxies[shinyProxy.realmId] = shinyProxy
                        channel.send(ShinyProxyEvent(ShinyProxyEventType.UPDATE_SPEC, shinyProxy.realmId, name, NAMESPACE, shinyProxy.hashOfCurrentSpec))
                    }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to read file '${file.name}'" }
                eventController.inputError("Failed to read file '${file.name}', error: '${e.message}'")
                hasInputError = true
            }
        }
        if (shinyProxies.isEmpty()) {
            logger.info { "No input files found" }
        }
        if (!hasInputError) {
            // only delete realms if all files were successfully processed
            checkForDeleted(nameToFile.keys)
        }
    }

    private fun checkDuplicateUrl(urlToFile: HashMap<Pair<String, String>, String>, shinyProxy: ShinyProxy, fileName: String): Boolean {
        val baseUrl = Pair(shinyProxy.fqdn, shinyProxy.subPath)
        if (urlToFile.containsKey(baseUrl)) {
            logger.warn { "Found multiple ShinyProxy resources with the same URL, fqdn: '${shinyProxy.fqdn}', path: '${shinyProxy.subPath}', realm: '${shinyProxy.realmId}' in file: '${fileName}', already defined in '${urlToFile[baseUrl]}'" }
            return false
        }
        urlToFile[baseUrl] = fileName
        for (additionalFqdn in shinyProxy.additionalFqdns) {
            val url = Pair(additionalFqdn, shinyProxy.subPath)
            if (urlToFile.containsKey(url)) {
                logger.warn { "Found multiple ShinyProxy resources with the same (additional) URL, additional fqdn: '${additionalFqdn}', path: '${shinyProxy.subPath}', realm: '${shinyProxy.realmId}' in file: '${fileName}', already defined in '${urlToFile[url]}'" }
                return false
            }
            urlToFile[url] =fileName
        }
        return true
    }

    private suspend fun checkForDeleted(discoveredShinyProxies: Collection<String>) {
        // list of all ShinyProxies currently being managed
        val allShinyProxies = (orchestrator.getShinyProxyStatuses().map { it.realmId } + shinyProxies.keys).toSet()
        for (realmId in allShinyProxies) {
            if (!discoveredShinyProxies.contains(realmId)) {
                // this realm no longer exists -> delete it
                logger.info { "${logPrefix(realmId)} [Delete]" }
                channel.send(ShinyProxyEvent(ShinyProxyEventType.DELETE, realmId, realmId.removePrefix(NAMESPACE), NAMESPACE, null))
                shinyProxies.remove(realmId)
            }
        }
    }

    fun stop() {
        timer?.cancel()
    }

}
