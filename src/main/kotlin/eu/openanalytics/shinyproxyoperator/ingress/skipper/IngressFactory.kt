package eu.openanalytics.shinyproxyoperator.ingress.skipper

import eu.openanalytics.shinyproxyoperator.components.LabelFactory
import eu.openanalytics.shinyproxyoperator.components.ResourceNameFactory
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.networking.v1beta1.Ingress
import io.fabric8.kubernetes.api.model.networking.v1beta1.IngressBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.internal.SerializationUtils
import mu.KotlinLogging
import java.lang.RuntimeException

class IngressFactory(private val kubeClient: KubernetesClient) {

    private val logger = KotlinLogging.logger {}

    fun createOrReplaceIngress(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance, replicaSet: ReplicaSet): Ingress? {

        val hashOfSpec = shinyProxyInstance.hashOfSpec ?: throw RuntimeException("Cannot create ingress for ShinyProxyInstance without hash of spec")

        val isLatest = hashOfSpec == shinyProxy.hashOfCurrentSpec
        val annotations = if (isLatest) {
            mapOf(
                    "kubernetes.io/ingress.class" to "skipper",
                    "zalando.org/skipper-predicate" to "True()",
                    "zalando.org/skipper-filter" to """jsCookie("sp-instance", "$hashOfSpec") -> jsCookie("sp-latest-instance", "${shinyProxy.hashOfCurrentSpec}")"""
            )
        } else {
            mapOf(
                    "kubernetes.io/ingress.class" to "skipper",
                    "zalando.org/skipper-predicate" to """Cookie("sp-instance", "$hashOfSpec")""",
                    "zalando.org/skipper-filter" to """jsCookie("sp-latest-instance", "${shinyProxy.hashOfCurrentSpec}")"""
            )
        }

        //@formatter:off
        val ingressDefinition = IngressBuilder()
                .withNewMetadata()
                    .withName(ResourceNameFactory.createNameForIngress(shinyProxy, shinyProxyInstance))
                    .withLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance))
                    .addNewOwnerReference()
                        .withController(true)
                        .withKind("ReplicaSet")
                        .withApiVersion("v1")
                        .withName(ResourceNameFactory.createNameForReplicaSet(shinyProxy, shinyProxyInstance))
                        .withNewUid(replicaSet.metadata.uid)
                    .endOwnerReference()
                .withAnnotations(annotations)
                .endMetadata()
                .withNewSpec()
                    .addNewRule()
                        .withHost("skipper-demo.ledfan.be")
                        .withNewHttp()
                            .addNewPath()
                                .withNewBackend()
                                    .withServiceName(ResourceNameFactory.createNameForService(shinyProxy, shinyProxyInstance))
                                    .withNewServicePort()
                                        .withIntVal(80)
                                    .endServicePort()
                                .endBackend()
                            .endPath()
                        .endHttp()
                    .endRule()
                .endSpec()
                .build()
        //@formatter:on

        println(SerializationUtils.dumpAsYaml(ingressDefinition))

        // TODO check namespace
        val createdIngress = kubeClient.network().ingress().inNamespace(shinyProxy.metadata.namespace).createOrReplace(ingressDefinition)

        logger.debug { "Created Ingress with name ${createdIngress.metadata.name} (latest=$isLatest)" }

        return kubeClient.resource(createdIngress).fromServer().get()
    }

}