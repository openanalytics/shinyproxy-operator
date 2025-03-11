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
package eu.openanalytics.shinyproxyoperator.impl.source

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import eu.openanalytics.shinyproxyoperator.IShinyProxySource
import eu.openanalytics.shinyproxyoperator.controller.EventController
import eu.openanalytics.shinyproxyoperator.event.ShinyProxyEvent
import eu.openanalytics.shinyproxyoperator.event.ShinyProxyEventType
import eu.openanalytics.shinyproxyoperator.impl.docker.DockerOrchestrator
import eu.openanalytics.shinyproxyoperator.logPrefix
import eu.openanalytics.shinyproxyoperator.model.ShinyProxy
import eu.openanalytics.shinyproxyoperator.readConfigValue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.DirectoryFileFilter
import org.apache.commons.io.filefilter.RegexFileFilter
import java.io.File
import kotlin.concurrent.timer

class FileSource(private val channel: Channel<ShinyProxyEvent>, private val orchestrator: DockerOrchestrator, private val eventController: EventController) : IShinyProxySource() {

    private val objectMapper = ObjectMapper(YAMLFactory())
    private val shinyProxies = mutableMapOf<String, ShinyProxy>()
    private val logger = KotlinLogging.logger {}
    private val dir = File(readConfigValue( "/opt/shinyproxy-docker-operator/input", "SPO_INPUT_DIR") { it })

    companion object {
        const val NAMESPACE = "default"
    }

    override suspend fun init() {
        runOnce()
        logger.info { "FileSource ready" }
    }

    override suspend fun run() {
        timer(period = 60_000L, initialDelay = 60_000L) {
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
        val files = FileUtils.listFiles(dir, RegexFileFilter("^.*\\.shinyproxy\\.(yml|yaml)$"), DirectoryFileFilter.DIRECTORY).sortedBy { it.name }

        var hasInputError = false
        val nameToFile = hashMapOf<String, String>()
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
                    logger.warn { "Found duplicate realmId: '${shinyProxy.realmId}' in file: '${nameToFile[realmId]}', already defined in '${file.name}'" }
                    hasInputError = true
                    continue
                }
                nameToFile[realmId] = file.name

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
                logger.warn(e) { "Failed to read file ${file.name}" }
                eventController.inputError("Failed to read file ${file.name}, error: ${e.message}")
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

}
