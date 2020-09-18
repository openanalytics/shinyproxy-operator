package eu.openanalytics.shinyproxyoperator.components

import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance
import io.fabric8.kubernetes.api.model.*

class PodTemplateSpecFactory  {

    private val podTemplatePatcher = PodTemplateSpecPatcher()

    fun create(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): PodTemplateSpec {

        val template = PodTemplateSpecBuilder()
                .withNewMetadata()
                    .withGenerateName(ResourceNameFactory.createNameForPod(shinyProxy))
                    .withNamespace(shinyProxy.metadata.namespace)
                    .withLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance))
                .endMetadata()
                .withNewSpec()
                    .addNewContainer()
                    .withName("shinyproxy")
                    .withImage("localhost:5000/shinyproxy-dev:latest")
                    .withVolumeMounts(VolumeMountBuilder()
                            .withName("config-volume")
                            .withMountPath("/etc/shinyproxy/application.yml")
                            .withSubPath("application.yml")
                            .build())
                    .endContainer()
                    .withVolumes(VolumeBuilder()
                            .withName("config-volume")
                            .withConfigMap(ConfigMapVolumeSourceBuilder()
                                    .withName(ResourceNameFactory.createNameForConfigMap(shinyProxy))
                                    .build())
                            .build())
                    .endSpec()
                .build()

        return podTemplatePatcher.patch(template, shinyProxy.parsedKubernetesPodTemplateSpecPatches)
    }


}