/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021-2024 Open Analytics
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
import eu.openanalytics.shinyproxyoperator.controller.ShinyProxyEvent
import eu.openanalytics.shinyproxyoperator.controller.ShinyProxyEventType
import eu.openanalytics.shinyproxyoperator.helpers.IntegrationTestBase
import eu.openanalytics.shinyproxyoperator.helpers.ShinyProxyTestInstance
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.client.readiness.Readiness
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainIntegrationTest : IntegrationTestBase() {

    private val logger = KotlinLogging.logger { }

    @Test
    fun `ingress should not be created before ReplicaSet is ready`() =
        setup(Mode.NAMESPACED) { namespace, shinyProxyClient, namespacedClient, stableClient, operator, reconcileListener, _ ->
            if (chaosEnabled) return@setup // this test depends on timings and therefore it does not work with chaos enabled
            // 1. create a SP instance
            val spTestInstance = ShinyProxyTestInstance(
                namespace,
                namespacedClient,
                shinyProxyClient,
                "simple_config.yaml",
                reconcileListener
            )
            spTestInstance.create()

            // 2. prepare the operator
            val (resourceRetriever, shinyProxyLister) = operator.prepare()

            // 3. check whether the controller waits until the ReplicaSet is ready before creating other resources
            var checked = false
            while (true) {
                // let the operator handle one event
                operator.shinyProxyController.receiveAndHandleEvent(resourceRetriever, shinyProxyLister)

                val replicaSets = stableClient.apps().replicaSets().list().items
                if (replicaSets.size == 0) {
                    // if replicaset is not created -> continue handling events
                    continue
                }
                assertEquals(1, replicaSets.size)
                val replicaSet = replicaSets[0]
                // replicaset exists -> perform our checks, operator is paused

                if (!Readiness.getInstance().isReady(replicaSet)) {
                    // replicaset not ready
                    // -> Service should not yet be created
                    assertEquals(0, stableClient.services().list().items.size)

                    // -> Ingress should not yet be created
                    assertEquals(0, stableClient.network().v1().ingresses().list().items.size)

                    // -> Latest marker should not yet be set
                    val sp = spTestInstance.retrieveInstance()
                    assertEquals(1, sp.status.instances.size)
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
                operator.run(resourceRetriever, shinyProxyLister)
            }

            // 4. wait until instance is created
            spTestInstance.waitForOneReconcile()

            // 5. assert correctness
            spTestInstance.assertInstanceIsCorrect()
            job.cancel()
        }

    @Test
    fun `simple test namespaces`() =
        setup(Mode.NAMESPACED) { namespace, shinyProxyClient, namespacedClient, _, operator, reconcileListener, _ ->
            // 1. create a SP instance
            val spTestInstance = ShinyProxyTestInstance(
                namespace,
                namespacedClient,
                shinyProxyClient,
                "simple_config.yaml",
                reconcileListener
            )
            spTestInstance.create()

            val (resourceRetriever, shinyProxyLister) = operator.prepare()
            // 2. start the operator and let it do it's work
            val job = GlobalScope.launch {
                operator.run(resourceRetriever, shinyProxyLister)
            }

            // 3. wait until instance is created
            spTestInstance.waitForOneReconcile()

            // 4. assert correctness
            spTestInstance.assertInstanceIsCorrect()
            job.cancel()
        }

    @Test
    fun `operator should re-create removed resources`() =
        setup(Mode.NAMESPACED) { namespace, shinyProxyClient, namespacedClient, stableClient, operator, reconcileListener, _ ->
            if (chaosEnabled) return@setup // this test depends on timings and therefore it does not work with chaos enabled
            // 1. create a SP instance
            val spTestInstance = ShinyProxyTestInstance(
                namespace,
                namespacedClient,
                shinyProxyClient,
                "simple_config.yaml",
                reconcileListener
            )
            val sp = spTestInstance.create()

            // 2. start the operator and let it do it's work
            val (resourceRetriever, shinyProxyLister) = operator.prepare()
            val job = GlobalScope.launch {
                operator.run(resourceRetriever, shinyProxyLister)
            }

            // 3. wait until instance is created
            spTestInstance.waitForReconcileCycle()
            logger.info { "Fully created instance." }

            // 4. assert correctness
            spTestInstance.assertInstanceIsCorrect()

            // 5. Delete Replicaset -> reconcile -> assert it is still ok
            executeAsyncAfter100ms {
                stableClient.apps().replicaSets()
                    .withName("sp-${sp.metadata.name}-rs-${spTestInstance.hash}".take(63)).delete()
                logger.info { "Deleted ReplicaSet" }
            }
            spTestInstance.waitForReconcileCycle()
            logger.info { "Reconciled after deleting RS" }
            spTestInstance.assertInstanceIsCorrect()

            // 6. Delete ConfigMap -> reconcile -> assert it is still ok
            executeAsyncAfter100ms {
                stableClient.configMaps().withName("sp-${sp.metadata.name}-cm-${spTestInstance.hash}".take(63))
                    .delete()
                logger.info { "Deleted ConfigMap" }
            }
            spTestInstance.waitForOneReconcile()
            logger.info { "Reconciled after deleting CM" }
            spTestInstance.assertInstanceIsCorrect()

            // 7. Delete Service -> reconcile -> assert it is still ok
            executeAsyncAfter100ms {
                stableClient.services().withName("sp-${sp.metadata.name}-svc".take(63))
                    .delete()
                logger.info { "Deleted Service" }
            }
            spTestInstance.waitForOneReconcile()
            logger.info { "Reconciled after deleting SVC" }
            spTestInstance.assertInstanceIsCorrect()

            // 8. Delete Ingress -> reconcile -> assert it is still ok
            executeAsyncAfter100ms {
                stableClient.network().v1().ingresses()
                    .withName("sp-${sp.metadata.name}-ing".take(63)).delete()
                logger.info { "Deleted Ingress" }
            }
            spTestInstance.waitForReconcileCycle()
            spTestInstance.assertInstanceIsCorrect()
            logger.info { "Reconciled after deleting Ingress" }

            job.cancel()
            logger.info { "Operator stopped" }
        }

    @Test
    fun `sp in other namespaced should be ignored when using namespaced mode`() =
        setup(Mode.NAMESPACED) { namespace, shinyProxyClient, namespacedClient, stableClient, operator, reconcileListener, _->
            // 1. create a SP instance in other namespace
            val spTestInstance = ShinyProxyTestInstance(
                "itest-2",
                stableClient.inNamespace("itest-2"),
                shinyProxyClient,
                "simple_config.yaml",
                reconcileListener
            )
            spTestInstance.create()

            // 2. start the operator and let it do it's work
            val (resourceRetriever, shinyProxyLister) = operator.prepare()
            val job = GlobalScope.launch {
                operator.run(resourceRetriever, shinyProxyLister)
            }

            // 3. wait a bit
            delay(20000)

            // assert that there are no ReplicaSets created
            assertEquals(0, stableClient.apps().replicaSets().list().items.size)

            job.cancel()
        }

    @Test
    fun `simple test with PodTemplateSpec patches`() =
        setup(Mode.NAMESPACED) { namespace, shinyProxyClient, namespacedClient, stableClient, operator, reconcileListener, _ ->
            // 1. create a SP instance in other namespace
            val spTestInstance = ShinyProxyTestInstance(
                namespace,
                namespacedClient,
                shinyProxyClient,
                "simple_config_with_patches.yaml",
                reconcileListener
            )
            val sp = spTestInstance.create()

            // 2. start the operator and let it do it's work
            val (resourceRetriever, shinyProxyLister) = operator.prepare()
            val job = GlobalScope.launch {
                operator.run(resourceRetriever, shinyProxyLister)
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
            val replicaSets = stableClient.inNamespace(namespace).apps().replicaSets().list().items
            assertEquals(1, replicaSets.size)
            val replicaSet = replicaSets[0]
            val templateSpec = replicaSet.spec.template.spec
            assertEquals(1, templateSpec.containers.size)
            assertEquals("shinyproxy", templateSpec.containers[0].name)

            assertEquals(1, templateSpec.containers[0].livenessProbe.periodSeconds)
            assertEquals(30, templateSpec.containers[0].livenessProbe.initialDelaySeconds) // changed by patch
            assertEquals("/actuator/health/liveness", templateSpec.containers[0].livenessProbe.httpGet.path)
            assertEquals(IntOrString(9090), templateSpec.containers[0].livenessProbe.httpGet.port)

            assertEquals(1, templateSpec.containers[0].readinessProbe.periodSeconds)
            assertEquals(30, templateSpec.containers[0].readinessProbe.initialDelaySeconds) // changed by patch
            assertEquals("/actuator/health/readiness", templateSpec.containers[0].readinessProbe.httpGet.path)
            assertEquals(IntOrString(9090), templateSpec.containers[0].readinessProbe.httpGet.port)

            assertEquals(null, templateSpec.containers[0].startupProbe) // changed by patch

            assertEquals(5, templateSpec.containers[0].env.size) // changed by patch
            assertNotNull(templateSpec.containers[0].env.firstOrNull { it.name == "SP_KUBE_POD_UID" })
            assertNotNull(templateSpec.containers[0].env.firstOrNull { it.name == "SP_KUBE_POD_NAME" })
            assertNotNull(templateSpec.containers[0].env.firstOrNull { it.name == "TEST_VAR" })
            assertEquals("TEST_VALUE", templateSpec.containers[0].env.firstOrNull { it.name == "TEST_VAR" }?.value)
            assertNotNull(templateSpec.containers[0].env.firstOrNull { it.name == "PROXY_REALM_ID" })
            assertEquals(sp.metadata.name + '-' + sp.metadata.namespace,
                templateSpec.containers[0].env.firstOrNull { it.name == "PROXY_REALM_ID" }?.value
            )
            assertNotNull(templateSpec.containers[0].env.firstOrNull { it.name == "PROXY_VERSION" })

            // check service
            spTestInstance.assertServiceIsCorrect(spTestInstance.retrieveInstance())

            // check ingress
            spTestInstance.assertIngressIsCorrect(spTestInstance.retrieveInstance())

            job.cancel()
        }

    @Test
    fun `update without apps running`() =
        setup(Mode.NAMESPACED) { namespace, shinyProxyClient, namespacedClient, stableClient, operator, reconcileListener, recyclableChecker ->
            if (chaosEnabled) return@setup // this test depends on timings and therefore it does not work with chaos enabled
            // 1. create a SP instance
            val spTestInstanceOriginal = ShinyProxyTestInstance(
                namespace,
                namespacedClient,
                shinyProxyClient,
                "simple_config.yaml",
                reconcileListener
            )
            val sp = spTestInstanceOriginal.create()

            // 2. start the operator and let it do it's work
            val (resourceRetriever, shinyProxyLister) = operator.prepare()
            val job = GlobalScope.launch {
                operator.run(resourceRetriever, shinyProxyLister)
            }

            // 3. wait until instance is created
            spTestInstanceOriginal.waitForOneReconcile()

            // 4. assert correctness
            spTestInstanceOriginal.assertInstanceIsCorrect()

            // 5. update ShinyProxy instance
            logger.debug { "Base instance created -> updating it" }
            val spTestInstanceUpdated = ShinyProxyTestInstance(
                namespace,
                namespacedClient,
                shinyProxyClient,
                "simple_config_updated.yaml",
                reconcileListener
            )
            spTestInstanceUpdated.create()
            logger.debug { "Base instance created -> updated" }

            // 6. wait until instance is created
            spTestInstanceUpdated.waitForOneReconcile()

            // 7. mark old shinyproxy as recyclable (old pods keeps existing)
            recyclableChecker.isRecyclable = true

            // 8. wait for delete to happen
            while (stableClient.pods().withName("sp-${sp.metadata.name}-pod-${spTestInstanceOriginal.hash}".take(63)).get() != null
                || stableClient.configMaps().withName("sp-${sp.metadata.name}-cm-${spTestInstanceOriginal.hash}".take(63)).get() != null
                || stableClient.services().withName("sp-${sp.metadata.name}-svc-${spTestInstanceOriginal.hash}".take(63)).get() != null) {
                delay(1000)
                logger.debug { "Pod still exists!" }
            }

            // 9. assert correctness
            spTestInstanceUpdated.assertInstanceIsCorrect()

            // 10. assert older instance does not exist anymore
            assertThrows<IllegalStateException>("Instance not found") {
                spTestInstanceOriginal.retrieveInstance()
            }

            job.cancel()
        }

    @Test
    fun `update with apps running`() =
        setup(Mode.NAMESPACED) { namespace, shinyProxyClient, namespacedClient, stableClient, operator, reconcileListener, recyclableChecker ->
            if (chaosEnabled) return@setup // this test depends on timings and therefore it does not work with chaos enabled
            // 1. create a SP instance
            val spTestInstanceOriginal = ShinyProxyTestInstance(
                namespace,
                namespacedClient,
                shinyProxyClient,
                "simple_config.yaml",
                reconcileListener
            )
            val sp = spTestInstanceOriginal.create()

            // 2. start the operator and let it do it's work
            val (resourceRetriever, shinyProxyLister) = operator.prepare()
            val job = GlobalScope.launch {
                operator.run(resourceRetriever, shinyProxyLister)
            }

            // 3. wait until instance is created
            spTestInstanceOriginal.waitForOneReconcile()

            // 4. assert correctness
            spTestInstanceOriginal.assertInstanceIsCorrect()

            // 5. launch an app
            startApp(sp, spTestInstanceOriginal)

            // 6. update ShinyProxy instance
            logger.debug { "Base instance created -> updating it" }
            val spTestInstanceUpdated = ShinyProxyTestInstance(
                namespace,
                namespacedClient,
                shinyProxyClient,
                "simple_config_updated.yaml",
                reconcileListener
            )
            spTestInstanceUpdated.create()
            logger.debug { "Base instance created -> updated" }

            // 7. wait until instance is created
            spTestInstanceUpdated.waitForOneReconcile()

            // 7. wait for delete to not happen
            delay(5000)

            // 8. assert that two instances are correctly working
            spTestInstanceOriginal.assertInstanceIsCorrect(2, false)
            spTestInstanceUpdated.assertInstanceIsCorrect(2, true)

            // 9. mark old shinyproxy as recyclable (old pods keeps existing)
            recyclableChecker.isRecyclable = true

            // 10. wait for delete to happen
            while (stableClient.pods().withName("sp-${sp.metadata.name}-pod-${spTestInstanceOriginal.hash}".take(63)).get() != null
                || stableClient.configMaps().withName("sp-${sp.metadata.name}-cm-${spTestInstanceOriginal.hash}".take(63)).get() != null
                || stableClient.services().withName("sp-${sp.metadata.name}-svc-${spTestInstanceOriginal.hash}".take(63)).get() != null) {
                delay(1000)
                logger.debug { "Pod still exists!" }
            }

            // 11. assert older instance does not exist anymore
            assertThrows<IllegalStateException>("Instance not found") {
                spTestInstanceOriginal.retrieveInstance()
            }

            // 12. assert correctness
            spTestInstanceUpdated.assertInstanceIsCorrect(1, true)

            // 13. assert app still exists
            assertEquals(1, getPodsForInstance(spTestInstanceOriginal.hash)?.items?.size)

            job.cancel()
        }

    @Test
    fun `simple test clustered`() =
        setup(Mode.CLUSTERED) { namespace, shinyProxyClient, namespacedClient, stableClient, operator, reconcileListener, _ ->
            // 1. create a SP instance
            val spTestInstance = ShinyProxyTestInstance(
                namespace,
                namespacedClient,
                shinyProxyClient,
                "simple_config.yaml",
                reconcileListener
            )
            spTestInstance.create()

            // 2. start the operator and let it do it's work
            val (resourceRetriever, shinyProxyLister) = operator.prepare()
            val job = GlobalScope.launch {
                operator.run(resourceRetriever, shinyProxyLister)
            }

            // 3. wait until instance is created
            spTestInstance.waitForOneReconcile()

            // 4. assert correctness
            spTestInstance.assertInstanceIsCorrect()

            // 5. create instance in other namespace
            val spTestInstance2 = ShinyProxyTestInstance(
                "itest-2",
                stableClient.inNamespace("itest-2"),
                shinyProxyClient,
                "simple_config_clustered.yaml",
                reconcileListener
            )
            spTestInstance2.create()

            // 6. wait until instance is created
            spTestInstance2.waitForOneReconcile()

            // 7. assert correctness
            spTestInstance2.assertInstanceIsCorrect()

            job.cancel()
        }

    @Test
    fun `configuration with subpath not ending in slash`() =
        setup(Mode.NAMESPACED) { namespace, shinyProxyClient, namespacedClient, stableClient, operator, reconcileListener , _->
            // 1. create a SP instance
            val spTestInstance = ShinyProxyTestInstance(
                namespace,
                namespacedClient,
                shinyProxyClient,
                "simple_config_subpath1.yaml",
                reconcileListener
            )
            spTestInstance.create()

            // 2. start the operator and let it do it's work
            val (resourceRetriever, shinyProxyLister) = operator.prepare()
            val job = GlobalScope.launch {
                operator.run(resourceRetriever, shinyProxyLister)
            }

            // 3. wait until instance is created
            spTestInstance.waitForOneReconcile()

            // 4. assert correctness
            spTestInstance.assertInstanceIsCorrect(1, true)

            // 5. additional assert correctness of ingress
            val ingresses = namespacedClient.inNamespace(namespace).network().v1().ingresses().list().items
            assertEquals(1, ingresses.size)
            assertTrue(ingresses.get(0).spec.rules.get(0).http.paths.get(0).path.endsWith("/"));

            job.cancel()
        }


    @Test
    fun `configuration with subpath ending in slash`() =
        setup(Mode.NAMESPACED) { namespace, shinyProxyClient, namespacedClient, stableClient, operator, reconcileListener, _ ->
            // 1. create a SP instance
            val spTestInstance = ShinyProxyTestInstance(
                namespace,
                namespacedClient,
                shinyProxyClient,
                "simple_config_subpath2.yaml",
                reconcileListener
            )
            spTestInstance.create()

            // 2. start the operator and let it do it's work
            val (resourceRetriever, shinyProxyLister) = operator.prepare()
            val job = GlobalScope.launch {
                operator.run(resourceRetriever, shinyProxyLister)
            }

            // 3. wait until instance is created
            spTestInstance.waitForOneReconcile()

            // 4. assert correctness

            spTestInstance.assertInstanceIsCorrect(1, true)

            // 5. additional assert correctness of ingress
            val ingresses = namespacedClient.inNamespace(namespace).network().v1().ingresses().list().items
            assertEquals(1, ingresses.size)
            assertTrue(ingresses.get(0).spec.rules.get(0).http.paths.get(0).path.endsWith("/"));

            job.cancel()
        }

    /**
     *  Test whether bug #23804 is solved.
     */
    @Test
    fun `reconcile of old instance should not update latestermarker and therefore delete old instance`() =
        setup(Mode.NAMESPACED) { namespace, shinyProxyClient, namespacedClient, stableClient, operator, reconcileListener, recyclableChecker ->
            if (chaosEnabled) return@setup // this test depends on timings and therefore it does not work with chaos enabled
            // 1. create a SP instance
            val spTestInstanceOriginal = ShinyProxyTestInstance(
                namespace,
                namespacedClient,
                shinyProxyClient,
                "simple_config.yaml",
                reconcileListener
            )
            spTestInstanceOriginal.create()

            // 2. start the operator and let it do it's work
            val (resourceRetriever, shinyProxyLister) = operator.prepare()
            val job = GlobalScope.launch {
                operator.run(resourceRetriever, shinyProxyLister)
            }

            // 3. wait until instance is created
            spTestInstanceOriginal.waitForOneReconcile()

            // 4. assert correctness
            spTestInstanceOriginal.assertInstanceIsCorrect()

            val sp = spTestInstanceOriginal.retrieveInstance()
            val originalSpInstance = sp.status.instances.first()

            // 5. update ShinyProxy instance
            logger.debug { "Base instance created -> updating it" }
            val spTestInstanceUpdated = ShinyProxyTestInstance(
                namespace,
                namespacedClient,
                shinyProxyClient,
                "simple_config_updated.yaml",
                reconcileListener
            )
            spTestInstanceUpdated.create()
            logger.debug { "Base instance created -> updated" }

            // 6. wait until the Operator added the new instance to the statuses
            while (true) {
                val sp = spTestInstanceOriginal.retrieveInstance()
                if (sp.status.instances.size == 2) {
                    break
                }
            }

            // 7. insert Reconcile of old instance
            operator.sendChannel.send(
                ShinyProxyEvent(
                    ShinyProxyEventType.RECONCILE,
                    spTestInstanceOriginal.retrieveInstance(),
                    originalSpInstance
                )
            )
            logger.debug { "Inserted reconcile" }

            // 8. wait for reconcile of old instance
            spTestInstanceOriginal.waitForOneReconcile()

            // 9. assert that status still points to old instance (the bug)
            val freshSP = spTestInstanceOriginal.retrieveInstance()
            assertEquals(2, freshSP.status.instances.size)
            assertTrue(freshSP.status.instances.firstOrNull { it.hashOfSpec == originalSpInstance.hashOfSpec }?.isLatestInstance == true)
            assertTrue(freshSP.status.instances.firstOrNull { it.hashOfSpec != originalSpInstance.hashOfSpec }?.isLatestInstance == false)

            // 6. wait until instance is created
            recyclableChecker.isRecyclable = true
            spTestInstanceUpdated.waitForOneReconcile()

            // 7. wait for delete to happen
            while (stableClient.pods().withName("sp-${sp.metadata.name}-pod-${spTestInstanceOriginal.hash}".take(63)).get() != null
                || stableClient.configMaps().withName("sp-${sp.metadata.name}-cm-${spTestInstanceOriginal.hash}".take(63)).get() != null
                || stableClient.services().withName("sp-${sp.metadata.name}-svc-${spTestInstanceOriginal.hash}".take(63)).get() != null) {
                delay(1000)
                logger.debug { "Pod still exists!" }
            }

            // 8. assert correctness
            spTestInstanceUpdated.assertInstanceIsCorrect()

            // 9. assert older instance does not exists anymore
            assertThrows<IllegalStateException>("Instance not found") {
                spTestInstanceOriginal.retrieveInstance()
            }

            job.cancel()
        }

    @Test
    fun `may no re-create instance after remove`() = setup(
        Mode.NAMESPACED,
    ) { namespace, shinyProxyClient, namespacedClient, _, operator, reconcileListener, _ ->
        if (chaosEnabled) return@setup // this test depends on timings and therefore it does not work with chaos enabled
        // 1. create a SP instance
        val spTestInstance = ShinyProxyTestInstance(
            namespace,
            namespacedClient,
            shinyProxyClient,
            "simple_config.yaml",
            reconcileListener
        )
        spTestInstance.create()

        val (resourceRetriever, shinyProxyLister) = operator.prepare()

        // 2. start the operator and let it do it's work
        val job = GlobalScope.launch {
            operator.run(resourceRetriever, shinyProxyLister)
        }

        // 3. wait until instance is created
        spTestInstance.waitForOneReconcile()

        // 4. stop the operator
        job.cancel()

        // take copy of old ShiynProxy, which still contains the (soon to be) deleted instance
        val sp = spTestInstance.retrieveInstance()
        val instance = spTestInstance.retrieveInstance().status.instances.first()

        // 5. schedule reconcile directly after deleting
        GlobalScope.launch {
            repeat(10) {
                delay(10)
                logger.debug { "Trying to trigger bug, by triggering reconcile with old status" }
                operator.shinyProxyController.reconcileSingleShinyProxyInstance(resourceRetriever, sp, instance)
            }
        }

        // 6. force delete the instance
        operator.shinyProxyController.deleteSingleShinyProxyInstance(resourceRetriever, sp, instance)

        // let it all work a bit
        withTimeout(50_000) {
            while (namespacedClient.inNamespace(namespace).apps().replicaSets().list().items.isNotEmpty()) {
                delay(1000)
                logger.debug { "Pod still exists!" }
            }
        }

        val replicaSets = namespacedClient.inNamespace(namespace).apps().replicaSets().list().items
        assertEquals(0, replicaSets.size)
    }

    @Test
    fun `restore old config version`() =
    // idea of test: launch instance A, update config to get instance B, and the update config again
        // using the same config as A, resulting in instance A' (which is the same instance as A, as A was never removed!)
        setup(Mode.NAMESPACED) { namespace, shinyProxyClient, namespacedClient, stableClient, operator, reconcileListener, recyclableChecker ->
            // 1. create a SP instance
            val instanceA = ShinyProxyTestInstance(
                namespace,
                namespacedClient,
                shinyProxyClient,
                "simple_config.yaml",
                reconcileListener
            )
            val spA = instanceA.create()

            val (resourceRetriever, shinyProxyLister) = operator.prepare()
            // 2. start the operator and let it do it's work
            val job = GlobalScope.launch {
                operator.run(resourceRetriever, shinyProxyLister)
            }

            // 3. wait until instance is created
            instanceA.waitForOneReconcile()

            // 4. assert correctness
            instanceA.assertInstanceIsCorrect()

            // 5. launch an app
            startApp(spA, instanceA)

            // 6. update ShinyProxy instance
            logger.debug { "Base instance created -> updating it" }
            val instanceB = ShinyProxyTestInstance(
                namespace,
                namespacedClient,
                shinyProxyClient,
                "simple_config_updated.yaml",
                reconcileListener
            )
            val spB = instanceB.create()
            logger.debug { "Base instance created -> updated" }

            // 7. wait until instance is created
            instanceB.waitForOneReconcile()

            // 7. wait for delete to not happen
            delay(5000)

            // 8. assert that two instances are correctly working
            instanceA.assertInstanceIsCorrect(2, false)
            instanceB.assertInstanceIsCorrect(2, true)

            // 9. update config to again have the config of A
            logger.debug { "Updating config to get A'" }
            val instanceAPrime = ShinyProxyTestInstance(
                namespace,
                namespacedClient,
                shinyProxyClient,
                "simple_config.yaml",
                reconcileListener
            )
            instanceAPrime.create()

            // 10. wait until instance is created
            instanceAPrime.waitForOneReconcile()

            // 11. wait for delete to happen
            recyclableChecker.isRecyclable = true
            while (stableClient.pods().withName("sp-${spB.metadata.name}-pod-${instanceB.hash}".take(63)).get() != null
                || stableClient.configMaps().withName("sp-${spB.metadata.name}-cm-${instanceB.hash}".take(63)).get() != null
                || stableClient.services().withName("sp-${spB.metadata.name}-svc-${instanceB.hash}".take(63)).get() != null) {
                delay(1000)
                logger.debug { "Pod still exists!" }
            }

            // 12. assert instance B does not exists anymore
            assertThrows<IllegalStateException>("Instance not found") {
                instanceB.retrieveInstance()
            }

            // 13. assert instance A' is correct
            instanceAPrime.assertInstanceIsCorrect(1, true)

            job.cancel()

        }

    // see #25154
    @Test
    fun `latest marker and ingress should be created in a single, atomic step`() = setup(Mode.NAMESPACED) { namespace, shinyProxyClient, namespacedClient, stableClient, operator, reconcileListener, _ ->
        if (chaosEnabled) return@setup // this test depends on timings and therefore it does not work with chaos enabled
        // 1. create a SP instance
        val spTestInstance = ShinyProxyTestInstance(
            namespace,
            namespacedClient,
            shinyProxyClient,
            "simple_config.yaml",
            reconcileListener
        )
        spTestInstance.create()

        // 2. prepare the operator
        val (resourceRetriever, shinyProxyLister) = operator.prepare()

        // 3. run the operator until the ReplicaSet has been created
        while (true) {
            // let the operator handle one event
            operator.shinyProxyController.receiveAndHandleEvent(resourceRetriever, shinyProxyLister)

            val replicaSets = stableClient.apps().replicaSets().list().items
            if (replicaSets.size >= 1) {
                // if replicaset is not created -> continue handling events
                break
            }
        }
        var replicaSets = stableClient.apps().replicaSets().list().items
        assertEquals(1, replicaSets.size)

        // 4. check state
        // A) when the ReplicaSet has been created (but is not yer ready), the service should not exist yet
        var services = stableClient.services().list().items
        assertEquals(0, services.size)

        // B) at this point the latestMarker should not be set yet
        var sp = shinyProxyClient.inNamespace(namespace).list().items[0]
        assertNotNull(sp)
        assertEquals(1, sp.status.instances.size)
        assertEquals(false, sp.status.instances[0].isLatestInstance)

        // C) at this point the ingress should not exist yet
        var ingresses = stableClient.inNamespace(namespace).network().v1().ingresses().list().items
        assertEquals(0, ingresses.size)

        // 5. wait for the replicaset to become ready
        while (true) {
            replicaSets = stableClient.apps().replicaSets().list().items
            val replicaSet = replicaSets[0]
            if (Readiness.getInstance().isReady(replicaSet)) {
                break
            }
            delay(1_000)
        }

        // Test starts here:

        // 6. handle one more event -> should set the latest marker, create the service and ingress
        operator.shinyProxyController.receiveAndHandleEvent(resourceRetriever, shinyProxyLister)

        // 7. check state:
        // A) at this point the service should have been created
        services = stableClient.services().list().items
        assertEquals(1, services.size)

        // B) at this point the latestMarker should be set
        sp = shinyProxyClient.inNamespace(namespace).list().items[0]
        assertNotNull(sp)
        assertEquals(1, sp.status.instances.size)
        assertEquals(true, sp.status.instances[0].isLatestInstance)

        // C) at this point the ingress should exist
        ingresses = stableClient.inNamespace(namespace).network().v1().ingresses().list().items
        assertEquals(1, ingresses.size)
    }


    // see #25161
    @Test
    fun `operator should properly handle 409 conflicts by replacing the resource`() =
        setup(Mode.NAMESPACED) { namespace, shinyProxyClient, namespacedClient, stableClient, operator, reconcileListener, _ ->
            // 1. create conflicting resources
            stableClient.load(this.javaClass.getResourceAsStream("/configs/conflict.yaml")).serverSideApply()

            // 2. create a SP instance
            val spTestInstance = ShinyProxyTestInstance(
                namespace,
                namespacedClient,
                shinyProxyClient,
                "simple_config.yaml",
                reconcileListener
            )
            spTestInstance.create()

            val (resourceRetriever, shinyProxyLister) = operator.prepare()

            // 3. start the operator and let it do it's work
            val job = GlobalScope.launch {
                operator.run(resourceRetriever, shinyProxyLister)
            }

            // 4. wait until instance is created
            spTestInstance.waitForOneReconcile()

            // 5. assert correctness
            spTestInstance.assertInstanceIsCorrect()
            job.cancel()
        }

    @Test
    fun `operator should have correct antiAffinity defaults`() =
        setup(Mode.NAMESPACED) { namespace, shinyProxyClient, namespacedClient, stableClient, operator, reconcileListener, _ ->
            // 1. create a SP instance
            val spTestInstance = ShinyProxyTestInstance(
                namespace,
                namespacedClient,
                shinyProxyClient,
                "simple_config.yaml",
                reconcileListener
            )
            val sp = spTestInstance.create()

            val (resourceRetriever, shinyProxyLister) = operator.prepare()

            // 3. start the operator and let it do it's work
            val job = GlobalScope.launch {
                operator.run(resourceRetriever, shinyProxyLister)
            }

            // 4. wait until instance is created
            spTestInstance.waitForOneReconcile()

            // 5. assert correctness
            spTestInstance.assertInstanceIsCorrect()

            // 6. check antiAffinity rules
            val replicaSets = stableClient.inNamespace(namespace).apps().replicaSets().list().items
            assertEquals(1, replicaSets.size)
            val replicaSet = replicaSets.firstOrNull { it.metadata.labels[LabelFactory.INSTANCE_LABEL] == spTestInstance.hash }
            assertNotNull(replicaSet)

            assertNotNull(replicaSet.spec.template.spec.affinity)
            assertNotNull(replicaSet.spec.template.spec.affinity.podAntiAffinity)
            assertNull(replicaSet.spec.template.spec.affinity.podAffinity)
            assertNull(replicaSet.spec.template.spec.affinity.nodeAffinity)
            assertEquals(0, replicaSet.spec.template.spec.affinity.podAntiAffinity.requiredDuringSchedulingIgnoredDuringExecution.size)
            assertNotNull(replicaSet.spec.template.spec.affinity.podAntiAffinity.preferredDuringSchedulingIgnoredDuringExecution)
            assertEquals(1, replicaSet.spec.template.spec.affinity.podAntiAffinity.preferredDuringSchedulingIgnoredDuringExecution.size)
            val rule = replicaSet.spec.template.spec.affinity.podAntiAffinity.preferredDuringSchedulingIgnoredDuringExecution[0]
            assertEquals(1, rule.weight)
            assertEquals("kubernetes.io/hostname", rule.podAffinityTerm.topologyKey)
            assertEquals(mapOf(
                LabelFactory.APP_LABEL to LabelFactory.APP_LABEL_VALUE,
                LabelFactory.REALM_ID_LABEL to sp.realmId,
                LabelFactory.INSTANCE_LABEL to spTestInstance.hash
            ), rule.podAffinityTerm.labelSelector.matchLabels)

            job.cancel()
        }

    @Test
    fun `operator should have correct antiAffinity when required is true`() =
        setup(Mode.NAMESPACED) { namespace, shinyProxyClient, namespacedClient, stableClient, operator, reconcileListener, _ ->
            // 1. create a SP instance
            val spTestInstance = ShinyProxyTestInstance(
                namespace,
                namespacedClient,
                shinyProxyClient,
                "affinity_required.yaml",
                reconcileListener
            )
            val sp = spTestInstance.create()

            val (resourceRetriever, shinyProxyLister) = operator.prepare()

            // 3. start the operator and let it do it's work
            val job = GlobalScope.launch {
                operator.run(resourceRetriever, shinyProxyLister)
            }

            // 4. wait until instance is created
            spTestInstance.waitForOneReconcile()

            // 5. assert correctness
            spTestInstance.assertInstanceIsCorrect()

            // 6. check antiAffinity rules
            val replicaSets = stableClient.inNamespace(namespace).apps().replicaSets().list().items
            assertEquals(1, replicaSets.size)
            val replicaSet = replicaSets.firstOrNull { it.metadata.labels[LabelFactory.INSTANCE_LABEL] == spTestInstance.hash }
            assertNotNull(replicaSet)

            assertNotNull(replicaSet.spec.template.spec.affinity)
            assertNotNull(replicaSet.spec.template.spec.affinity.podAntiAffinity)
            assertNull(replicaSet.spec.template.spec.affinity.podAffinity)
            assertNull(replicaSet.spec.template.spec.affinity.nodeAffinity)
            assertEquals(0, replicaSet.spec.template.spec.affinity.podAntiAffinity.preferredDuringSchedulingIgnoredDuringExecution.size)
            assertEquals(1, replicaSet.spec.template.spec.affinity.podAntiAffinity.requiredDuringSchedulingIgnoredDuringExecution.size)
            assertNotNull(replicaSet.spec.template.spec.affinity.podAntiAffinity.requiredDuringSchedulingIgnoredDuringExecution)
            val rule = replicaSet.spec.template.spec.affinity.podAntiAffinity.requiredDuringSchedulingIgnoredDuringExecution[0]
            assertEquals("kubernetes.io/hostname", rule.topologyKey)
            assertEquals(mapOf(
                LabelFactory.APP_LABEL to LabelFactory.APP_LABEL_VALUE,
                LabelFactory.REALM_ID_LABEL to sp.realmId,
                LabelFactory.INSTANCE_LABEL to spTestInstance.hash
            ), rule.labelSelector.matchLabels)

            job.cancel()
        }


    @Test
    fun `operator should have correct antiAffinity when topologyKey is set`() =
        setup(Mode.NAMESPACED) { namespace, shinyProxyClient, namespacedClient, stableClient, operator, reconcileListener, _ ->
            // 1. create a SP instance
            val spTestInstance = ShinyProxyTestInstance(
                namespace,
                namespacedClient,
                shinyProxyClient,
                "affinity_topologykey.yaml",
                reconcileListener
            )
            val sp = spTestInstance.create()

            val (resourceRetriever, shinyProxyLister) = operator.prepare()

            // 3. start the operator and let it do it's work
            val job = GlobalScope.launch {
                operator.run(resourceRetriever, shinyProxyLister)
            }

            // 4. wait until instance is created
            spTestInstance.waitForOneReconcile()

            // 5. assert correctness
            spTestInstance.assertInstanceIsCorrect()

            // 6. check antiAffinity rules
            val replicaSets = stableClient.inNamespace(namespace).apps().replicaSets().list().items
            assertEquals(1, replicaSets.size)
            val replicaSet = replicaSets.firstOrNull { it.metadata.labels[LabelFactory.INSTANCE_LABEL] == spTestInstance.hash }
            assertNotNull(replicaSet)

            assertNotNull(replicaSet.spec.template.spec.affinity)
            assertNotNull(replicaSet.spec.template.spec.affinity.podAntiAffinity)
            assertNull(replicaSet.spec.template.spec.affinity.podAffinity)
            assertNull(replicaSet.spec.template.spec.affinity.nodeAffinity)
            assertEquals(0, replicaSet.spec.template.spec.affinity.podAntiAffinity.requiredDuringSchedulingIgnoredDuringExecution.size)
            assertNotNull(replicaSet.spec.template.spec.affinity.podAntiAffinity.preferredDuringSchedulingIgnoredDuringExecution)
            assertEquals(1, replicaSet.spec.template.spec.affinity.podAntiAffinity.preferredDuringSchedulingIgnoredDuringExecution.size)
            val rule = replicaSet.spec.template.spec.affinity.podAntiAffinity.preferredDuringSchedulingIgnoredDuringExecution[0]
            assertEquals(1, rule.weight)
            assertEquals("example.com/custom-topology-key", rule.podAffinityTerm.topologyKey)
            assertEquals(mapOf(
                LabelFactory.APP_LABEL to LabelFactory.APP_LABEL_VALUE,
                LabelFactory.REALM_ID_LABEL to sp.realmId,
                LabelFactory.INSTANCE_LABEL to spTestInstance.hash
            ), rule.podAffinityTerm.labelSelector.matchLabels)

            job.cancel()
        }

    @Test
    fun `test additional fqns`() =
        setup(Mode.NAMESPACED) { namespace, shinyProxyClient, namespacedClient, _, operator, reconcileListener, _ ->
            // 1. create a SP instance
            val spTestInstance = ShinyProxyTestInstance(
                namespace,
                namespacedClient,
                shinyProxyClient,
                "additional_fqdns.yaml",
                reconcileListener
            )
            spTestInstance.create()

            val (resourceRetriever, shinyProxyLister) = operator.prepare()
            // 2. start the operator and let it do it's work
            val job = GlobalScope.launch {
                operator.run(resourceRetriever, shinyProxyLister)
            }

            // 3. wait until instance is created
            spTestInstance.waitForOneReconcile()

            // 4. assert correctness
            val sp = spTestInstance.retrieveInstance()
            assertNotNull(sp)
            val instance = sp.status.instances[0]
            assertNotNull(instance)
            assertTrue(instance.isLatestInstance)

            // check configmap
            spTestInstance.assertConfigMapIsCorrect(sp)

           // check replicaset
            spTestInstance.assertReplicaSetIsCorrect(sp)

            // check service
            spTestInstance.assertServiceIsCorrect(spTestInstance.retrieveInstance())

            // check ingress
            val allIngresses = namespacedClient.network().v1().ingresses().list().items
            assertEquals(1, allIngresses.size)
            val ingress = allIngresses.firstOrNull { it.metadata.name == "sp-${sp.metadata.name}-ing".take(63) }
            assertNotNull(ingress)

            assertEquals(mapOf(
                LabelFactory.APP_LABEL to LabelFactory.APP_LABEL_VALUE,
                LabelFactory.REALM_ID_LABEL to sp.realmId,
                LabelFactory.LATEST_INSTANCE_LABEL to sp.status.latestInstance()!!.hashOfSpec
            ), ingress.metadata.labels)

            assertEquals(2, ingress.spec.rules.size)
            val rule1 = ingress.spec.rules[0]
            assertNotNull(rule1)
            assertEquals(sp.fqdn, rule1.host)
            assertEquals(1, rule1.http.paths.size)
            val path1 = rule1.http.paths[0]
            assertNotNull(path1)
            assertEquals(sp.subPath, path1.path)
            assertEquals("sp-${sp.metadata.name}-svc".take(63), path1.backend.service.name)
            assertEquals(80, path1.backend.service.port.number)

            val rule2 = ingress.spec.rules[1]
            assertNotNull(rule2)
            assertEquals(sp.additionalFqdns[0], rule2.host)
            assertEquals(1, rule2.http.paths.size)
            val path2 = rule2.http.paths[0]
            assertNotNull(path2)
            assertEquals(sp.subPath, path2.path)
            assertEquals("sp-${sp.metadata.name}-svc".take(63), path2.backend.service.name)
            assertEquals(80, path2.backend.service.port.number)

            job.cancel()
        }


}
