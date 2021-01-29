/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2020 Open Analytics
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
import java.nio.file.Paths

class PodTemplateSpecFactory {

    private val podTemplatePatcher = PodTemplateSpecPatcher()

    fun create(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): PodTemplateSpec {

        val operator = Operator.getOperatorInstance()

        //@formatter:off
        val template = PodTemplateSpecBuilder()
                .withNewMetadata()
                    .withGenerateName(ResourceNameFactory.createNameForPod(shinyProxy, shinyProxyInstance))
                    .withNamespace(shinyProxy.metadata.namespace)
                    .withLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance))
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
                                .withValue(shinyProxy.metadata.name)
                            .build()))
                        .withVolumeMounts(VolumeMountBuilder()
                            .withName("config-volume")
                            .withMountPath("/etc/shinyproxy/application.yml")
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
                        .endLivenessProbe()
                        .withNewReadinessProbe()
                            .withNewHttpGet()
                                .withPath("/actuator/health/readiness")
                                .withNewPort(9090)
                            .endHttpGet()
                            .withPeriodSeconds(1)
                            .withInitialDelaySeconds(operator.probeInitialDelay)
                            .withFailureThreshold(operator.probeFailureThreshold)
                        .endReadinessProbe()
                        .withNewStartupProbe()
                            .withNewHttpGet()
                                .withPath("/actuator/health/liveness")
                                .withNewPort(9090)
                                .endHttpGet()
                            .withFailureThreshold(6)
                            .withPeriodSeconds(5)
                        .endStartupProbe()
                    .endContainer()
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


}
