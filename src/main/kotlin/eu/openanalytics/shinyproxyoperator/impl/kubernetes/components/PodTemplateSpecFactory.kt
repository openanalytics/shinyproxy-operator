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
package eu.openanalytics.shinyproxyoperator.impl.kubernetes.components

import eu.openanalytics.shinyproxyoperator.Config
import eu.openanalytics.shinyproxyoperator.LabelFactory
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.getAntiAffinityRequired
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.getAntiAffinityTopologyKey
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.getParsedKubernetesPodTemplateSpecPatches
import eu.openanalytics.shinyproxyoperator.model.ShinyProxy
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance
import eu.openanalytics.shinyproxyoperator.prettyMessage
import io.fabric8.kubernetes.api.model.Affinity
import io.fabric8.kubernetes.api.model.AffinityBuilder
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.PodTemplateSpec
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ResourceRequirements
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder

class PodTemplateSpecFactory(config: Config) {

    private val podTemplatePatcher = Patcher()

    private val probeInitialDelay = config.readConfigValue(0, "SPO_PROBE_INITIAL_DELAY", String::toInt)
    private val probeFailureThreshold = config.readConfigValue(0, "SPO_PROBE_FAILURE_THRESHOLD", String::toInt)
    private val probeTimeout = config.readConfigValue(1, "SPO_PROBE_TIMEOUT", String::toInt)
    private val startupProbeInitialDelay = config.readConfigValue(60, "SPO_STARTUP_PROBE_INITIAL_DELAY", String::toInt)

    fun create(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): PodTemplateSpec {
        val version = if (shinyProxyInstance.hashOfSpec == shinyProxy.hashOfCurrentSpec) {
            System.currentTimeMillis()
        } else {
            0
        }

        //@formatter:off
        val template = PodTemplateSpecBuilder()
                .withNewMetadata()
                    .withGenerateName(ResourceNameFactory.createNameForPod(shinyProxy, shinyProxyInstance))
                    .withNamespace(shinyProxy.namespace)
                    .withLabels<String, String>(shinyProxy.labels + LabelFactory.labelsForShinyProxyInstance(shinyProxyInstance, version))
                .endMetadata()
                .withNewSpec()
                    .addNewContainer()
                        .withName("shinyproxy")
                        .withImage(shinyProxy.image)
                        .withImagePullPolicy(shinyProxy.imagePullPolicy)
                        .addNewPort()
                            .withContainerPort(8080)
                            .withName("http")
                            .withProtocol("TCP")
                        .endPort()
                        .addNewPort()
                            .withContainerPort(9090)
                            .withName("actuator")
                            .withProtocol("TCP")
                        .endPort()
                        .withEnv(listOf(
                            EnvVarBuilder()
                                .withName("SP_KUBE_POD_NAME")
                                .withNewValueFrom()
                                    .withNewFieldRef()
                                        .withFieldPath("metadata.name")
                                    .endFieldRef()
                                .endValueFrom()
                            .build(),
                            EnvVarBuilder()
                                .withName("SP_KUBE_POD_UID")
                                .withNewValueFrom()
                                    .withNewFieldRef()
                                        .withFieldPath("metadata.uid")
                                    .endFieldRef()
                                .endValueFrom()
                            .build(),
                            EnvVarBuilder()
                                .withName("PROXY_REALM_ID")
                                .withValue(shinyProxy.realmId)
                            .build(),
                            EnvVarBuilder()
                                .withName("PROXY_VERSION")
                                .withValue(version.toString())
                            .build()))
                        .withResources(createResources(shinyProxy))
                        .withVolumeMounts(VolumeMountBuilder()
                            .withName("config-volume")
                            .withMountPath("/opt/shinyproxy/application.yml")
                            .withSubPath("application.yml")
                        .build())
                        .withNewLivenessProbe()
                            .withNewHttpGet()
                                .withPath("/actuator/health/liveness")
                                .withNewPort(9090)
                            .endHttpGet()
                            .withPeriodSeconds(1)
                            .withInitialDelaySeconds(probeInitialDelay)
                            .withFailureThreshold(probeFailureThreshold)
                            .withTimeoutSeconds(probeTimeout)
                        .endLivenessProbe()
                        .withNewReadinessProbe()
                            .withNewHttpGet()
                                .withPath("/actuator/health/readiness")
                                .withNewPort(9090)
                            .endHttpGet()
                            .withPeriodSeconds(1)
                            .withInitialDelaySeconds(probeInitialDelay)
                            .withFailureThreshold(probeFailureThreshold)
                            .withTimeoutSeconds(probeTimeout)
                        .endReadinessProbe()
                        .withNewStartupProbe()
                            .withNewHttpGet()
                                .withPath("/actuator/health/liveness")
                                .withNewPort(9090)
                                .endHttpGet()
                            .withTimeoutSeconds(probeTimeout)
                            .withInitialDelaySeconds(startupProbeInitialDelay)
                            .withFailureThreshold(6)
                            .withPeriodSeconds(5)
                        .endStartupProbe()
                        .withTerminationMessagePolicy("FallbackToLogsOnError")
                    .endContainer()
                    .withNewDnsConfig()
                        .addToNameservers(*shinyProxy.dns.toTypedArray())
                    .endDnsConfig()
                    .withAffinity(createAffinity(shinyProxy, shinyProxyInstance))
                    .withVolumes(VolumeBuilder()
                            .withName("config-volume")
                            .withConfigMap(ConfigMapVolumeSourceBuilder()
                                    .withName(ResourceNameFactory.createNameForConfigMap(shinyProxy, shinyProxyInstance))
                                    .build())
                            .build())
                    .endSpec()
                .build()
        //@formatter:on

        return podTemplatePatcher.patch(template, shinyProxy.getParsedKubernetesPodTemplateSpecPatches())
    }

    private fun createAffinity(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): Affinity {
        if (shinyProxy.getAntiAffinityRequired()) {
            //@formatter:off
            return AffinityBuilder()
                .withNewPodAntiAffinity()
                    .addNewRequiredDuringSchedulingIgnoredDuringExecution()
                        .withNewLabelSelector()
                            .withMatchLabels<String, String>(LabelFactory.labelsForShinyProxyInstance(shinyProxyInstance))
                        .endLabelSelector()
                        .withTopologyKey(shinyProxy.getAntiAffinityTopologyKey())
                    .endRequiredDuringSchedulingIgnoredDuringExecution()
                .endPodAntiAffinity()
                .build()
            //@formatter:on
        } else {
            //@formatter:off
            return AffinityBuilder()
                .withNewPodAntiAffinity()
                    .addNewPreferredDuringSchedulingIgnoredDuringExecution()
                        .withWeight(1)
                        .withNewPodAffinityTerm()
                            .withNewLabelSelector()
                                .withMatchLabels<String, String>(LabelFactory.labelsForShinyProxyInstance(shinyProxyInstance))
                            .endLabelSelector()
                        .withTopologyKey(shinyProxy.getAntiAffinityTopologyKey())
                        .endPodAffinityTerm()
                    .endPreferredDuringSchedulingIgnoredDuringExecution()
                .endPodAntiAffinity()
                .build()
            //@formatter:on
        }
    }

    private fun createResources(shinyProxy: ShinyProxy): ResourceRequirements? {
        val resourceBuilder = ResourceRequirementsBuilder()
        if (shinyProxy.memoryRequest != null) {
            try {
                resourceBuilder.addToRequests("memory", parseMemorQuantity(shinyProxy.memoryRequest!!))
            } catch (e: Exception) {
                throw RuntimeException("Invalid memoryRequest: " + e.prettyMessage(), e)
            }
        }
        if (shinyProxy.memoryLimit != null) {
            try {
                resourceBuilder.addToLimits("memory", parseMemorQuantity(shinyProxy.memoryLimit!!))
            } catch (e: Exception) {
                throw RuntimeException("Invalid memoryLimit: " + e.prettyMessage(), e)
            }
        }
        if (shinyProxy.cpuRequest != null) {
            try {
                resourceBuilder.addToRequests("cpu", parseCpuQuantity(shinyProxy.cpuRequest!!))
            } catch (e: Exception) {
                throw RuntimeException("Invalid cpuRequest: " + e.prettyMessage(), e)
            }
        }
        if (shinyProxy.cpuLimit != null) {
            try {
                resourceBuilder.addToLimits("cpu", parseCpuQuantity(shinyProxy.cpuLimit!!))
            } catch (e: Exception) {
                throw RuntimeException("Invalid cpuLimit: " + e.prettyMessage(), e)
            }
        }
        return resourceBuilder.build()
    }

    private fun parseCpuQuantity(value: String): Quantity {
        val quantity = Quantity(value)
        quantity.numericalAmount // validates the quantity
        if (quantity.format != "m" && quantity.format != "") {
            throw RuntimeException("Invalid format for CPU resources")
        }
        return quantity
    }

    private fun parseMemorQuantity(value: String): Quantity {
        val quantity = Quantity(value)
        quantity.numericalAmount // validates the quantity
        if (quantity.format == "m" || quantity.format == "") {
            throw RuntimeException("Invalid format for memory resources")
        }
        return quantity
    }

}
