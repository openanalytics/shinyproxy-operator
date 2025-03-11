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
package eu.openanalytics.shinyproxyoperator.impl.docker.monitoring

import eu.openanalytics.shinyproxyoperator.FileManager
import eu.openanalytics.shinyproxyoperator.impl.docker.DockerActions
import eu.openanalytics.shinyproxyoperator.impl.docker.DockerOrchestrator
import eu.openanalytics.shinyproxyoperator.readConfigValue
import io.github.oshai.kotlinlogging.KotlinLogging
import org.mandas.docker.client.DockerClient
import org.mandas.docker.client.messages.ContainerConfig
import org.mandas.docker.client.messages.HostConfig
import java.nio.file.Path

class PrometheusConfig(private val dockerClient: DockerClient, private val dockerActions: DockerActions, mainDataDir: Path) {

    private val logger = KotlinLogging.logger {}
    private val dockerGID = readConfigValue(null, null, "SPO_DOCKER_GID") { it.toInt() }
    private val prometheusImage: String = readConfigValue(null, "prom/prometheus:v3.0.1", "SPO_PROMETHEUS_IMAGE") { it }
    private val fileManager = FileManager()
    private val containerName = "sp-prometheus"
    private val dataDir: Path = mainDataDir.resolve(containerName)

    init {
        fileManager.createDirectories(dataDir)
        fileManager.createDirectories(dataDir.resolve("prometheus"))
    }

    suspend fun reconcile() {
        val configUpdated = fileManager.writeFromResource("/configs/docker/monitoring/prometheus.yml", dataDir.resolve("prometheus.yml"))
        if (!configUpdated && dockerActions.isContainerRunning(containerName, prometheusImage)) {
            logger.info { "[Prometheus] Ok" }
            return
        }
        logger.info { "[Prometheus] Reconciling" }
        dockerActions.stopAndRemoveContainer(containerName)
        logger.info { "[Prometheus] Pulling image" }
        dockerClient.pull(prometheusImage)

        val hostConfig = HostConfig.builder()
            .networkMode(DockerOrchestrator.SHARED_NETWORK_NAME)
            .binds(
                HostConfig.Bind.builder()
                    .from("/var/run/docker.sock")
                    .to("/var/run/docker.sock")
                    .readOnly(true)
                    .build(),
                HostConfig.Bind.builder()
                    .from(dataDir.resolve("prometheus.yml").toString())
                    .to("/etc/prometheus/prometheus.yml")
                    .build(),
                HostConfig.Bind.builder()
                    .from(dataDir.resolve("prometheus").toString())
                    .to("/prometheus")
                    .build(),
            )
            .groupAdd(dockerGID.toString())
            .restartPolicy(HostConfig.RestartPolicy.always())
            .build()

        val containerConfig = ContainerConfig.builder()
            .image(prometheusImage)
            .hostConfig(hostConfig)
            .labels(mapOf("app" to "prometheus"))
            .user("1000")
            .build()

        logger.info { "[Prometheus] Creating new container" }
        val containerId = dockerClient.createContainer(containerConfig, containerName).id()!!
        dockerClient.startContainer(containerId)
    }

}
