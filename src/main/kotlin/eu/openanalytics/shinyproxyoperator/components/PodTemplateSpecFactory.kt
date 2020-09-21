package eu.openanalytics.shinyproxyoperator.components

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance
import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.client.internal.SerializationUtils
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature
import io.fabric8.kubernetes.client.utils.Serialization

class PodTemplateSpecFactory {

    private val podTemplatePatcher = PodTemplateSpecPatcher()

    fun create(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): PodTemplateSpec {

        //@formatter:off
        val template = PodTemplateSpecBuilder()
                .withNewMetadata()
                    .withGenerateName(ResourceNameFactory.createNameForPod(shinyProxy))
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
                                    .withName(ResourceNameFactory.createNameForConfigMap(shinyProxy))
                                    .build())
                            .build())
                    .endSpec()
                .build()
        //@formatter:on

        return podTemplatePatcher.patch(template, shinyProxy.parsedKubernetesPodTemplateSpecPatches)
    }


}