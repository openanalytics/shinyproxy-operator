/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2020 Open Analytics
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


package eu.openanalytics.shinyproxyoperator

import eu.openanalytics.shinyproxyoperator.components.LabelFactory
import eu.openanalytics.shinyproxyoperator.components.ResourceNameFactory
import eu.openanalytics.shinyproxyoperator.helpers.IntegrationTestBase
import eu.openanalytics.shinyproxyoperator.helpers.ShinyProxyTestInstance
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.client.internal.readiness.Readiness
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.IllegalStateException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MainIntegrationTest : IntegrationTestBase() {

    private val logger = KotlinLogging.logger {  }

    @Test
    fun `ingress should not be created before ReplicaSet is ready`() = setup { namespace, shinyProxyClient, namespacedClient, operator, reconcileListener ->
        // 1. create a SP instance
        val spTestInstance = ShinyProxyTestInstance(namespace, namespacedClient, shinyProxyClient, "simple_config.yaml", reconcileListener)
        spTestInstance.create()

        // 2. prepare the operator
        operator.prepare()

        // 3. check whether the controller waits until the ReplicaSet is ready before creating other resources
        var checked = false
        while (true) {
            // let the operator handle one event
            operator.shinyProxyController.receiveAndHandleEvent()

            val replicaSets = namespacedClient.apps().replicaSets().list().items
            if (replicaSets.size == 0) {
                // if replicaset is not created -> continue handling events
                continue
            }
            assertEquals(1, replicaSets.size)
            val replicaSet = replicaSets[0]
            // replicaset exists -> perform our checks, operator is paused

            if (!Readiness.isReady(replicaSet)) {
                // replicaset not ready
                // -> Service should not yet be created
                assertEquals(0, namespacedClient.services().list().items.size)

                // -> Ingress should not yet be created
                assertEquals(0, namespacedClient.network().ingresses().list().items.size)

                // -> Latest marker should not yet be set
                val sp = spTestInstance.retrieveInstance()
                assertEquals(1, sp.status.instances.size)
                println("${sp.status.instances[0].isLatestInstance} => ${Readiness.isReady(replicaSet)}")
                assertFalse(sp.status.instances[0].isLatestInstance)
                checked = true
            } else {
                // ReplicaSet is Ready -> break
                break
            }
        }

        assertTrue(checked) // actually checked that ingress wasn't created when the ReplicaSet wasn't ready yet

        val job = GlobalScope.launch {
            // let the operator finish its business
            operator.run()
        }

        // 4. wait until instance is created
        spTestInstance.waitForOneReconcile()

        // 5. assert correctness
        spTestInstance.assertInstanceIsCorrect()
        job.cancel()
    }

    @Test
    fun simple_test() = setup { namespace, shinyProxyClient, namespacedClient, operator, reconcileListener ->
        // 1. create a SP instance
        val spTestInstance = ShinyProxyTestInstance(namespace, namespacedClient, shinyProxyClient, "simple_config.yaml", reconcileListener)
        spTestInstance.create()

        // 2. start the operator and let it do it's work
        val job = GlobalScope.launch {
            operator.prepare()
            operator.run()
        }

        // 3. wait until instance is created
        spTestInstance.waitForOneReconcile()

        // 4. assert correctness
        spTestInstance.assertInstanceIsCorrect()
        job.cancel()
    }


    @Test
    fun `operator should re-create removed resources`() = setup { namespace, shinyProxyClient, namespacedClient, operator, reconcileListener ->
        // 1. create a SP instance
        val spTestInstance = ShinyProxyTestInstance(namespace, namespacedClient, shinyProxyClient, "simple_config.yaml", reconcileListener)
        val sp = spTestInstance.create()

        // 2. start the operator and let it do it's work
        val job = GlobalScope.launch {
            operator.prepare()
            operator.run()
        }

        // 3. wait until instance is created
        spTestInstance.waitForOneReconcile()
        logger.info { "Fuly created instance." }

        // 4. assert correctness
        spTestInstance.assertInstanceIsCorrect()

        // 5. Delete Replicaset -> reconcile -> assert it is still ok
        executeAsyncAfter100ms {
            namespacedClient.apps().replicaSets().withName("sp-${sp.metadata.name}-rs-${spTestInstance.hash}".take(63)).delete()
            logger.info { "Deleted ReplicaSet" }
        }
        spTestInstance.waitForOneReconcile()
        logger.info { "Reconciled after deleting RS" }
        spTestInstance.assertInstanceIsCorrect()

        // 6. Delete ConfigMap -> reconcile -> assert it is still ok
        executeAsyncAfter100ms {
            namespacedClient.configMaps().withName("sp-${sp.metadata.name}-cm-${spTestInstance.hash}".take(63)).delete()
            logger.info { "Deleted ConfigMap" }
        }
        spTestInstance.waitForOneReconcile()
        logger.info { "Reconciled after deleting CM" }
        spTestInstance.assertInstanceIsCorrect()

        // 7. Delete Service -> reconcile -> assert it is still ok
        executeAsyncAfter100ms {
            namespacedClient.services().withName("sp-${sp.metadata.name}-svc-${spTestInstance.hash}".take(63)).delete()
            logger.info { "Deleted Service" }
        }
        spTestInstance.waitForOneReconcile()
        logger.info { "Reconciled after deleting SVC" }
        spTestInstance.assertInstanceIsCorrect()

        // 8. Delete Ingress -> reconcile -> assert it is still ok
        executeAsyncAfter100ms {
            namespacedClient.network().ingress().withName("sp-${sp.metadata.name}-ing-${spTestInstance.hash}".take(63)).delete()
            logger.info { "Deleted Ingress" }
        }
        spTestInstance.waitForOneReconcile()
        spTestInstance.assertInstanceIsCorrect()
        logger.info { "Reconciled after deleting Ingress" }

        job.cancel()
        logger.info { "Operator stopped" }
    }

    @Test
    fun `sp in other namespaced should be ignored when using namespaced mode`() = setup { namespace, shinyProxyClient, namespacedClient, operator, reconcileListener ->
        // 1. create a SP instance in other namespace
        val spTestInstance = ShinyProxyTestInstance("itest-2", namespacedClient.inNamespace("itest-2"), shinyProxyClient, "simple_config.yaml", reconcileListener)
        val sp = spTestInstance.create()

        // 2. start the operator and let it do it's work
        val job = GlobalScope.launch {
            operator.prepare()
            operator.run()
        }

        // 3. wait a bit
        delay(20000)

        // assert that there are no ReplicaSets created
        assertEquals(0, namespacedClient.apps().replicaSets().list().items.size)

        job.cancel()
    }

    @Test
    fun `simple test with PodTemplateSpec patches`() = setup { namespace, shinyProxyClient, namespacedClient, operator, reconcileListener ->
        // 1. create a SP instance in other namespace
        val spTestInstance = ShinyProxyTestInstance(namespace, namespacedClient, shinyProxyClient, "simple_config_with_patches.yaml", reconcileListener)
        val sp = spTestInstance.create()

        // 2. start the operator and let it do it's work
        val job = GlobalScope.launch {
            operator.prepare()
            operator.run()
        }

        // 3. wait until instance is created
        spTestInstance.waitForOneReconcile()


        // 4. assertions
        val retrievedSp = spTestInstance.retrieveInstance()
        assertNotNull(retrievedSp)
        val instance = retrievedSp.status.instances[0]
        assertNotNull(instance)
        assertTrue(instance.isLatestInstance)

        // check configmap
        spTestInstance.assertConfigMapIsCorrect(sp)

        // check replicaset
        val replicaSets = namespacedClient.inNamespace(namespace).apps().replicaSets().list().items
        assertEquals(1, replicaSets.size)
        val replicaSet = replicaSets[0]
        val templateSpec = replicaSet.spec.template.spec
        assertEquals(1, templateSpec.containers.size)
        assertEquals("shinyproxy", templateSpec.containers[0].name)

        assertEquals(1, templateSpec.containers[0].livenessProbe.periodSeconds)
        assertEquals(30, templateSpec.containers[0].livenessProbe.initialDelaySeconds) // changed by patch
        assertEquals("/actuator/health/liveness", templateSpec.containers[0].livenessProbe.httpGet.path)
        assertEquals(IntOrString(8080), templateSpec.containers[0].livenessProbe.httpGet.port)

        assertEquals(1, templateSpec.containers[0].readinessProbe.periodSeconds)
        assertEquals(30, templateSpec.containers[0].readinessProbe.initialDelaySeconds) // changed by patch
        assertEquals("/actuator/health/readiness", templateSpec.containers[0].readinessProbe.httpGet.path)
        assertEquals(IntOrString(8080), templateSpec.containers[0].readinessProbe.httpGet.port)

        assertEquals(null, templateSpec.containers[0].startupProbe) // changed by patch

        assertEquals(3, templateSpec.containers[0].env.size) // changed by patch
        assertNotNull(templateSpec.containers[0].env.firstOrNull { it.name == "SP_KUBE_POD_UID" })
        assertNotNull(templateSpec.containers[0].env.firstOrNull { it.name == "SP_KUBE_POD_NAME" })
        assertNotNull(templateSpec.containers[0].env.firstOrNull { it.name == "TEST_VAR" })
        assertEquals("TEST_VALUE", templateSpec.containers[0].env.firstOrNull { it.name == "TEST_VAR" }?.value)

        // check service
        spTestInstance.assertServiceIsCorrect(sp)

        // check ingress
        spTestInstance.assertIngressIsCorrect(sp)

        job.cancel()
    }

    @Test
    fun `update without apps running`() = setup { namespace, shinyProxyClient, namespacedClient, operator, reconcileListener ->
        // 1. create a SP instance
        val spTestInstanceOriginal = ShinyProxyTestInstance(namespace, namespacedClient, shinyProxyClient, "simple_config.yaml", reconcileListener)
        spTestInstanceOriginal.create()

        // 2. start the operator and let it do it's work
        val job = GlobalScope.launch {
            operator.prepare()
            operator.run()
        }

        // 3. wait until instance is created
        spTestInstanceOriginal.waitForOneReconcile()

        // 4. assert correctness
        spTestInstanceOriginal.assertInstanceIsCorrect()

        // 5. update ShinyProxy instance
        logger.debug { "Base instance created -> updating it" }
        val spTestInstanceUpdated = ShinyProxyTestInstance(namespace, namespacedClient, shinyProxyClient, "simple_config_updated.yaml", reconcileListener)
        spTestInstanceUpdated.create()
        logger.debug { "Base instance created -> updated" }

        // 6. wait until instance is created
        spTestInstanceUpdated.waitForOneReconcile()

        // 7. wait for delete to happen
        delay(5000)

        // 8. assert correctness
        spTestInstanceUpdated.assertInstanceIsCorrect()

        // 9. assert older instance does not exists anymore
        assertThrows<IllegalStateException>("Instance not found") {
            spTestInstanceOriginal.retrieveInstance()
        }

        job.cancel()
    }

    @Test
    fun `update with apps running`() = setup { namespace, shinyProxyClient, namespacedClient, operator, reconcileListener ->
        // 1. create a SP instance
        val spTestInstanceOriginal = ShinyProxyTestInstance(namespace, namespacedClient, shinyProxyClient, "simple_config.yaml", reconcileListener)
        val sp = spTestInstanceOriginal.create()

        // 2. start the operator and let it do it's work
        val job = GlobalScope.launch {
            operator.prepare()
            operator.run()
        }

        // 3. wait until instance is created
        spTestInstanceOriginal.waitForOneReconcile()

        // 4. assert correctness
        spTestInstanceOriginal.assertInstanceIsCorrect()

        // 5. launch an app
        runCurlRequest("sp-${sp.metadata.name}-svc-${spTestInstanceOriginal.hash}".take(63), namespace)
        delay(10_000) // give the app some time to properly boot

        // 6. update ShinyProxy instance
        logger.debug { "Base instance created -> updating it" }
        val spTestInstanceUpdated = ShinyProxyTestInstance(namespace, namespacedClient, shinyProxyClient, "simple_config_updated.yaml", reconcileListener)
        val spUpdated = spTestInstanceUpdated.create()
        logger.debug { "Base instance created -> updated" }

        // 7. wait until instance is created
        spTestInstanceUpdated.waitForOneReconcile()

        // 7. wait for delete to not happen
        delay(5000)

        // 8. assert that two instances are correctly working
        spTestInstanceOriginal.assertInstanceIsCorrect(2, false)
        spTestInstanceUpdated.assertInstanceIsCorrect(2, true)

        // 9. kill app on older instance
        namespacedClient.pods().delete(getPodsForInstance(spTestInstanceOriginal.hash)?.items)

        // 10. wait for delete to happen
        while (getPodsForInstance(spTestInstanceOriginal.hash)?.items?.isNotEmpty() == true
                || namespacedClient.pods().withName("sp-${spUpdated.metadata.name}-pod-${spUpdated.hashOfCurrentSpec}").get() != null) {
            delay(1000)
            println("Pod still exists!")
        }
        // 11. give operator time to clenaup
        delay(5000)

        // 11. assert older instance does not exists anymore
        assertThrows<IllegalStateException>("Instance not found") {
            spTestInstanceOriginal.retrieveInstance()
        }

        spTestInstanceUpdated.assertInstanceIsCorrect(1, true)

        job.cancel()

    }

    @Test
    fun `apps running in different namespaces`() = setup { namespace, shinyProxyClient, namespacedClient, operator, reconcileListener ->

    }

}