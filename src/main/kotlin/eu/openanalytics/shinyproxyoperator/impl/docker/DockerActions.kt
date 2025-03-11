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
package eu.openanalytics.shinyproxyoperator.impl.docker

import eu.openanalytics.shinyproxyoperator.LabelFactory
import eu.openanalytics.shinyproxyoperator.LabelFactory.APP_LABEL
import eu.openanalytics.shinyproxyoperator.LabelFactory.REALM_ID_LABEL
import eu.openanalytics.shinyproxyoperator.impl.docker.CraneConfig.Companion.CRANE_APP_LABEL_VALUE
import eu.openanalytics.shinyproxyoperator.impl.docker.CraneConfig.Companion.CRANE_INSTANCE_LABEL
import eu.openanalytics.shinyproxyoperator.model.ShinyProxy
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance
import mu.KotlinLogging
import org.mandas.docker.client.DockerClient
import org.mandas.docker.client.exceptions.ContainerNotFoundException
import org.mandas.docker.client.exceptions.NetworkNotFoundException
import org.mandas.docker.client.messages.Container
import org.mandas.docker.client.messages.NetworkConfig

class DockerActions(private val dockerClient: DockerClient) {

    private val logger = KotlinLogging.logger {}

    fun networkExists(name: String): Boolean {
        try {
            dockerClient.inspectNetwork(name)
            return true
        } catch (e: NetworkNotFoundException) {
            return false
        }
    }

    fun createNetwork(name: String) {
        dockerClient.createNetwork(
            NetworkConfig.builder()
                .name(name)
                .build()
        )
    }

    fun getContainerByName(name: String): Container? {
        val containers = dockerClient
            .listContainers(DockerClient.ListContainersParam.withStatusRunning())
            .filter { it.names().any { containerName -> containerName == "/$name" } }
        if (containers.isEmpty()) {
            return null
        }
        if (containers.size > 1) {
            throw IllegalStateException("Found more than one '$name' container")
        }
        return containers[0]
    }

    fun isContainerRunning(name: String, expectedImage: String? = null): Boolean {
        val containers = dockerClient
            .listContainers(DockerClient.ListContainersParam.withStatusRunning())
            .filter { it.names().any { containerName -> containerName == "/$name" } }
        if (containers.isEmpty()) {
            return false
        }
        if (containers.size > 1) {
            throw IllegalStateException("Found more than one '$name' container")
        }
        if (expectedImage != null && containers[0].image() != expectedImage) {
            return false
        }
        return true
    }

    fun getContainers(shinyProxyInstance: ShinyProxyInstance): List<Container> {
        val listParams = arrayListOf(
            DockerClient.ListContainersParam.allContainers(),
        )
        for ((key, value) in LabelFactory.labelsForShinyProxyInstance(shinyProxyInstance)) {
            listParams.add(DockerClient.ListContainersParam.withLabel(key, value))
        }
        val containers = dockerClient.listContainers(*listParams.toTypedArray())
        if (containers.isEmpty()) {
            return listOf()
        }
        return containers
    }

    fun exec(containerId: String, command: List<String>) {
        val exec = dockerClient.execCreate(containerId, command.toTypedArray())
        dockerClient.execStart(exec.id(), DockerClient.ExecStartParameter.DETACH)
    }

    fun stopAndRemoveContainer(container: Container): Boolean {
        try {
            dockerClient.stopContainer(container.id(), 30)
            dockerClient.removeContainer(container.id())
            return true
        } catch (_: ContainerNotFoundException) {
            return false
        }
    }

    /**
     * Stops and removes any container with the given name.
     */
    fun stopAndRemoveContainer(name: String) {
        val containers = dockerClient
            .listContainers(DockerClient.ListContainersParam.allContainers())
            .filter { it.names().any { containerName -> containerName == "/$name" } }
        for (container in containers) {
            logger.info { "Removing existing container, name: ${name}, id: ${container.shortId()}" }
            stopAndRemoveContainer(container)
        }
    }

    /**
     * Stops and removes any non-running container with the given name.
     */
    fun stopAndRemoveNotRunningContainer(name: String) {
        val containers = dockerClient
            .listContainers(DockerClient.ListContainersParam.allContainers())
            .filter { it.names().any { containerName -> containerName == "/$name" } }
        for (container in containers) {
            if (container.state().equals("running")) {
                continue
            }
            logger.info { "Removing existing (non-running) container, name: ${name}, id: ${container.shortId()}" }
            stopAndRemoveContainer(container)
        }
    }

    fun getCraneContainer(shinyProxy: ShinyProxy, hash: String, deletedContainers: List<String>): Container? {
        val listParams = arrayListOf(
            DockerClient.ListContainersParam.allContainers(),
        )
        for ((key, value) in labelsForCrane(shinyProxy.realmId, hash)) {
            listParams.add(DockerClient.ListContainersParam.withLabel(key, value))
        }
        val containers = dockerClient.listContainers(*listParams.toTypedArray())
            .filter { !deletedContainers.contains(it.id()) }
        if (containers.isEmpty()) {
            return null
        }
        if (containers.size > 1) {
            throw IllegalStateException("Found more than one container")
        }
        return containers[0]
    }

    fun getCraneContainers(realmId: String): List<Container> {
        val listParams = arrayListOf(
            DockerClient.ListContainersParam.allContainers(),
        )
        for ((key, value) in labelsForCrane(realmId)) {
            listParams.add(DockerClient.ListContainersParam.withLabel(key, value))
        }
        return dockerClient.listContainers(*listParams.toTypedArray())
    }

    fun labelsForCrane(realmId: String, hash: String? = null): Map<String, String> {
        val labels = hashMapOf(
            APP_LABEL to CRANE_APP_LABEL_VALUE,
            REALM_ID_LABEL to realmId,
        )
        if (hash != null) {
            labels[CRANE_INSTANCE_LABEL] = hash
        }
        return labels
    }

}
