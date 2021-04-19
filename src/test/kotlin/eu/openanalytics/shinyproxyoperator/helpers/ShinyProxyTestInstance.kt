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

import eu.openanalytics.shinyproxyoperator.ShinyProxyClient
import eu.openanalytics.shinyproxyoperator.components.LabelFactory
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.client.NamespacedKubernetesClient
import io.fabric8.kubernetes.client.internal.readiness.Readiness
import kotlinx.coroutines.withTimeout
import java.lang.IllegalStateException
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShinyProxyTestInstance(private val namespace: String,
                             private val client: NamespacedKubernetesClient,
                             private val shinyProxyClient: ShinyProxyClient,
                             private val fileName: String,
                             private val reconcileListener: ReconcileListener) {

    lateinit var hash: String

    fun create(): ShinyProxy {
        val sp: ShinyProxy = shinyProxyClient.inNamespace(namespace).load(this.javaClass.getResourceAsStream("/configs/$fileName")).createOrReplace()
        hash = sp.hashOfCurrentSpec

        // assert that it has been created
        assertEquals(1, shinyProxyClient.inNamespace(namespace).list().items.size)
        return sp
    }

    suspend fun waitForOneReconcile(): ShinyProxyInstance? {
        return withTimeout(120_000) {
            reconcileListener.waitForNextReconcile(hash).await()
        }
    }

    fun assertInstanceIsCorrect(numInstancesRunning: Int = 1, isLatest: Boolean = true) {
        val sp = retrieveInstance()
        assertNotNull(sp)
        val instance = sp.status.instances.firstOrNull { it.hashOfSpec == hash }
        assertNotNull(instance)
        assertEquals(isLatest, instance.isLatestInstance)
        assertEquals(numInstancesRunning, sp.status.instances.size)

        // check configmap
        assertConfigMapIsCorrect(sp, numInstancesRunning, isLatest)

        // check replicaset
        assertReplicaSetIsCorrect(sp, numInstancesRunning)

        // check service
        assertServiceIsCorrect(sp, numInstancesRunning)

        // check ingress
        assertIngressIsCorrect(sp, numInstancesRunning, isLatest)
    }

    fun assertIngressIsCorrect(sp: ShinyProxy, numInstancesRunning: Int = 1, isLatest: Boolean = true) {
        val ingresses = client.inNamespace(namespace).network().ingresses().list().items
        assertEquals(numInstancesRunning, ingresses.size)
        val ingress = ingresses.firstOrNull { it.metadata.labels[LabelFactory.INSTANCE_LABEL] == hash }
        assertNotNull(ingress)
        assertEquals("sp-${sp.metadata.name}-ing-${hash}".take(63), ingress.metadata.name)

        assertEquals(mapOf(
                LabelFactory.APP_LABEL to LabelFactory.APP_LABEL_VALUE,
                LabelFactory.NAME_LABEL to sp.metadata.name,
                LabelFactory.INSTANCE_LABEL to hash,
                LabelFactory.INGRESS_IS_LATEST to isLatest.toString()
        ), ingress.metadata.labels)

        assertEquals(1, ingress.metadata.ownerReferences.size)
        assertTrue(ingress.metadata.ownerReferences[0].controller)
        assertEquals("ReplicaSet", ingress.metadata.ownerReferences[0].kind)
        assertEquals("apps/v1", ingress.metadata.ownerReferences[0].apiVersion)
        assertEquals("sp-${sp.metadata.name}-rs-${hash}".take(63), ingress.metadata.ownerReferences[0].name)

        if (isLatest) {
            assertEquals(mapOf(
                    "kubernetes.io/ingress.class" to "skipper",
                    "zalando.org/skipper-predicate" to "True()",
                    "zalando.org/skipper-filter" to """appendResponseHeader("Set-Cookie",  "sp-instance=${sp.hashOfCurrentSpec}; Secure; Path=/") -> appendResponseHeader("Set-Cookie", "sp-latest-instance=${sp.hashOfCurrentSpec}; Secure; Path=/")"""
            ), ingress.metadata.annotations)
        } else {
            assertEquals(mapOf(
                    "kubernetes.io/ingress.class" to "skipper",
                    "zalando.org/skipper-predicate" to """True() && Cookie("sp-instance", "$hash")""",
                    "zalando.org/skipper-filter" to """appendResponseHeader("Set-Cookie", "sp-latest-instance=${sp.hashOfCurrentSpec}; Secure; Path=/")"""
            ), ingress.metadata.annotations)
        }

        assertEquals(1, ingress.spec.rules.size)
        val rule = ingress.spec.rules[0]
        assertNotNull(rule)
        assertEquals(sp.fqdn, rule.host)
        assertEquals(1, rule.http.paths.size)
        val path = rule.http.paths[0]
        assertNotNull(path)
        assertEquals("sp-${sp.metadata.name}-svc-${hash}".take(63), path.backend.serviceName)
        assertEquals(IntOrString(80), path.backend.servicePort)

    }

    fun assertServiceIsCorrect(sp: ShinyProxy, numInstancesRunning: Int = 1) {
        val services = client.inNamespace(namespace).services().list().items
        assertEquals(numInstancesRunning, services.size)
        val service = services.firstOrNull { it.metadata.labels[LabelFactory.INSTANCE_LABEL] == hash }
        assertNotNull(service)
        assertEquals("sp-${sp.metadata.name}-svc-${hash}".take(63), service.metadata.name)
        assertLabelsAreCorrect(service, sp)
        assertOwnerReferenceIsCorrect(service, sp)

        assertEquals("NodePort", service.spec.type)
        assertEquals(1, service.spec.ports.size)
        assertEquals(80, service.spec.ports[0].port)
        assertEquals(IntOrString(8080), service.spec.ports[0].targetPort)
        assertEquals(mapOf(
                LabelFactory.APP_LABEL to LabelFactory.APP_LABEL_VALUE,
                LabelFactory.NAME_LABEL to sp.metadata.name,
                LabelFactory.INSTANCE_LABEL to hash
        ), service.spec.selector)

    }

    fun assertConfigMapIsCorrect(sp: ShinyProxy, numInstancesRunning: Int = 1, isLatest: Boolean = true) {
        val configMaps = client.inNamespace(namespace).configMaps().list().items.filter { it.metadata.name != "kube-root-ca.crt" }
        assertEquals(numInstancesRunning, configMaps.size)
        val configMap = configMaps.firstOrNull { it.metadata.labels[LabelFactory.INSTANCE_LABEL] == hash }
        assertNotNull(configMap)
        assertEquals("sp-${sp.metadata.name}-cm-${hash}".take(63), configMap.metadata.name)
        assertEquals(listOf("application.yml"), configMap.data.keys.toList())
        if (isLatest) {
            assertEquals(sp.specAsYaml, configMap.data["application.yml"])
        } else {
            assertNotEquals(sp.specAsYaml, configMap.data["application.yml"])
        }

        assertLabelsAreCorrect(configMap, sp)
        assertOwnerReferenceIsCorrect(configMap, sp)

//        assertTrue(configMap.immutable) // TODO make the configmap immutable?
    }

    fun assertReplicaSetIsCorrect(sp: ShinyProxy, numInstancesRunning: Int = 1) {
        val replicaSets = client.inNamespace(namespace).apps().replicaSets().list().items
        assertEquals(numInstancesRunning, replicaSets.size)
        val replicaSet = replicaSets.firstOrNull { it.metadata.labels[LabelFactory.INSTANCE_LABEL] == hash }
        assertNotNull(replicaSet)
        assertEquals(1, replicaSet.status.replicas)
        assertEquals(1, replicaSet.status.readyReplicas)
        assertEquals(1, replicaSet.status.availableReplicas)
        assertEquals("sp-${sp.metadata.name}-rs-${hash}".take(63), replicaSet.metadata.name)
        assertLabelsAreCorrect(replicaSet, sp)
        assertOwnerReferenceIsCorrect(replicaSet, sp)

        val templateSpec = replicaSet.spec.template.spec
        assertEquals(1, templateSpec.containers.size)
        assertEquals("shinyproxy", templateSpec.containers[0].name)
        assertEquals(sp.image, templateSpec.containers[0].image)
        assertEquals(sp.imagePullPolicy, templateSpec.containers[0].imagePullPolicy)

        assertEquals(3, templateSpec.containers[0].env.size)
        assertNotNull(templateSpec.containers[0].env.firstOrNull { it.name == "SP_KUBE_POD_UID" })
        assertNotNull(templateSpec.containers[0].env.firstOrNull { it.name == "SP_KUBE_POD_NAME" })
        assertNotNull(templateSpec.containers[0].env.firstOrNull { it.name == "PROXY_REALM_ID" })
        assertEquals(sp.metadata.name, templateSpec.containers[0].env.firstOrNull { it.name == "PROXY_REALM_ID" }?.value)

        assertEquals(1, templateSpec.containers[0].volumeMounts.size)
        assertEquals("config-volume", templateSpec.containers[0].volumeMounts[0].name)
        assertEquals("/opt/shinyproxy/application.yml", templateSpec.containers[0].volumeMounts[0].mountPath)
        assertEquals("application.yml", templateSpec.containers[0].volumeMounts[0].subPath)

        assertEquals(1, templateSpec.containers[0].livenessProbe.periodSeconds)
        assertEquals("/actuator/health/liveness", templateSpec.containers[0].livenessProbe.httpGet.path)
        assertEquals(IntOrString(9090), templateSpec.containers[0].livenessProbe.httpGet.port)

        assertEquals(1, templateSpec.containers[0].readinessProbe.periodSeconds)
        assertEquals("/actuator/health/readiness", templateSpec.containers[0].readinessProbe.httpGet.path)
        assertEquals(IntOrString(9090), templateSpec.containers[0].readinessProbe.httpGet.port)

        if (client.isStartupProbesSupported()) {
            // only check for startup probes if it supported
            assertEquals(5, templateSpec.containers[0].startupProbe.periodSeconds)
            assertEquals(6, templateSpec.containers[0].startupProbe.failureThreshold)
            assertEquals("/actuator/health/liveness", templateSpec.containers[0].startupProbe.httpGet.path)
            assertEquals(IntOrString(9090), templateSpec.containers[0].startupProbe.httpGet.port)
        }

        assertEquals(1, templateSpec.volumes.size)
        assertEquals("config-volume", templateSpec.volumes[0].name)
        assertEquals("sp-${sp.metadata.name}-cm-${hash}".take(63), templateSpec.volumes[0].configMap.name)

        assertTrue(Readiness.isReady(replicaSet))
    }

    fun assertLabelsAreCorrect(resource: HasMetadata, sp: ShinyProxy) {
        assertEquals(mapOf(
                LabelFactory.APP_LABEL to LabelFactory.APP_LABEL_VALUE,
                LabelFactory.NAME_LABEL to sp.metadata.name,
                LabelFactory.INSTANCE_LABEL to hash
        ), resource.metadata.labels)
    }

    fun assertOwnerReferenceIsCorrect(resource: HasMetadata, sp: ShinyProxy) {
        assertEquals(1, resource.metadata.ownerReferences.size)
        assertTrue(resource.metadata.ownerReferences[0].controller)
        assertEquals("ShinyProxy", resource.metadata.ownerReferences[0].kind)
        assertEquals("openanalytics.eu/v1alpha1", resource.metadata.ownerReferences[0].apiVersion)
        assertEquals(sp.metadata.name, resource.metadata.ownerReferences[0].name)
        assertEquals(sp.metadata.uid, resource.metadata.ownerReferences[0].uid)
    }

    fun retrieveInstance(): ShinyProxy {
        for (sp in shinyProxyClient.inNamespace(namespace).list().items) {
            if (sp != null && sp.status.instances.find { it.hashOfSpec == hash } != null) {
                return sp
            }
        }
        throw IllegalStateException("Instance not found")
    }

}