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

import eu.openanalytics.shinyproxyoperator.impl.docker.CaddyConfig
import eu.openanalytics.shinyproxyoperator.impl.docker.DockerActions
import eu.openanalytics.shinyproxyoperator.model.ShinyProxy
import eu.openanalytics.shinyproxyoperator.readConfigValue
import org.mandas.docker.client.DockerClient
import java.nio.file.Path

class MonitoringConfig(dockerClient: DockerClient, dockerActions: DockerActions, mainDataDir: Path, caddyConfig: CaddyConfig) {

    private val enableMonitoring = readConfigValue(null, false, "SPO_ENABLE_MONITORING") { it.toBoolean() }

    private val grafanaAlloyConfig = GrafanaAlloyConfig(dockerClient, dockerActions, mainDataDir)
    private val grafanaLokiConfig = GrafanaLokiConfig(dockerClient, dockerActions, mainDataDir)
    private val prometheusConfig = PrometheusConfig(dockerClient, dockerActions, mainDataDir)
    private val cAdvisorConfig = CAdvisorConfig(dockerClient, dockerActions)
    internal val grafanaConfig = GrafanaConfig(dockerClient, dockerActions, mainDataDir, caddyConfig)

    suspend fun reconcile(shinyProxy: ShinyProxy) {
        if (enableMonitoring) {
            grafanaAlloyConfig.reconcile()
            grafanaLokiConfig.reconcile()
            prometheusConfig.reconcile()
            cAdvisorConfig.reconcile()
            grafanaConfig.reconcile(shinyProxy)
        }
    }

    fun isEnabled(): Boolean {
        return enableMonitoring
    }

}
