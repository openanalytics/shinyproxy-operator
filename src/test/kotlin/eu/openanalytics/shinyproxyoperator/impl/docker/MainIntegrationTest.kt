/*
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021-2025 Open Analytics
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
package eu.openanalytics.shinyproxyoperator.impl.docker

import eu.openanalytics.shinyproxyoperator.controller.EventController
import eu.openanalytics.shinyproxyoperator.helpers.awaitWithTimeout
import eu.openanalytics.shinyproxyoperator.impl.docker.helpers.IntegrationTestBase
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Ignore
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MainIntegrationTest : IntegrationTestBase() {

    @Test
    fun `simple test`() = setup { dataDir, inputDir, operator, eventController, dockerAssertions, _, _ ->
        val hash = createInputFile(inputDir, "simple_config.yaml", "realm1.shinyproxy.yaml")
        val shinyProxyInstance = ShinyProxyInstance("realm1", "default", "default-realm1", hash, true, 0)

        scope.launch {
            operator.init()
            operator.run()
        }

        eventController.waitForNextReconcile(hash)

        val shinyProxyContainer = getSingleShinyProxyContainer(shinyProxyInstance)
        dockerAssertions.assertRedisContainer()
        dockerAssertions.assertCaddyContainer("simple_test_caddy.json", mapOf("#CONTAINER_IP#" to shinyProxyContainer.getSharedNetworkIpAddress()!!))
        dockerAssertions.assertShinyProxyContainer(shinyProxyContainer, shinyProxyInstance)
    }

    @ValueSource(strings = ["simple_config_public_subpath.yaml", "simple_config_public_subpath2.yaml"])
    @ParameterizedTest
    fun `two instances with subpath`(file: String) = setup { dataDir, inputDir, operator, eventController, dockerAssertions, _, _ ->
        val hash1 = createInputFile(inputDir, "simple_config.yaml", "realm1.shinyproxy.yaml")
        val shinyProxyInstance1 = ShinyProxyInstance("realm1", "default", "default-realm1", hash1, true, 0)
        val hash2 = createInputFile(inputDir, file, "realm2.shinyproxy.yaml")
        val shinyProxyInstance2 = ShinyProxyInstance("realm2", "default", "default-realm2", hash2, true, 0)

        scope.launch {
            operator.init()
            operator.run()
        }

        eventController.waitForNextReconcile(hash1)
        eventController.waitForNextReconcile(hash2)

        val shinyProxyContainer1 = getSingleShinyProxyContainer(shinyProxyInstance1)
        val shinyProxyContainer2 = getSingleShinyProxyContainer(shinyProxyInstance2)

        dockerAssertions.assertRedisContainer()
        dockerAssertions.assertCaddyContainer("two_instances_with_subpath.json", mapOf(
            "#CONTAINER_IP#" to shinyProxyContainer1.getSharedNetworkIpAddress()!!,
            "#CONTAINER_IP_PUBLIC#" to shinyProxyContainer2.getSharedNetworkIpAddress()!!
        ))
        dockerAssertions.assertShinyProxyContainer(shinyProxyContainer1, shinyProxyInstance1)
        dockerAssertions.assertShinyProxyContainer(shinyProxyContainer2, shinyProxyInstance2)
    }

    @Test
    fun `update without apps running`() = setup { dataDir, inputDir, operator, eventController, dockerAssertions, recyclableChecker, _ ->
        val hash = createInputFile(inputDir, "simple_config.yaml", "realm1.shinyproxy.yaml")
        val shinyProxyInstance = ShinyProxyInstance("realm1", "default", "default-realm1", hash, true, 0)

        scope.launch {
            operator.init()
            operator.run()
        }

        eventController.waitForNextReconcile(hash)

        val shinyProxyContainer = getSingleShinyProxyContainer(shinyProxyInstance)
        dockerAssertions.assertRedisContainer()
        dockerAssertions.assertCaddyContainer("simple_test_caddy.json", mapOf("#CONTAINER_IP#" to shinyProxyContainer.getSharedNetworkIpAddress()!!))
        dockerAssertions.assertShinyProxyContainer(shinyProxyContainer, shinyProxyInstance)

        // update ShinyProxy instance
        val updatedHash = createInputFile(inputDir, "simple_config_updated.yaml", "realm1.shinyproxy.yaml")
        val updatedShinyProxyInstance = ShinyProxyInstance("realm1", "default", "default-realm1", updatedHash, true, 0)
        eventController.waitForNextReconcile(updatedHash)

        val updatedShinyProxyContainer = getSingleShinyProxyContainer(updatedShinyProxyInstance)
        dockerAssertions.assertRedisContainer()
        dockerAssertions.assertCaddyContainer("simple_test_caddy.json", mapOf("#CONTAINER_IP#" to updatedShinyProxyContainer.getSharedNetworkIpAddress()!!))

        recyclableChecker.isRecyclable = true
        eventController.waitForDeletion(hash).awaitWithTimeout()
        assertEquals(0, dockerActions.getContainers(shinyProxyInstance).size)
    }

    @Test
    fun `update with apps running`() = setup { dataDir, inputDir, operator, eventController, dockerAssertions, recyclableChecker, _ ->
        val hash = createInputFile(inputDir, "simple_config.yaml", "realm1.shinyproxy.yaml")
        val shinyProxyInstance = ShinyProxyInstance("realm1", "default", "default-realm1", hash, true, 0)

        scope.launch {
            operator.init()
            operator.run()
        }

        eventController.waitForNextReconcile(hash)

        val shinyProxyContainer = getSingleShinyProxyContainer(shinyProxyInstance)
        dockerAssertions.assertRedisContainer()
        dockerAssertions.assertCaddyContainer("simple_test_caddy.json", mapOf("#CONTAINER_IP#" to shinyProxyContainer.getSharedNetworkIpAddress()!!))
        dockerAssertions.assertShinyProxyContainer(shinyProxyContainer, shinyProxyInstance)

        // start app
        val proxyId = startApp(shinyProxyInstance)
        recyclableChecker.isRecyclable = false

        // update ShinyProxy instance
        val updatedHash = createInputFile(inputDir, "simple_config_updated.yaml", "realm1.shinyproxy.yaml")
        val updatedShinyProxyInstance = ShinyProxyInstance("realm1", "default", "default-realm1", updatedHash, true, 0)
        eventController.waitForNextReconcile(updatedHash)

        val updatedShinyProxyContainer = getSingleShinyProxyContainer(updatedShinyProxyInstance)
        dockerAssertions.assertRedisContainer()
        dockerAssertions.assertCaddyContainer("simple_test_caddy.json", mapOf("#CONTAINER_IP#" to updatedShinyProxyContainer.getSharedNetworkIpAddress()!!))

        // wait for delete to not happen
        delay(5000)

        recyclableChecker.isRecyclable = true

        // wait for deletion to happen
        eventController.waitForDeletion(hash).awaitWithTimeout()
        assertEquals(0, dockerActions.getContainers(shinyProxyInstance).size)

        // check that app is still running
        assertAppIsRunning(updatedShinyProxyInstance, proxyId)
    }

    @Test
    fun `restore old config version when new instance fails`() = setup { dataDir, inputDir, operator, eventController, dockerAssertions, recyclableChecker, _ ->
        // see #33546
        // idea of test: launch instance A, update config to get instance B, however, instance B fails to start up, and then update config again to A
        // the operator will start a new instance, with an increased revision
        // 1. create a SP instance
        val hashA = createInputFile(inputDir, "simple_config.yaml", "realm1.shinyproxy.yaml")
        val shinyProxyInstanceA = ShinyProxyInstance("realm1", "default", "default-realm1", hashA, true, 0)

        // 2. start the operator and let it do it's work
        scope.launch {
            operator.init()
            operator.run()
        }

        // 3. wait until instance is created
        eventController.waitForNextReconcile(hashA)

        // 4. assert correctness
        val shinyProxyContainerA = getSingleShinyProxyContainer(shinyProxyInstanceA)
        dockerAssertions.assertRedisContainer()
        dockerAssertions.assertCaddyContainer("simple_test_caddy.json", mapOf("#CONTAINER_IP#" to shinyProxyContainerA.getSharedNetworkIpAddress()!!))
        dockerAssertions.assertShinyProxyContainer(shinyProxyContainerA, shinyProxyInstanceA)

        // 5. update ShinyProxy instance
        val hashB = createInputFile(inputDir, "simple_config_failure.yaml", "realm1.shinyproxy.yaml")
        val shinyProxyInstanceB = ShinyProxyInstance("realm1", "default", "default-realm1", hashB, true, 0)

        // 6. wait for instance to startup (startup will fail)
        val result = eventController.waitForNextReconcile(hashB)
        assertEquals("StartingNewInstanceFailed", result)

        // 7. check that caddy still points to original instance
        dockerAssertions.assertCaddyContainer("simple_test_caddy.json", mapOf("#CONTAINER_IP#" to shinyProxyContainerA.getSharedNetworkIpAddress()!!))

        // 8. update config to again have the config of A
        createInputFile(inputDir, "simple_config.yaml", "realm1.shinyproxy.yaml")
        val shinyProxyInstanceAPrime = ShinyProxyInstance("realm1", "default", "default-realm1", hashA, true, 1)

        // 9. wait until instance is created
        eventController.waitForNextReconcile(hashA, 1)

        // 10. check correctness of instance A'
        val shinyProxyContainerAPrime = getSingleShinyProxyContainer(shinyProxyInstanceAPrime)
        dockerAssertions.assertCaddyContainer("simple_test_caddy.json", mapOf("#CONTAINER_IP#" to shinyProxyContainerAPrime.getSharedNetworkIpAddress()!!))
        dockerAssertions.assertShinyProxyContainer(shinyProxyContainerAPrime, shinyProxyInstanceAPrime)

        // 10. wait for delete of instance A to happen
        val deleteAFuture = eventController.waitForDeletion(hashA)
        val deleteBFuture = eventController.waitForDeletion(hashB)
        recyclableChecker.isRecyclable = true
        deleteAFuture.awaitWithTimeout()
        deleteBFuture.awaitWithTimeout()

        // 11. check instances are removed
        assertEquals(0, dockerActions.getContainers(shinyProxyInstanceA).size)
        assertEquals(0, dockerActions.getContainers(shinyProxyInstanceB).size)

        // 12. check caddy still points to A'
        dockerAssertions.assertCaddyContainer("simple_test_caddy.json", mapOf("#CONTAINER_IP#" to shinyProxyContainerAPrime.getSharedNetworkIpAddress()!!))
    }

    @Test
    fun `operator works after invalid config`() = setup { dataDir, inputDir, operator, eventController, dockerAssertions, recyclableChecker, _ ->
        // idea of test: launch instance A, update config to get instance B, however, instance B has invalid yaml
        // 1. create a SP instance
        val hashA = createInputFile(inputDir, "simple_config.yaml", "realm1.shinyproxy.yaml")
        val shinyProxyInstanceA = ShinyProxyInstance("realm1", "default", "default-realm1", hashA, true, 0)

        // 2. start the operator and let it do it's work
        scope.launch {
            operator.init()
            operator.run()
        }

        // 3. wait until instance is created
        eventController.waitForNextReconcile(hashA)

        // 4. assert correctness
        val shinyProxyContainerA = getSingleShinyProxyContainer(shinyProxyInstanceA)
        dockerAssertions.assertRedisContainer()
        dockerAssertions.assertCaddyContainer("simple_test_caddy.json", mapOf("#CONTAINER_IP#" to shinyProxyContainerA.getSharedNetworkIpAddress()!!))
        dockerAssertions.assertShinyProxyContainer(shinyProxyContainerA, shinyProxyInstanceA)

        // 5. update ShinyProxy instance
        val hashB = createInputFile(inputDir, "simple_config_input_error.yaml", "realm1.shinyproxy.yaml")
        val shinyProxyInstanceB = ShinyProxyInstance("realm1", "default", "default-realm1", hashB, true, 0)

        // 6. wait for instance to startup (startup will fail)
        val error = eventController.waitForInputError()
        assertEquals("Failed to read file 'realm1.shinyproxy.yaml', error: 'No or invalid realm-id'", error)

        // 7. check that caddy still points to original instance
        dockerAssertions.assertCaddyContainer("simple_test_caddy.json", mapOf("#CONTAINER_IP#" to shinyProxyContainerA.getSharedNetworkIpAddress()!!))

        // 8. update config to again have the config of A
        createInputFile(inputDir, "simple_config.yaml", "realm1.shinyproxy.yaml")

        // 9. wait until instance is created (just a reconcile, no new instance)
        val result = eventController.waitForNextReconcile(hashA)
        assertEquals("Reconciled", result)

        // 10. update ShinyProxy instance
        val updatedHash = createInputFile(inputDir, "simple_config_updated.yaml", "realm1.shinyproxy.yaml")
        val updatedShinyProxyInstance = ShinyProxyInstance("realm1", "default", "default-realm1", updatedHash, true, 0)
        eventController.waitForNextReconcile(updatedHash)

        val updatedShinyProxyContainer = getSingleShinyProxyContainer(updatedShinyProxyInstance)
        dockerAssertions.assertShinyProxyContainer(updatedShinyProxyContainer, updatedShinyProxyInstance)
        dockerAssertions.assertRedisContainer()
        dockerAssertions.assertCaddyContainer("simple_test_caddy.json", mapOf("#CONTAINER_IP#" to updatedShinyProxyContainer.getSharedNetworkIpAddress()!!))

        recyclableChecker.isRecyclable = true
        eventController.waitForDeletion(hashA).awaitWithTimeout()
        assertEquals(0, dockerActions.getContainers(shinyProxyInstanceA).size)
    }

    @Test
    fun `delete realm`() = setup { dataDir, inputDir, operator, eventController, dockerAssertions, _, _ ->
        // 1. create a SP instance
        val hash = createInputFile(inputDir, "simple_config.yaml", "realm1.shinyproxy.yaml")
        val shinyProxyInstance = ShinyProxyInstance("realm1", "default", "default-realm1", hash, true, 0)

        // 2. start the operator and let it do it's work
        scope.launch {
            operator.init()
            operator.run()
        }

        // 3. wait until instance is created
        eventController.waitForNextReconcile(hash)

        // 4. assert correctness
        val shinyProxyContainer = getSingleShinyProxyContainer(shinyProxyInstance)
        dockerAssertions.assertRedisContainer()
        dockerAssertions.assertCaddyContainer("simple_test_caddy.json", mapOf("#CONTAINER_IP#" to shinyProxyContainer.getSharedNetworkIpAddress()!!))
        dockerAssertions.assertShinyProxyContainer(shinyProxyContainer, shinyProxyInstance)

        // 5. update ShinyProxy instance
        val updatedHash = createInputFile(inputDir, "simple_config_updated.yaml", "realm1.shinyproxy.yaml")
        val updatedShinyProxyInstance = ShinyProxyInstance("realm1", "default", "default-realm1", updatedHash, true, 0)
        eventController.waitForNextReconcile(updatedHash)

        // 6. wait until instance is created
        val updatedShinyProxyContainer = getSingleShinyProxyContainer(updatedShinyProxyInstance)
        dockerAssertions.assertShinyProxyContainer(updatedShinyProxyContainer, updatedShinyProxyInstance)

        // 7. delete realm
        deleteInputFile(inputDir, "realm1.shinyproxy.yaml")

        delay(30_000)

        // check shinyproxy containers removed
        assertEquals(0, dockerActions.getContainers(shinyProxyInstance).size)
        assertEquals(0, dockerActions.getContainers(updatedShinyProxyInstance).size)

        // redis + caddy should stay running
        dockerAssertions.assertRedisContainer()
        dockerAssertions.assertCaddyContainer("empty_caddy.yaml", mapOf())
    }

    @Test
    fun `restart operator during (failing) update`() = setup { dataDir, inputDir, operator, eventController, dockerAssertions, recyclableChecker, config ->
        // idea of test: launch instance A, update config to get instance B, however, instance B fails to start up, in the meantime restart the operator and then update config again to A
        // the operator will start a new instance, with an increased revision
        // 1. create a SP instance
        val hashA = createInputFile(inputDir, "simple_config.yaml", "realm1.shinyproxy.yaml")
        val shinyProxyInstanceA = ShinyProxyInstance("realm1", "default", "default-realm1", hashA, true, 0)

        // 2. start the operator and let it do it's work
        val runner = scope.launch {
            operator.init()
            operator.run()
        }

        // 3. wait until instance is created
        eventController.waitForNextReconcile(hashA)

        // 4. assert correctness
        val shinyProxyContainerA = getSingleShinyProxyContainer(shinyProxyInstanceA)
        dockerAssertions.assertRedisContainer()
        dockerAssertions.assertCaddyContainer("simple_test_caddy.json", mapOf("#CONTAINER_IP#" to shinyProxyContainerA.getSharedNetworkIpAddress()!!))
        dockerAssertions.assertShinyProxyContainer(shinyProxyContainerA, shinyProxyInstanceA)

        // 5. update ShinyProxy instance
        val hashB = createInputFile(inputDir, "simple_config_failure.yaml", "realm1.shinyproxy.yaml")
        val shinyProxyInstanceB = ShinyProxyInstance("realm1", "default", "default-realm1", hashB, true, 0)

        // 6. stop operator once instance is being started
        eventController.waitForNewInstance(hashB)
        delay(5_000)
        runner.cancel()
        operator.stop()
        delay(10_000)

        // 7. restart operator
        val operator2 = DockerOperator(config, eventController, recyclableChecker)
        eventController.setDelegate(EventController(operator2.orchestrator))
        scope.launch {
            eventController.setDelegate(EventController(operator2.orchestrator))
            operator2.init()
            operator2.run()
        }
        dockerAssertions.assertCaddyContainer("simple_test_caddy.json", mapOf("#CONTAINER_IP#" to shinyProxyContainerA.getSharedNetworkIpAddress()!!))

        // 7. wait for new instance to be created
        val shinyProxyInstanceBPrime = eventController.waitForNewInstance(hashB, 1)
        assertEquals(hashB, shinyProxyInstanceBPrime.hashOfSpec)
        assertEquals(1, shinyProxyInstanceBPrime.revision)
        dockerAssertions.assertCaddyContainer("simple_test_caddy.json", mapOf("#CONTAINER_IP#" to shinyProxyContainerA.getSharedNetworkIpAddress()!!))

        // 8. wait for instance to startup (startup will fail)
        val result = eventController.waitForNextReconcile(hashB, 1)
        assertEquals("StartingNewInstanceFailed", result)
        dockerAssertions.assertCaddyContainer("simple_test_caddy.json", mapOf("#CONTAINER_IP#" to shinyProxyContainerA.getSharedNetworkIpAddress()!!))

        // 10. update config to again have the config of A
        createInputFile(inputDir, "simple_config.yaml", "realm1.shinyproxy.yaml")
        val shinyProxyInstanceAPrime = ShinyProxyInstance("realm1", "default", "default-realm1", hashA, true, 1)

        // 11. wait until instance is created
        eventController.waitForNextReconcile(hashA, 1)

        // 12. check correctness of instance A'
        val shinyProxyContainerAPrime = getSingleShinyProxyContainer(shinyProxyInstanceAPrime)
        dockerAssertions.assertCaddyContainer("simple_test_caddy.json", mapOf("#CONTAINER_IP#" to shinyProxyContainerAPrime.getSharedNetworkIpAddress()!!))
        dockerAssertions.assertShinyProxyContainer(shinyProxyContainerAPrime, shinyProxyInstanceAPrime)

        // 13. wait for delete of instance A to happen
        val deleteAFuture = eventController.waitForDeletion(hashA)
        val deleteBFuture = eventController.waitForDeletion(hashB)
        val deleteBPrimeFuture = eventController.waitForDeletion(hashB, 1)
        recyclableChecker.isRecyclable = true
        deleteAFuture.awaitWithTimeout()
        deleteBFuture.awaitWithTimeout()
        deleteBPrimeFuture.awaitWithTimeout()

        // 14. check instances are removed
        assertEquals(0, dockerActions.getContainers(shinyProxyInstanceA).size)
        assertEquals(0, dockerActions.getContainers(shinyProxyInstanceB).size)
        assertEquals(0, dockerActions.getContainers(shinyProxyInstanceBPrime).size)

        // 15. check caddy still points to A'
        dockerAssertions.assertCaddyContainer("simple_test_caddy.json", mapOf("#CONTAINER_IP#" to shinyProxyContainerAPrime.getSharedNetworkIpAddress()!!))
        operator2.stop()
    }

    @Test
    fun `restart operator during (failing) update without state file`() = setup { dataDir, inputDir, operator, eventController, dockerAssertions, recyclableChecker, config ->
        // idea of test: launch instance A, update config to get instance B, however, instance B fails to start up, in the meantime restart the operator and then update config again to A
        // the operator will start a new instance, with an increased revision
        dataDir.resolve("state.yaml").writeText("redisPassword: 75ZEykd5RYsZS7v9H7PYCaFcrscJHWa4\n")
        // 1. create a SP instance
        val hashA = createInputFile(inputDir, "simple_config.yaml", "realm1.shinyproxy.yaml")
        val shinyProxyInstanceA = ShinyProxyInstance("realm1", "default", "default-realm1", hashA, true, 0)

        // 2. start the operator and let it do it's work
        val runner = scope.launch {
            operator.init()
            operator.run()
        }

        // 3. wait until instance is created
        eventController.waitForNextReconcile(hashA)

        // 4. assert correctness
        val shinyProxyContainerA = getSingleShinyProxyContainer(shinyProxyInstanceA)
        dockerAssertions.assertRedisContainer()
        dockerAssertions.assertCaddyContainer("simple_test_caddy.json", mapOf("#CONTAINER_IP#" to shinyProxyContainerA.getSharedNetworkIpAddress()!!))
        dockerAssertions.assertShinyProxyContainer(shinyProxyContainerA, shinyProxyInstanceA)

        // 5. update ShinyProxy instance
        val hashB = createInputFile(inputDir, "simple_config_failure.yaml", "realm1.shinyproxy.yaml")
        val shinyProxyInstanceB = ShinyProxyInstance("realm1", "default", "default-realm1", hashB, true, 0)

        // 6. stop operator once instance is being started
        eventController.waitForNewInstance(hashB)
        delay(5_000)
        runner.cancel()
        operator.stop()

        dataDir.resolve("state.yaml").deleteIfExists()
        dataDir.resolve("state.yaml").writeText("redisPassword: 75ZEykd5RYsZS7v9H7PYCaFcrscJHWa4\n")
        delay(10_000)

        // 7. restart operator
        val operator2 = DockerOperator(config, eventController, recyclableChecker)
        eventController.setDelegate(EventController(operator2.orchestrator))
        scope.launch {
            eventController.setDelegate(EventController(operator2.orchestrator))
            operator2.init()
            operator2.run()
        }
        dockerAssertions.assertCaddyContainer("simple_test_caddy.json", mapOf("#CONTAINER_IP#" to shinyProxyContainerA.getSharedNetworkIpAddress()!!))

        // 8. wait for instance to startup (startup will fail)
        val result = eventController.waitForNextReconcile(hashB)
        assertEquals("StartingNewInstanceFailed", result)
        dockerAssertions.assertCaddyContainer("simple_test_caddy.json", mapOf("#CONTAINER_IP#" to shinyProxyContainerA.getSharedNetworkIpAddress()!!))

        // 9. update config to again have the config of A
        createInputFile(inputDir, "simple_config.yaml", "realm1.shinyproxy.yaml")
        val shinyProxyInstanceAPrime = ShinyProxyInstance("realm1", "default", "default-realm1", hashA, true, 1)

        // 10. wait until instance is created
        eventController.waitForNextReconcile(hashA, 1)

        // 11. check correctness of instance A'
        val shinyProxyContainerAPrime = getSingleShinyProxyContainer(shinyProxyInstanceAPrime)
        dockerAssertions.assertCaddyContainer("simple_test_caddy.json", mapOf("#CONTAINER_IP#" to shinyProxyContainerAPrime.getSharedNetworkIpAddress()!!))
        dockerAssertions.assertShinyProxyContainer(shinyProxyContainerAPrime, shinyProxyInstanceAPrime)

        // 12. wait for delete of instance A to happen
        val deleteAFuture = eventController.waitForDeletion(hashA)
        val deleteBFuture = eventController.waitForDeletion(hashB)
        recyclableChecker.isRecyclable = true
        deleteAFuture.awaitWithTimeout()
        deleteBFuture.awaitWithTimeout()

        // 13. check instances are removed
        assertEquals(0, dockerActions.getContainers(shinyProxyInstanceA).size)
        assertEquals(0, dockerActions.getContainers(shinyProxyInstanceB).size)

        // 14. check caddy still points to A'
        dockerAssertions.assertCaddyContainer("simple_test_caddy.json", mapOf("#CONTAINER_IP#" to shinyProxyContainerAPrime.getSharedNetworkIpAddress()!!))
        operator2.stop()
    }

    @Test
    fun `advanced caddy config`() {
        val config = mapOf("SPO_CADDY_ENABLE_TLS" to "true")
        setup(config) { dataDir, inputDir, operator, eventController, dockerAssertions, _, _ ->
            val cert = createRawInputFile(inputDir, "cert.pem", "cert.pem")
            val key = createRawInputFile(inputDir, "key.pem", "key.pem")
            val templateFile1 = createRawInputFile(inputDir, "index.html", "templates/realm1/index.html")
            val hash1 = createInputFile(inputDir, "advanced_caddy_config1.yaml", "realm1.shinyproxy.yaml", mapOf("#INPUT_DIR#" to inputDir.toString()))
            val shinyProxyInstance1 = ShinyProxyInstance("realm1", "default", "default-realm1", hash1, true, 0)
            val templateFile2 = createRawInputFile(inputDir, "index.html", "templates/default-realm2/index.html")
            val hash2 = createInputFile(inputDir, "advanced_caddy_config2.yaml", "realm2.shinyproxy.yaml", mapOf("#INPUT_DIR#" to inputDir.toString()))
            val shinyProxyInstance2 = ShinyProxyInstance("realm2", "default", "default-realm2", hash2, true, 0)

            scope.launch {
                operator.init()
                operator.run()
            }

            eventController.waitForNextReconcile(hash1)
            eventController.waitForNextReconcile(hash2)

            val shinyProxyContainer1 = getSingleShinyProxyContainer(shinyProxyInstance1)
            dockerAssertions.assertShinyProxyContainer(shinyProxyContainer1, shinyProxyInstance1)

            val shinyProxyContainer2 = getSingleShinyProxyContainer(shinyProxyInstance2)
            dockerAssertions.assertShinyProxyContainer(shinyProxyContainer2, shinyProxyInstance2)

            dockerAssertions.assertRedisContainer()
            dockerAssertions.assertCaddyContainer("advanced_caddy.yaml", mapOf(
                "#CONTAINER_IP#" to shinyProxyContainer1.getSharedNetworkIpAddress()!!,
                "#CONTAINER_IP_2#" to shinyProxyContainer2.getSharedNetworkIpAddress()!!
            ), true)
            assertEquals(cert, dataDir.resolve("sp-caddy/certs/itest.local.crt.pem").readText())
            assertEquals(key, dataDir.resolve("sp-caddy/certs/itest.local.key.pem").readText())

            assertEquals(templateFile1, dataDir.resolve(shinyProxyContainer1.name()!!).resolve("templates/index.html").readText())
            assertEquals(templateFile2, dataDir.resolve(shinyProxyContainer2.name()!!).resolve("templates/index.html").readText())
        }
    }

    @Test
    fun monitoring() {
        val config = mapOf("SPO_ENABLE_MONITORING" to "true")
        setup(config) { dataDir, inputDir, operator, eventController, dockerAssertions, _, _ ->
            val hash1 = createInputFile(inputDir, "simple_config.yaml", "realm1.shinyproxy.yaml")
            val shinyProxyInstance1 = ShinyProxyInstance("realm1", "default", "default-realm1", hash1, true, 0)
            val hash2 = createInputFile(inputDir, "simple_config_none.yaml", "realm2.shinyproxy.yaml")
            val shinyProxyInstance2 = ShinyProxyInstance("realm2", "default", "default-realm2", hash2, true, 0)

            scope.launch {
                operator.init()
                operator.run()
            }

            eventController.waitForNextReconcile(hash1)
            eventController.waitForNextReconcile(hash2)

            val shinyProxyContainer1 = getSingleShinyProxyContainer(shinyProxyInstance1)
            val shinyProxyContainer2 = getSingleShinyProxyContainer(shinyProxyInstance2)

            dockerAssertions.assertRedisContainer()
            dockerAssertions.assertShinyProxyContainer(shinyProxyContainer1, shinyProxyInstance1)
            dockerAssertions.assertShinyProxyContainer(shinyProxyContainer2, shinyProxyInstance2)

            // LOKI DRIVER
            val containerInfo1 = dockerClient.inspectContainer(shinyProxyContainer1.id())
            assertEquals("loki", containerInfo1.hostConfig().logConfig().logType())
            assertEquals("http://localhost:3100/loki/api/v1/push", containerInfo1.hostConfig().logConfig().logOptions()["loki-url"])
            assertEquals("non-blocking", containerInfo1.hostConfig().logConfig().logOptions()["mode"])
            assertEquals("sp_realm_id=default-realm1,sp_instance=${hash1},namespace=default,app=shinyproxy", containerInfo1.hostConfig().logConfig().logOptions()["loki-external-labels"])

            val containerInfo2 = dockerClient.inspectContainer(shinyProxyContainer2.id())
            assertEquals("loki", containerInfo2.hostConfig().logConfig().logType())
            assertEquals("http://localhost:3100/loki/api/v1/push", containerInfo2.hostConfig().logConfig().logOptions()["loki-url"])
            assertEquals("non-blocking", containerInfo2.hostConfig().logConfig().logOptions()["mode"])
            assertEquals("sp_realm_id=default-realm2,sp_instance=${hash2},namespace=default,app=shinyproxy", containerInfo2.hostConfig().logConfig().logOptions()["loki-external-labels"])

            // CADVISOR
            val cadvisorContainer = inspectContainer(getContainerByName("sp-cadvisor"))
            assertNotNull(cadvisorContainer)
            assertEquals(true, cadvisorContainer.state().running())
            assertEquals("sp-shared-network", cadvisorContainer.hostConfig().networkMode())
            assertEquals("always", cadvisorContainer.hostConfig().restartPolicy().name())

            // GRAFANA
            val grafanaContainer = inspectContainer(getContainerByName("sp-grafana-grafana-default-realm1"))
            assertNotNull(grafanaContainer)
            assertEquals(true, grafanaContainer.state().running())
            assertEquals("sp-shared-network", grafanaContainer.hostConfig().networkMode())
            assertEquals("always", grafanaContainer.hostConfig().restartPolicy().name())

            // no grafana for public instances
            assertNull(getContainerByName("sp-grafana-grafana-default-realm2"))

            // LOKI
            val lokiContainer = inspectContainer(getContainerByName("sp-grafana-loki"))
            assertNotNull(lokiContainer)
            assertEquals(true, lokiContainer.state().running())
            assertEquals("sp-shared-network", lokiContainer.hostConfig().networkMode())
            assertEquals("always", lokiContainer.hostConfig().restartPolicy().name())

            // PROMETHEUS
            val prometheusContainer = inspectContainer(getContainerByName("sp-prometheus"))
            assertNotNull(prometheusContainer)
            assertEquals(true, prometheusContainer.state().running())
            assertEquals("sp-shared-network", prometheusContainer.hostConfig().networkMode())
            assertEquals("always", prometheusContainer.hostConfig().restartPolicy().name())
        }
    }

    @Test
    fun crane() = setup { dataDir, inputDir, operator, eventController, dockerAssertions, _, _ ->
        val hash = createInputFile(inputDir, "simple_config.yaml", "realm1.shinyproxy.yaml")
        val crane = createRawInputFile(inputDir, "simple_config_crane.yaml", "realm1.crane.yaml")
        val shinyProxyInstance = ShinyProxyInstance("realm1", "default", "default-realm1", hash, true, 0)

        scope.launch {
            operator.init()
            operator.run()
        }

        eventController.waitForNextReconcile(hash)

        val shinyProxyContainer = getSingleShinyProxyContainer(shinyProxyInstance)
        dockerAssertions.assertRedisContainer()
        dockerAssertions.assertShinyProxyContainer(shinyProxyContainer, shinyProxyInstance)

        withTimeout(120_000) {
            while (true) {
                if (dockerActions.getCraneContainers("default-realm1").filter { it.state() == "running" }.isNotEmpty()) {
                    break
                }
                delay(3_000)
            }
        }

        assertEquals(1, dockerActions.getCraneContainers("default-realm1").size)
        val craneContainer = dockerActions.getCraneContainers("default-realm1").get(0)
        val craneContainerInfo = dockerClient.inspectContainer(craneContainer.id())
        assertEquals("sp-shared-network", craneContainerInfo.hostConfig().networkMode())
        assertEquals(listOf(
            "${dataDir}${craneContainerInfo.name()}/application.yml:/opt/crane/application.yml:ro",
            "${dataDir}${craneContainerInfo.name()}/generated.yml:/opt/crane/generated.yml:ro",
            "${dataDir}/logs${craneContainerInfo.name()}:/opt/crane/logs",
            "/tmp/crane-mount:/mnt",
        ), craneContainerInfo.hostConfig().binds())
        dockerAssertions.assertCaddyContainer("simple_config_crane_caddy.yaml", mapOf(
            "#CONTAINER_IP#" to shinyProxyContainer.getSharedNetworkIpAddress()!!,
            "#CRANE_IP#" to craneContainer.getSharedNetworkIpAddress()!!
        ))
    }

}
