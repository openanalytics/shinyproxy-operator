package eu.openanalytics.shinyproxyoperator.components

import eu.openanalytics.shinyproxyoperator.controller.ShinyProxyController
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import mu.KotlinLogging

class ServiceFactory(private val kubeClient: KubernetesClient) {

    private val logger = KotlinLogging.logger {}

    suspend fun create(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): Service? {
        val serviceDefinition: Service = ServiceBuilder()
                .withNewMetadata()
                    .withName(ResourceNameFactory.createNameForService(shinyProxy))
                    .withNamespace(shinyProxy.metadata.namespace)
                    .withLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance))
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
                    .withSelector(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance))
                .endSpec()
                .build()

        val createdService = kubeClient.services().inNamespace(shinyProxy.metadata.namespace).create(serviceDefinition)
        logger.debug { "Created Service with name ${createdService.metadata.name}" }

        return kubeClient.resource(createdService).fromServer().get()
    }

}