package eu.openanalytics.shinyproxyoperator.components

import eu.openanalytics.shinyproxyoperator.controller.ShinyProxyController
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.retry
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.internal.readiness.Readiness
import mu.KotlinLogging
import java.util.*

class ReplicaSetFactory(private val kubeClient: KubernetesClient ) {

    private val logger = KotlinLogging.logger {}

    suspend fun create(shinyProxy: ShinyProxy,configMap: ConfigMap): ReplicaSet {
       val replicaSetDefinition: ReplicaSet = ReplicaSetBuilder()
                .withNewMetadata()
                    .withGenerateName(shinyProxy.metadata.name.toString() + "-replicaset-")
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
                    .withReplicas(1)
                    .withNewSelector()
                        .withMatchLabels(mapOf(ShinyProxyController.APP_LABEL to shinyProxy.metadata.name))
                    .endSelector()
                    .withNewTemplate()
                        .withNewMetadata()
                        .withGenerateName(shinyProxy.metadata.name.toString() + "-pod-")
                        .withNamespace(shinyProxy.metadata.namespace)
                        .withLabels(Collections.singletonMap(ShinyProxyController.APP_LABEL, shinyProxy.metadata.name))
                    .endMetadata()
                    .withNewSpec()
                        .addNewContainer()
                        .withName("shinyproxy")
                        .withImage("localhost:5000/shinyproxy-dev:latest")
                        .withVolumeMounts(VolumeMountBuilder()
                                .withName("config-volume")
                                .withMountPath("/etc/shinyproxy/application-in.yml")
                                .withSubPath("application-in.yml")
                                .build())
                        .endContainer()
                        .withVolumes(VolumeBuilder()
                                .withName("config-volume")
                                .withConfigMap(ConfigMapVolumeSourceBuilder()
                                        .withName(configMap.metadata.name)
                                        .build())
                                .build())
                    .endSpec()
                .endTemplate()
                .endSpec()
                .build()

        val createdReplicaSet = kubeClient.apps().replicaSets().inNamespace(shinyProxy.metadata.namespace).create(replicaSetDefinition)
        if (retry(60, 1000) { Readiness.isReady(kubeClient.resource(createdReplicaSet).fromServer().get()) }) {
            logger.debug { "Created ReplicaSet with name ${createdReplicaSet.metadata.name}" }
            return kubeClient.resource(createdReplicaSet).fromServer().get()
        } else {
            throw RuntimeException("Could not create ReplicaSet ${createdReplicaSet.metadata.name}")
        }
    }

}