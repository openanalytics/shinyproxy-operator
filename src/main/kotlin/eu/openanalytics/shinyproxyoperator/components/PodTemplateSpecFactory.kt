/**
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
package eu.openanalytics.shinyproxyoperator.components

import eu.openanalytics.shinyproxyoperator.Operator
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance
import io.fabric8.kubernetes.api.model.*

class PodTemplateSpecFactory {

    private val podTemplatePatcher = Patcher()

    fun create(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): PodTemplateSpec {

        val operator = Operator.getOperatorInstance()

        val version = if (shinyProxyInstance.hashOfSpec == shinyProxy.hashOfCurrentSpec) {
            System.currentTimeMillis()
        } else {
            0
        }

        //@formatter:off
        val template = PodTemplateSpecBuilder()
                .withNewMetadata()
                    .withGenerateName(ResourceNameFactory.createNameForPod(shinyProxy, shinyProxyInstance))
                    .withNamespace(shinyProxy.metadata.namespace)
                    .withLabels<String, String>(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance))
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
                            .withInitialDelaySeconds(operator.probeInitialDelay)
                            .withFailureThreshold(operator.probeFailureThreshold)
                            .withTimeoutSeconds(operator.probeTimeout)
                        .endLivenessProbe()
                        .withNewReadinessProbe()
                            .withNewHttpGet()
                                .withPath("/actuator/health/readiness")
                                .withNewPort(9090)
                            .endHttpGet()
                            .withPeriodSeconds(1)
                            .withInitialDelaySeconds(operator.probeInitialDelay)
                            .withFailureThreshold(operator.probeFailureThreshold)
                            .withTimeoutSeconds(operator.probeTimeout)
                        .endReadinessProbe()
                        .withNewStartupProbe()
                            .withNewHttpGet()
                                .withPath("/actuator/health/liveness")
                                .withNewPort(9090)
                                .endHttpGet()
                            .withTimeoutSeconds(operator.probeTimeout)
                            .withInitialDelaySeconds(operator.startupProbeInitialDelay)
                            .withFailureThreshold(6)
                            .withPeriodSeconds(5)
                        .endStartupProbe()
                    .endContainer()
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

        return podTemplatePatcher.patch(template, shinyProxy.parsedKubernetesPodTemplateSpecPatches)
    }

    private fun createAffinity(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): Affinity {
        if (shinyProxy.antiAffinityRequired) {
            //@formatter:off
            return AffinityBuilder()
                .withNewPodAntiAffinity()
                    .addNewRequiredDuringSchedulingIgnoredDuringExecution()
                        .withNewLabelSelector()
                            .withMatchLabels<String, String>(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance))
                        .endLabelSelector()
                        .withTopologyKey(shinyProxy.antiAffinityTopologyKey)
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
                                .withMatchLabels<String, String>(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance))
                            .endLabelSelector()
                        .withTopologyKey(shinyProxy.antiAffinityTopologyKey)
                        .endPodAffinityTerm()
                    .endPreferredDuringSchedulingIgnoredDuringExecution()
                .endPodAntiAffinity()
                .build()
            //@formatter:on
        }
    }


}
