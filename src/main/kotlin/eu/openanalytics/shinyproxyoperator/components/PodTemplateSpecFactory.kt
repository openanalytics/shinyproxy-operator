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

import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance
import io.fabric8.kubernetes.api.model.*

class PodTemplateSpecFactory {

    private val podTemplatePatcher = PodTemplateSpecPatcher()

    fun create(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): PodTemplateSpec {

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
                        .withImage("localhost:5000/shinyproxy-dev:latest") // TODO make configurable
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
                            .build()))
                        .withVolumeMounts(VolumeMountBuilder()
                            .withName("config-volume")
                            .withMountPath("/etc/shinyproxy/application.yml")
                            .withSubPath("application.yml")
                        .build())
                        .withNewLivenessProbe()
                            .withNewHttpGet()
                                .withPath("/actuator/health/liveness")
                                .withPort(IntOrString(8080))
                            .endHttpGet()
                            .withPeriodSeconds(1) // TODO
                        .endLivenessProbe()
                        .withNewReadinessProbe()
                            .withNewHttpGet()
                                .withPath("/actuator/health/readiness")
                                .withNewPort(8080) // string instead of int because of quirks in the library
                            .endHttpGet()
                            .withPeriodSeconds(1) // TODO
                        .endReadinessProbe()
                        .withNewStartupProbe()
                            .withNewHttpGet()
                                .withPath("/actuator/health/liveness")
                                .withNewPort(8080) // string instead of int because of quirks in the library
                                .endHttpGet()
                            .withFailureThreshold(6) // TODO
                            .withPeriodSeconds(5) // TODO configurable?
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