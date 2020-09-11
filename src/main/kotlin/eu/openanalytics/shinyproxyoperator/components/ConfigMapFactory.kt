package eu.openanalytics.shinyproxyoperator.components

import eu.openanalytics.shinyproxyoperator.controller.ShinyProxyController
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import mu.KotlinLogging

class ConfigMapFactory(private val kubeClient: KubernetesClient) {

    private val logger = KotlinLogging.logger {}

    fun create(shinyProxy: ShinyProxy): ConfigMap {
        val configMapDefinition: ConfigMap = ConfigMapBuilder()
                .withNewMetadata()
                    .withGenerateName(shinyProxy.metadata.name.toString() + "-configmap-")
                    .withLabels(mapOf(ShinyProxyController.APP_LABEL to shinyProxy.metadata.name))
                    .addNewOwnerReference()
                        .withController(true)
                        .withKind("ShinyProxy")
                        .withApiVersion("openanalytics.eu/v1alpha1")
                        .withName(shinyProxy.metadata.name)
                        .withNewUid(shinyProxy.metadata.uid)
                    .endOwnerReference()
                .endMetadata()
                .addToData("application-in.yml", shinyProxy.spec.applicationYaml)
                .build()

        val createdConfigMap = kubeClient.configMaps().inNamespace(shinyProxy.metadata.namespace).create(configMapDefinition)
        logger.debug { "Created ConfigMap with name ${createdConfigMap.metadata.name}" }
        return kubeClient.resource(createdConfigMap).fromServer().get()
    }

}