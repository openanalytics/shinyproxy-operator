/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021 Open Analytics
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
package eu.openanalytics.shinyproxyoperator.helpers

import eu.openanalytics.shinyproxyoperator.Mode
import eu.openanalytics.shinyproxyoperator.Operator
import eu.openanalytics.shinyproxyoperator.ShinyProxyClient
import eu.openanalytics.shinyproxyoperator.components.LabelFactory
import eu.openanalytics.shinyproxyoperator.crd.DoneableShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyList
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.PodList
import io.fabric8.kubernetes.client.*
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext
import io.fabric8.kubernetes.client.extended.run.RunConfigBuilder
import io.fabric8.kubernetes.client.utils.HttpClientUtils.createHttpClient
import kotlinx.coroutines.*


abstract class IntegrationTestBase {

    private val customResourceDefinitionContext = CustomResourceDefinitionContext.Builder()
            .withVersion("v1alpha1")
            .withScope("Namespaced")
            .withGroup("openanalytics.eu")
            .withPlural("shinyproxies")
            .build()

    private val namespace = "itest"
    private val managedNamespaces = listOf("itest", "itest-2")

    protected val chaosEnabled = System.getenv("SPO_TEST_CHAOS") != null

    private val stableClient = DefaultKubernetesClient()
    private val chaosClient: DefaultKubernetesClient = if (chaosEnabled) {
        ChaosInterceptor.createChaosKubernetesClient()
    } else {
        DefaultKubernetesClient()
    }

    protected fun setup(mode: Mode, disableSecureCookies: Boolean = false, block: suspend (String, ShinyProxyClient, NamespacedKubernetesClient, NamespacedKubernetesClient, Operator, ReconcileListener) -> Unit) {
        runBlocking {

            Runtime.getRuntime().addShutdownHook(Thread {
                runBlocking {
                    deleteNamespaces()
                    deleteCRD(stableClient)
                }
            })

            // 1. Create the namespace
            deleteNamespaces()
            createNamespaces()
            setupServiceAccount()

            val namespacedKubernetesClient = chaosClient.inNamespace(namespace)

            // 2. create the CRD
            if (!crdExists()) {
                val crd = stableClient.customResourceDefinitions().load(this.javaClass.getResource("/crd.yaml")).get()
                stableClient.customResourceDefinitions().createOrReplace(crd)
            }

            // 3. create the operator
            val reconcileListener = ReconcileListener()

            val operator = if (stableClient.isStartupProbesSupported()) {
                Operator(namespacedKubernetesClient, mode, disableSecureCookies, reconcileListener)
            } else {
                Operator(namespacedKubernetesClient, mode, disableSecureCookies, reconcileListener, 40, 2)
            }

            Operator.setOperatorInstance(operator)

            // TODO stable or chaos?
            val shinyProxyClient = stableClient.inNamespace(namespace).customResources(customResourceDefinitionContext, ShinyProxy::class.java, ShinyProxyList::class.java, DoneableShinyProxy::class.java)

            try {
                // 4. run test
                block(namespace, shinyProxyClient, namespacedKubernetesClient, stableClient.inNamespace(namespace), operator, reconcileListener)
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
                deleteNamespaces()
            }
        }

    }


    private fun createNamespaces() {
        for (managedNamespace in managedNamespaces) {
            stableClient.namespaces().create(NamespaceBuilder()
                    .withNewMetadata()
                    .withName(managedNamespace)
                    .endMetadata()
                    .build())
        }
    }

    private suspend fun deleteNamespaces() {
        for (managedNamespace in managedNamespaces) {
            val ns = stableClient.namespaces().withName(managedNamespace).get() ?: continue
            try {
                stableClient.namespaces().delete(ns)
            } catch (e: KubernetesClientException) {
                // this namespace is probably all being deleted
            }
            while (stableClient.namespaces().withName(managedNamespace).get() != null) {
                delay(1000)
            }
        }
    }

    private suspend fun deleteCRD(client: DefaultKubernetesClient) {
        val crd = client.customResourceDefinitions().load(this.javaClass.getResource("/crd.yaml")).get()
        client.customResourceDefinitions().delete(crd)
        delay(2000)

        while (crdExists()) {
            delay(1000)
        }
    }

    private fun crdExists(): Boolean {
        return stableClient.customResourceDefinitions().list().items.firstOrNull {
            it.spec.group == "openanalytics.eu" && it.spec.names.plural == "shinyproxies"
        } != null
    }

    protected fun executeAsyncAfter100ms(block: () -> Unit): Job {
        return GlobalScope.launch {
            delay(100)
            block()
        }
    }

    protected fun runCurlRequest(serviceName: String, namespace: String) {
        stableClient.run().inNamespace(namespace)
                .withRunConfig(RunConfigBuilder()
                        .withName("itest-curl-helper")
                        .withImage("curlimages/curl")
                        .withArgs("-X", "POST", "-u", "demo:demo", "${serviceName}/api/proxy/01_hello")
                        .withRestartPolicy("Never")
                        .build())
                .done()
    }

    private fun setupServiceAccount() {
        stableClient.load(this.javaClass.getResourceAsStream("/configs/serviceaccount.yaml")).createOrReplace()
    }

    protected fun getPodsForInstance(instanceHash: String): PodList? {
        return stableClient.pods().inNamespace(namespace).withLabels(mapOf(
                LabelFactory.PROXIED_APP to "true",
                LabelFactory.INSTANCE_LABEL to instanceHash
        )).list()
    }

}

fun KubernetesClient.isStartupProbesSupported(): Boolean {
    return version.major == "1" && version.minor >= "18"
}
