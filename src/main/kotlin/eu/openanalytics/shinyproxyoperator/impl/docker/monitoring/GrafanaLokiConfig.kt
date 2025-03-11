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

class GrafanaLokiConfig(private val dockerClient: DockerClient, private val dockerActions: DockerActions, mainDataDir: Path) {

    private val logger = KotlinLogging.logger {}
    private val lokiImage: String = readConfigValue(null, "grafana/loki:3.2.2", "SPO_GRAFANA_GRAFANA_IMAGE") { it }
    private val fileManager = FileManager()
    private val containerName = "sp-grafana-loki"
    private val dataDir: Path = mainDataDir.resolve(containerName)

    init {
        fileManager.createDirectories(dataDir)
        fileManager.createDirectories(dataDir.resolve("loki"))
    }

    suspend fun reconcile() {
        val configUpdated = fileManager.writeFromResource("/configs/docker/monitoring/loki-config.yaml", dataDir.resolve("loki-config.yaml"))
        if (!configUpdated && dockerActions.isContainerRunning(containerName, lokiImage)) {
            logger.info { "[Grafana Loki] Ok" }
            return
        }
        logger.info { "[Grafana Loki] Reconciling" }
        dockerActions.stopAndRemoveContainer(containerName)
        logger.info { "[Grafana Loki] Pulling image" }
        dockerClient.pull(lokiImage)

        val hostConfig = HostConfig.builder()
            .networkMode(DockerOrchestrator.SHARED_NETWORK_NAME)
            .binds(
                HostConfig.Bind.builder()
                    .from(dataDir.resolve("loki-config.yaml").toString())
                    .to("/etc/loki/local-config.yaml")
                    .build(),
                HostConfig.Bind.builder()
                    .from(dataDir.resolve("loki").toString())
                    .to("/loki")
                    .build(),
            )
            .restartPolicy(HostConfig.RestartPolicy.always())
            .build()

        val containerConfig = ContainerConfig.builder()
            .image(lokiImage)
            .hostConfig(hostConfig)
            .user("1000")
            .labels(mapOf("app" to "grafana-loki"))
            .build()

        logger.info { "[Grafana Loki] Creating new container" }
        val containerId = dockerClient.createContainer(containerConfig, containerName).id()!!
        dockerClient.startContainer(containerId)
    }

}
