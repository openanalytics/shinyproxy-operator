/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021-2023 Open Analytics
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
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.PodList
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.NamespacedKubernetesClient
import io.fabric8.kubernetes.client.extended.run.RunConfigBuilder
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach


abstract class IntegrationTestBase {

    private val namespace = "itest"
    private val managedNamespaces = listOf("itest", "itest-2")

    protected val chaosEnabled = System.getenv("SPO_TEST_CHAOS") != null

    private val stableClient = DefaultKubernetesClient()
    private val chaosClient: DefaultKubernetesClient = if (chaosEnabled) {
        ChaosInterceptor.createChaosKubernetesClient()
    } else {
        DefaultKubernetesClient()
    }

    @AfterEach
    fun cleanup() {
        runBlocking {
            deleteNamespaces()
            stableClient.httpClient.close()
        }
    }

    companion object {

        @JvmStatic
        @AfterAll
        fun cleanupCRD() {
            runBlocking {
                val client = DefaultKubernetesClient()
                val crd = client.apiextensions().v1().customResourceDefinitions().load(this.javaClass.getResource("/crd.yaml")).get()
                client.apiextensions().v1().customResourceDefinitions().delete(crd)
                delay(2000)

                while (client.apiextensions().v1().customResourceDefinitions().list().items.firstOrNull { it.spec.group == "openanalytics.eu" && it.spec.names.plural == "shinyproxies" } != null) {
                    delay(1000)
                }
            }
        }

    }

    protected fun setup(mode: Mode, block: suspend (String, ShinyProxyClient, NamespacedKubernetesClient, NamespacedKubernetesClient, Operator, ReconcileListener, MockRecyclableChecker) -> Unit) {
        runBlocking {

            // 1. Create the namespace
            deleteNamespaces()
            createNamespaces()
            setupServiceAccount()

            val namespacedKubernetesClient = chaosClient.inNamespace(namespace)

            // 2. create the CRD
            if (!crdExists()) {
                val crd = stableClient.apiextensions().v1().customResourceDefinitions().load(this.javaClass.getResource("/crd.yaml")).get()
                stableClient.apiextensions().v1().customResourceDefinitions().createOrReplace(crd)
            }

            // 3. create the operator
            val recyclableChecker = MockRecyclableChecker()
            val reconcileListener = ReconcileListener()

            val operator = if (stableClient.isStartupProbesSupported()) {
                Operator(namespacedKubernetesClient, mode, reconcileListener, recyclableChecker=recyclableChecker)
            } else {
                Operator(namespacedKubernetesClient, mode, reconcileListener, 40, 2, recyclableChecker=recyclableChecker)
            }

            Operator.setOperatorInstance(operator)

            // TODO stable or chaos?
            val shinyProxyClient: ShinyProxyClient = stableClient.inNamespace(namespace).resources(ShinyProxy::class.java)

            try {
                // 4. run test
                block(namespace, shinyProxyClient, namespacedKubernetesClient, stableClient.inNamespace(namespace), operator, reconcileListener, recyclableChecker)
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
            Operator.getOperatorInstance().stop()
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
        while (true) {
            for (managedNamespace in managedNamespaces) {
                try {
                    val ns = stableClient.namespaces().withName(managedNamespace).get() ?: continue
                    stableClient.namespaces().resource(ns).delete()
                } catch (e: KubernetesClientException) {
                    // this namespace is probably all being deleted
                }
                delay(1000)
            }
            try {
                if (managedNamespaces.all { stableClient.namespaces().withName(it).get() == null }) {
                    break
                }
            } catch (_: KubernetesClientException) {
                return
            }
        }
    }


    private fun crdExists(): Boolean {
        return stableClient.apiextensions().v1().customResourceDefinitions().list().items.firstOrNull {
            it.spec.group == "openanalytics.eu" && it.spec.names.plural == "shinyproxies"
        } != null
    }

    protected fun executeAsyncAfter100ms(block: () -> Unit): Job {
        return GlobalScope.launch {
            delay(100)
            block()
        }
    }

    protected suspend fun startApp(shinyProxy: ShinyProxy, instance: ShinyProxyTestInstance) {
        val oldNumApps = getPodsForInstance(instance.hash)?.items?.filter { it.status.phase.equals("Running") }?.size ?: 0
        val serviceName = "sp-${shinyProxy.metadata.name}-svc".take(63)
        stableClient.run().inNamespace(shinyProxy.metadata.namespace)
            .withRunConfig(RunConfigBuilder()
                .withName("itest-curl-helper")
                .withImage("curlimages/curl")
                .withArgs("-X", "POST", "-u", "demo:demo", "${serviceName}/api/proxy/01_hello")
                .withRestartPolicy("Never")
                .build())
            .done()
        withTimeout(60_000) {
            while (true) {
                val newNumApps = getPodsForInstance(instance.hash)?.items?.filter { it.status.phase.equals("Running") }?.size ?: continue
                if (newNumApps > oldNumApps) {
                    break
                }
                delay(1_000)
            }
        }
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
