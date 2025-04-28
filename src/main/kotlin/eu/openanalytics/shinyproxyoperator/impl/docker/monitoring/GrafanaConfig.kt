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
import eu.openanalytics.shinyproxyoperator.FileManager
import eu.openanalytics.shinyproxyoperator.LabelFactory.REALM_ID_LABEL
import eu.openanalytics.shinyproxyoperator.impl.docker.CaddyConfig
import eu.openanalytics.shinyproxyoperator.impl.docker.DockerActions
import eu.openanalytics.shinyproxyoperator.impl.docker.DockerOrchestrator
import eu.openanalytics.shinyproxyoperator.logPrefix
import eu.openanalytics.shinyproxyoperator.model.ShinyProxy
import io.github.oshai.kotlinlogging.KotlinLogging
import org.mandas.docker.client.DockerClient
import org.mandas.docker.client.messages.ContainerConfig
import org.mandas.docker.client.messages.HostConfig
import java.nio.file.Path

class GrafanaConfig(private val dockerClient: DockerClient, private val dockerActions: DockerActions, private val mainDataDir: Path, private val caddyConfig: CaddyConfig, config: Config) {

    private val logger = KotlinLogging.logger {}
    private val grafanaImage: String = config.readConfigValue("docker.io/grafana/grafana-oss:11.5.1", "SPO_GRAFANA_IMAGE") { it }
    private val grafanaRole: String = config.readConfigValue("Viewer", "SPO_GRAFANA_ROLE") { it }
    private val fileManager = FileManager()

    suspend fun reconcile(shinyProxy: ShinyProxy) {
        if (shinyProxy.getSpec().get("proxy")?.get("authentication")?.isTextual == true
            && shinyProxy.getSpec().get("proxy")?.get("authentication")?.textValue()?.equals("none", ignoreCase = true) == true
        ) {
            removeRealm(shinyProxy.realmId)
            return
        }

        val containerName = "sp-grafana-grafana-${shinyProxy.realmId}"
        fileManager.createDirectories(mainDataDir.resolve(containerName))
        fileManager.createDirectories(mainDataDir.resolve(containerName).resolve("datasources"))
        fileManager.createDirectories(mainDataDir.resolve(containerName).resolve("dashboards"))

        val configUpdated = fileManager.writeFromResources(
            "/configs/docker/monitoring/grafana/",
            listOf(
                "datasources/datasources.yaml",
                "dashboards/dashboards.yaml",
                "dashboards/shinyproxy-aggregated-usage.json",
                "dashboards/shinyproxy-app-logs.json",
                "dashboards/shinyproxy-app-resources.json",
                "dashboards/shinyproxy-delegate-app-logs.json",
                "dashboards/shinyproxy-delegate-app-resources.json",
                "dashboards/shinyproxy-logs.json",
                "dashboards/shinyproxy-operator-logs.json",
                "dashboards/shinyproxy-seats.json",
                "dashboards/shinyproxy-usage.json"
            ),
            mainDataDir.resolve(containerName),
            false
        )

        if (!configUpdated && dockerActions.isContainerRunning(containerName, grafanaImage)) {
            logger.info { "${logPrefix(shinyProxy)} [Grafana] Ok" }
            return
        }

        logger.info { "[Grafana] Reconciling" }
        dockerActions.stopAndRemoveContainer(containerName)
        logger.info { "${logPrefix(shinyProxy)} [Grafana] Pulling image" }
        dockerClient.pull(grafanaImage)

        val hostConfig = HostConfig.builder()
            .networkMode(DockerOrchestrator.SHARED_NETWORK_NAME)
            .binds(
                HostConfig.Bind.builder()
                    .from(mainDataDir.resolve(containerName).resolve("datasources").toString())
                    .to("/etc/grafana/provisioning/datasources")
                    .build(),
                HostConfig.Bind.builder()
                    .from(mainDataDir.resolve(containerName).resolve("dashboards").toString())
                    .to("/etc/grafana/provisioning/dashboards")
                    .build()
            )
            .restartPolicy(HostConfig.RestartPolicy.always())
            .build()


        val serverRoot = if (caddyConfig.isTlsEnabled()) {
            "https://" + shinyProxy.fqdn + shinyProxy.subPath + "grafana/"
        } else {
            "http://" + shinyProxy.fqdn + shinyProxy.subPath + "grafana/"
        }

        val containerConfig = ContainerConfig.builder()
            .image(grafanaImage)
            .hostConfig(hostConfig)
            .labels(mapOf(
                "app" to "grafana-loki",
                REALM_ID_LABEL to shinyProxy.realmId
            ))
            .env(
                "GF_SECURITY_ALLOW_EMBEDDING=true",
                "GF_SERVER_SERVE_FROM_SUB_PATH=true",
                "GF_SERVER_ROOT_URL=$serverRoot",
                "GF_AUTH_PROXY_ENABLED=true",
                "GF_AUTH_PROXY_HEADER_NAME=X-SP-UserId",
                "GF_AUTH_PROXY_AUTO_SIGN_UP=true",
                "GF_USERS_AUTO_ASSIGN_ORG_ROLE=${grafanaRole}",
                "GF_USERS_DEFAULT_THEME=system",
                "GF_UNIFIED_ALERTING_ENABLED=false",
                "GF_AUTH_DISABLE_SIGNOUT_MENU=true")
            .build()

        logger.info { "${logPrefix(shinyProxy)} Creating new container" }
        val containerId = dockerClient.createContainer(containerConfig, containerName).id()!!
        dockerClient.startContainer(containerId)
    }

    fun getGrafanaUrl(shinyProxy: ShinyProxy): String {
        return "http://sp-grafana-grafana-${shinyProxy.realmId}:3000${shinyProxy.subPath}grafana/"
    }

    fun removeRealm(realmId: String) {
        dockerActions.stopAndRemoveContainer("sp-grafana-grafana-${realmId}")
    }

}
