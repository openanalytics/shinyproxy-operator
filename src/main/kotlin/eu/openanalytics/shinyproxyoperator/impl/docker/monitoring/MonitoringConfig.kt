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
import eu.openanalytics.shinyproxyoperator.impl.docker.CaddyConfig
import eu.openanalytics.shinyproxyoperator.impl.docker.DockerActions
import eu.openanalytics.shinyproxyoperator.model.ShinyProxy
import io.github.oshai.kotlinlogging.KotlinLogging
import org.mandas.docker.client.DockerClient
import java.nio.file.Path

class MonitoringConfig(dockerClient: DockerClient, dockerActions: DockerActions, mainDataDir: Path, dataDirUid: Int, caddyConfig: CaddyConfig, config: Config, dockerSocket: String) {

    private val logger = KotlinLogging.logger { }
    private val enableMonitoring: Boolean
    internal val grafanaLokiConfig = GrafanaLokiConfig(dockerClient, dockerActions, mainDataDir, dataDirUid, config)
    private val prometheusConfig = PrometheusConfig(dockerClient, dockerActions, mainDataDir, dataDirUid, config, dockerSocket)
    private val cAdvisorConfig = CAdvisorConfig(dockerClient, dockerActions, config, dockerSocket)
    internal val grafanaConfig = GrafanaConfig(dockerClient, dockerActions, mainDataDir, caddyConfig, config)

    init {
        val enableMonitoringConfig = config.readConfigValue(false, "SPO_ENABLE_MONITORING") { it.toBoolean() }
        if (enableMonitoringConfig && isPodman()) {
            logger.warn { "Monitoring is not supported on podman. Continuing without monitoring." }
            enableMonitoring = false
        } else {
            enableMonitoring = enableMonitoringConfig
        }
    }

    suspend fun reconcile(shinyProxy: ShinyProxy) {
        if (enableMonitoring) {
            grafanaLokiConfig.reconcile()
            prometheusConfig.reconcile()
            cAdvisorConfig.reconcile()
            grafanaConfig.reconcile(shinyProxy)
        }
    }

    fun isEnabled(): Boolean {
        return enableMonitoring
    }

    private fun isPodman(): Boolean {
        return System.getenv("container") == "podman"
    }

}
