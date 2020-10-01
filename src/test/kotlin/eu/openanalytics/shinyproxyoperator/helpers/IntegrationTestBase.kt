package eu.openanalytics.shinyproxyoperator.helpers

import eu.openanalytics.shinyproxyoperator.Mode
import eu.openanalytics.shinyproxyoperator.Operator
import eu.openanalytics.shinyproxyoperator.ShinyProxyClient
import eu.openanalytics.shinyproxyoperator.crd.DoneableShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyList
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.NamespacedKubernetesClient
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

abstract class IntegrationTestBase {

    private val customResourceDefinitionContext = CustomResourceDefinitionContext.Builder()
            .withVersion("v1alpha1")
            .withScope("Namespaced")
            .withGroup("openanalytics.eu")
            .withPlural("shinyproxies")
            .build()

    val namespace = "itest"
    val client = DefaultKubernetesClient()

    protected fun setup(block: suspend (String, ShinyProxyClient, NamespacedKubernetesClient, Operator, ReconcileListener) -> Unit) {
        runBlocking {
            Runtime.getRuntime().addShutdownHook(Thread {
                runBlocking {
                    deleteNamespace(client, namespace)
                    deleteCRD(client)
                }
            })

            // 1. Create the namespace
            deleteNamespace(client, namespace)

            client.namespaces().create(NamespaceBuilder()
                    .withNewMetadata()
                    .withName(namespace)
                    .endMetadata()
                    .build())

            val namespacedKubernetesClient = client.inNamespace(namespace)

            // 2. create the CRD
            if (!crdExists(client)) {
                val crd = client.customResourceDefinitions().load(this.javaClass.getResource("/crd.yaml")).get()
                client.customResourceDefinitions().createOrReplace(crd)
            }

            // 3. create the operator
            val reconcileListener = ReconcileListener()
            val operator = Operator(namespacedKubernetesClient, Mode.NAMESPACED, reconcileListener)
            Operator.operatorInstance = operator

            val shinyProxyClient = client.inNamespace(namespace).customResources(customResourceDefinitionContext, ShinyProxy::class.java, ShinyProxyList::class.java, DoneableShinyProxy::class.java)

            try {
                // 4. run test
                block(namespace, shinyProxyClient, namespacedKubernetesClient, operator, reconcileListener)
            } finally {
                // 5. remove all instances
                try {
                    for (sp in shinyProxyClient.inNamespace(namespace).list().items) {
                        shinyProxyClient.delete(sp)
                    }
                } catch (e: KubernetesClientException) {
                    // no SP created
                }

                // 6. delete namespace
                deleteNamespace(client, namespace)
            }
        }

    }

    private suspend fun deleteNamespace(client: DefaultKubernetesClient, namespace: String) {
        val ns = client.namespaces().withName(namespace).get() ?: return
        client.namespaces().delete(ns)
        while (client.namespaces().withName(namespace).get() != null) {
            delay(1000)
        }
    }

    private suspend fun deleteCRD(client: DefaultKubernetesClient) {
        val crd = client.customResourceDefinitions().load(this.javaClass.getResource("/crd.yaml")).get()
        client.customResourceDefinitions().delete(crd)
        delay(2000)

        while (crdExists(client)) {
            delay(1000)
        }
    }

    private fun crdExists(client: DefaultKubernetesClient): Boolean {
        return client.customResourceDefinitions().list().items.firstOrNull {
            it.spec.group == "openanalytics.eu" && it.spec.names.plural == "shinyproxies"
        } != null

    }

}