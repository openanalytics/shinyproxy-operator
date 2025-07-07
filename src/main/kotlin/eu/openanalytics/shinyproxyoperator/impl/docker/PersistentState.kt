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
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path

class PersistentState(dataDir: Path) {

    private val objectMapper = ObjectMapper(YAMLFactory())
    private val logger = KotlinLogging.logger {}
    private val file = dataDir.resolve("state.yaml").toFile()

    init {
        objectMapper.registerKotlinModule()
    }

    fun readState(): State {
        try {
            if (!file.exists()) {
                return State(mapOf(), null)
            }
            return objectMapper.readValue(file)
        } catch (e: Exception) {
            logger.warn(e) { "Could not read store state" }
            return State(mapOf(), null)
        }
    }

    fun storeLatest(realmId: String, instance: String) {
        updateState { state ->
            val newMap = state.realms.toMutableMap()
            if (newMap.containsKey(realmId)) {
                newMap[realmId] = newMap.getValue(realmId).copy(latestInstance = instance)
            } else {
                newMap[realmId] = RealmState(instance)
            }
            state.copy(realms = newMap)
        }
    }

    fun storeLatestCrane(realmId: String, instance: String) {
        updateState { state ->
            val newMap = state.realms.toMutableMap()
            if (newMap.containsKey(realmId)) {
                newMap[realmId] = newMap.getValue(realmId).copy(craneLatestInstance = instance)
            } else {
                newMap[realmId] = RealmState(null, instance)
            }
            state.copy(realms = newMap)
        }
    }

    fun storeRedisPassword(password: String) {
        updateState { state ->
            state.copy(redisPassword = password)
        }
    }

    private fun updateState(block: (State) -> State) {
        try {
            val state = readState()
            val newState = block(state)
            objectMapper.writeValue(file, newState)
        } catch (e: Exception) {
            logger.warn(e) { "Could not store state" }
        }
    }

    data class State(val realms: Map<String, RealmState> = mapOf(), val redisPassword: String? = null)

    data class RealmState(val latestInstance: String?, val craneLatestInstance: String? = null)

}
