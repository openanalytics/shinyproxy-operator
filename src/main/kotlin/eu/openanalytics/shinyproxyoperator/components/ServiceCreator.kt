package eu.openanalytics.shinyproxyoperator.components

import eu.openanalytics.shinyproxyoperator.controller.ShinyProxyController
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.retry
import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.internal.readiness.Readiness
import mu.KotlinLogging

class ServiceCreator(private val shinyProxy: ShinyProxy) {

    private val logger = KotlinLogging.logger {}

    private val serviceDefinition: Service = ServiceBuilder()
            .withNewMetadata()
                .withGenerateName(shinyProxy.metadata.name.toString() + "-service-")
                .withNamespace(shinyProxy.metadata.namespace)
                .withLabels(mapOf(ShinyProxyController.APP_LABEL to shinyProxy.metadata.name))
                .addNewOwnerReference()
                    .withController(true)
                    .withKind("ShinyProxy")
                    .withApiVersion("openanalytics.eu/v1alpha1")
                    .withName(shinyProxy.metadata.name)
                    .withNewUid(shinyProxy.metadata.uid)
                .endOwnerReference()
            .endMetadata()
            .withNewSpec()
                .withType("NodePort")
                .addNewPort()
                    .withPort(80)
                    .withTargetPort(IntOrString(8080))
                .endPort()
                .withSelector(mapOf(ShinyProxyController.APP_LABEL to shinyProxy.metadata.name))
            .endSpec()
            .build()

    suspend fun create(kubeClient: KubernetesClient): Service? {
        val createdService = kubeClient.services().inNamespace(shinyProxy.metadata.namespace).create(serviceDefinition)
        logger.debug { "Created Service with name ${createdService.metadata.name}" }
        return kubeClient.resource(createdService).fromServer().get()
    }

}