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
package eu.openanalytics.shinyproxyoperator.impl.docker.monitoring

import eu.openanalytics.shinyproxyoperator.Config
import eu.openanalytics.shinyproxyoperator.impl.docker.DockerActions
import eu.openanalytics.shinyproxyoperator.impl.docker.DockerOrchestrator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.mandas.docker.client.DockerClient
import org.mandas.docker.client.messages.ContainerConfig
import org.mandas.docker.client.messages.Device
import org.mandas.docker.client.messages.HostConfig

class CAdvisorConfig(private val dockerClient: DockerClient, private val dockerActions: DockerActions, config: Config, private val dockerSocket: String) {

    private val logger = KotlinLogging.logger {}
    private val cAdvisorImage: String = config.readConfigValue("gcr.io/cadvisor/cadvisor:v0.49.1", "SPO_CADVISOR_IMAGE") { it }
    private val containerName = "sp-cadvisor"

    fun reconcile() {
        if (dockerActions.isContainerRunning(containerName, cAdvisorImage)) {
            logger.info { "[cAdvisor] Ok" }
            return
        }
        logger.info { "[cAdvisor] Reconciling" }
        dockerActions.stopAndRemoveContainer(containerName)
        logger.info { "[cAdvisor] Pulling image" }
        dockerClient.pull(cAdvisorImage)

        val hostConfig = HostConfig.builder()
            .networkMode(DockerOrchestrator.SHARED_NETWORK_NAME)
            .binds(
                HostConfig.Bind.builder()
                    .from(dockerSocket)
                    .to("/var/run/docker.sock")
                    .readOnly(true)
                    .build(),
                HostConfig.Bind.builder()
                    .from("/")
                    .to("/rootfs")
                    .readOnly(true)
                    .build(),
                HostConfig.Bind.builder()
                    .from("/var/run")
                    .to("/var/run")
                    .readOnly(true)
                    .build(),
                HostConfig.Bind.builder()
                    .from("/sys")
                    .to("/sys")
                    .readOnly(true)
                    .build(),
                HostConfig.Bind.builder()
                    .from("/var/lib/docker")
                    .to("/var/lib/docker")
                    .readOnly(true)
                    .build(),
                HostConfig.Bind.builder()
                    .from("/dev/disk")
                    .to("/dev/disk")
                    .readOnly(true)
                    .build(),
            )
            .privileged(true)
            .restartPolicy(HostConfig.RestartPolicy.always())
            .devices(Device.builder().pathOnHost("/dev/kmsg").build())
            .build()

        val containerConfig = ContainerConfig.builder()
            .image(cAdvisorImage)
            .hostConfig(hostConfig)
            .labels(mapOf("app" to "cadvisor"))
            .cmd(listOf("--docker_only=true", "--enable_metrics=cpu,memory,network"))
            .build()

        logger.info { "[cAdvisor] Creating new container" }
        val containerId = dockerClient.createContainer(containerConfig, containerName).id()!!
        dockerClient.startContainer(containerId)
    }

}
