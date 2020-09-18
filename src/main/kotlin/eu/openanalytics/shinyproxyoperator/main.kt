package eu.openanalytics.shinyproxyoperator

import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyList
import eu.openanalytics.shinyproxyoperator.controller.ShinyProxyController
import eu.openanalytics.shinyproxyoperator.crd.DoneableShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.apps.ReplicaSetList
import io.fabric8.kubernetes.api.model.networking.v1beta1.Ingress
import io.fabric8.kubernetes.api.model.networking.v1beta1.IngressList
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.Resource
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext
import mu.KotlinLogging

typealias ShinyProxyClient = MixedOperation<ShinyProxy, ShinyProxyList, DoneableShinyProxy, Resource<ShinyProxy, DoneableShinyProxy>>

suspend fun main() {
    val logger = KotlinLogging.logger {}
    try {
        val client = DefaultKubernetesClient()
        var namespace = client.namespace
        if (namespace == null) {
            logger.info { "No namespace found via config, assuming default." }
            namespace = "default"
        }
        logger.info { "Using namespace : $namespace " }
        var podSetCustomResourceDefinition = client.customResourceDefinitions().withName("shinyproxies.openanalytics.eu").get()
        if (podSetCustomResourceDefinition == null) {
            podSetCustomResourceDefinition = client.customResourceDefinitions().load(object : Any() {}.javaClass.getResourceAsStream("/crd.yaml")).get()
            client.customResourceDefinitions().create(podSetCustomResourceDefinition)
            logger.info { "Created CustomResourceDefinition" }
        }

        val podSetCustomResourceDefinitionContext = CustomResourceDefinitionContext.Builder()
                .withVersion("v1alpha1")
                .withScope("Namespaced")
                .withGroup("openanalytics.eu")
                .withPlural("shinyproxies")
                .build()

        val informerFactory = client.informers()
        val shinyProxyClient: ShinyProxyClient = client.customResources(podSetCustomResourceDefinitionContext, ShinyProxy::class.java, ShinyProxyList::class.java, DoneableShinyProxy::class.java)
        val replicaSetIndexInformer = informerFactory.sharedIndexInformerFor(ReplicaSet::class.java, ReplicaSetList::class.java, 10 * 60 * 1000.toLong())
        val serviceIndexInformer = informerFactory.sharedIndexInformerFor(Service::class.java, ServiceList::class.java, 10 * 60 * 1000.toLong())
        val configMapIndexInformer = informerFactory.sharedIndexInformerFor(ConfigMap::class.java, ConfigMapList::class.java, 10 * 60 * 1000.toLong())
        val ingressInformer = informerFactory.sharedIndexInformerFor(Ingress::class.java, IngressList::class.java, 10 * 60 * 1000.toLong())
        val shinyProxyIndexInformer = informerFactory.sharedIndexInformerForCustomResource(podSetCustomResourceDefinitionContext, ShinyProxy::class.java, ShinyProxyList::class.java, 10 * 60 * 1000)
        val podInformer = informerFactory.sharedIndexInformerFor(Pod::class.java, PodList::class.java, 10 * 60 * 1000.toLong())

        val shinyProxyController = ShinyProxyController(client,
                shinyProxyClient,
                replicaSetIndexInformer,
                serviceIndexInformer,
                configMapIndexInformer,
                podInformer,
                ingressInformer,
                shinyProxyIndexInformer,
                namespace)

        informerFactory.startAllRegisteredInformers()

        informerFactory.addSharedInformerEventListener {
            logger.warn { "Exception occurred, but caught $it" }
        }

        shinyProxyController.run()
    } catch (exception: KubernetesClientException) {
        logger.warn { "Kubernetes Client Exception : ${exception.message}" }
        exception.printStackTrace()
    }
}