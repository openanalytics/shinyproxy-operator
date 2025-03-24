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
package eu.openanalytics.shinyproxyoperator.impl.kubernetes.helpers

import eu.openanalytics.shinyproxyoperator.LabelFactory
import eu.openanalytics.shinyproxyoperator.LabelFactory.APP_LABEL
import eu.openanalytics.shinyproxyoperator.LabelFactory.APP_LABEL_VALUE
import eu.openanalytics.shinyproxyoperator.LabelFactory.INSTANCE_LABEL
import eu.openanalytics.shinyproxyoperator.LabelFactory.REALM_ID_LABEL
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.ShinyProxyClient
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.crd.ShinyProxyCustomResource
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.getImagePullPolicy
import eu.openanalytics.shinyproxyoperator.model.ShinyProxy
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyStatus
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.client.NamespacedKubernetesClient
import io.fabric8.kubernetes.client.readiness.Readiness
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShinyProxyTestInstance(private val namespace: String,
                             private val client: NamespacedKubernetesClient,
                             private val shinyProxyClient: ShinyProxyClient,
                             private val fileName: String) {

    lateinit var realmId: String
    lateinit var hash: String
    lateinit var uid: String

    fun create(): ShinyProxy {
        val sp: ShinyProxyCustomResource = shinyProxyClient.inNamespace(namespace).load(this.javaClass.getResourceAsStream("/configs/$fileName")).serverSideApply()
        hash = sp.hashOfCurrentSpec
        realmId = sp.realmId
        uid = sp.metadata.uid

        // assert that it has been created
        assertEquals(1, shinyProxyClient.inNamespace(namespace).list().items.size)
        return ShinyProxy(sp.spec, sp.metadata.name, sp.metadata.namespace)
    }

    fun assertInstanceIsCorrect(numInstancesRunning: Int = 1, isLatest: Boolean = true, revision: Int = 0) {
        val (sp, status, instance) = retrieveInstance(revision)
        assertEquals(isLatest, instance.isLatestInstance)
        assertEquals(numInstancesRunning, status.instances.size)

        // check configmap
        assertConfigMapIsCorrect(sp, numInstancesRunning, isLatest, revision)

        // check replicaset
        assertReplicaSetIsCorrect(sp, numInstancesRunning, revision)

        // check service
        assertServiceIsCorrect(sp, status, revision)

        // check ingress
        assertIngressIsCorrect(sp, status)
    }

    fun assertIngressIsCorrect(sp: ShinyProxy, status: ShinyProxyStatus) {
        val allIngresses = client.inNamespace(namespace).network().v1().ingresses().list().items
        assertEquals(1, allIngresses.size)
        val ingress = allIngresses.firstOrNull { it.metadata.name == "sp-${sp.name}-ing".take(63) }
        assertNotNull(ingress)

        assertEquals(mapOf(
            LabelFactory.APP_LABEL to LabelFactory.APP_LABEL_VALUE,
            LabelFactory.REALM_ID_LABEL to sp.realmId,
            LabelFactory.LATEST_INSTANCE_LABEL to status.latestInstance()!!.hashOfSpec
        ), ingress.metadata.labels)

        assertEquals(mapOf(
            "nginx.org/websocket-services" to "sp-${sp.name}-svc".take(63),
        ),
            ingress.metadata.annotations
        )

        assertOwnerReferenceIsCorrect(ingress, sp)

        assertEquals(1, ingress.spec.rules.size)
        val rule = ingress.spec.rules[0]
        assertNotNull(rule)
        assertEquals(sp.fqdn, rule.host)
        assertEquals(1, rule.http.paths.size)
        val path = rule.http.paths[0]
        assertNotNull(path)
        assertEquals(sp.subPath, path.path)
        assertEquals("sp-${sp.name}-svc".take(63), path.backend.service.name)
        assertEquals(80, path.backend.service.port.number)
    }

    fun assertServiceIsCorrect(sp: ShinyProxy, status: ShinyProxyStatus, revision: Int = 0) {
        val services = client.inNamespace(namespace).services().list().items
        assertEquals(1, services.size)
        val service = services.firstOrNull { it.metadata.name == "sp-${sp.name}-svc".take(63) }
        assertNotNull(service)

        assertEquals(mapOf(
            LabelFactory.APP_LABEL to LabelFactory.APP_LABEL_VALUE,
            LabelFactory.REALM_ID_LABEL to sp.realmId,
            LabelFactory.LATEST_INSTANCE_LABEL to status.latestInstance()!!.hashOfSpec,
            LabelFactory.REVISION_LABEL to revision.toString()
        ), service.metadata.labels)

        assertOwnerReferenceIsCorrect(service, sp)

        assertEquals("ClusterIP", service.spec.type)
        assertEquals(1, service.spec.ports.size)
        assertEquals(80, service.spec.ports[0].port)
        assertEquals(IntOrString(8080), service.spec.ports[0].targetPort)
        assertEquals(mapOf(
            LabelFactory.APP_LABEL to LabelFactory.APP_LABEL_VALUE,
            LabelFactory.REALM_ID_LABEL to sp.realmId,
            LabelFactory.INSTANCE_LABEL to status.latestInstance()!!.hashOfSpec,
            LabelFactory.REVISION_LABEL to revision.toString()
        ), service.spec.selector)

    }

    fun assertConfigMapIsCorrect(sp: ShinyProxy, numInstancesRunning: Int = 1, isLatest: Boolean = true, revision: Int = 0) {
        val configMaps = client.inNamespace(namespace).configMaps().list().items.filter { it.metadata.name != "kube-root-ca.crt" }
        assertEquals(numInstancesRunning, configMaps.size)
        val configMap = configMaps.firstOrNull { it.metadata.labels[LabelFactory.INSTANCE_LABEL] == hash }
        assertNotNull(configMap)
        assertEquals("sp-${sp.name}-cm-${hash}".take(63), configMap.metadata.name)
        assertEquals(listOf("application.yml"), configMap.data.keys.toList())
        if (isLatest) {
            assertEquals(sp.specAsYaml, configMap.data["application.yml"])
        } else {
            assertNotEquals(sp.specAsYaml, configMap.data["application.yml"])
        }

        assertLabelsAreCorrect(configMap, sp, revision)
        assertOwnerReferenceIsCorrect(configMap, sp)

//        assertTrue(configMap.immutable) // TODO make the configmap immutable?
    }

    fun assertReplicaSetIsCorrect(sp: ShinyProxy, numInstancesRunning: Int = 1, revision: Int = 0) {
        val replicaSets = client.inNamespace(namespace).apps().replicaSets().list().items
        assertEquals(numInstancesRunning, replicaSets.size)
        val replicaSet = replicaSets.firstOrNull { it.metadata.labels[LabelFactory.INSTANCE_LABEL] == hash }
        assertNotNull(replicaSet)
        assertEquals(1, replicaSet.status.replicas)
        assertEquals(1, replicaSet.status.readyReplicas)
        assertEquals(1, replicaSet.status.availableReplicas)
        assertEquals("sp-${sp.name}-rs-${revision}-${hash}".take(63), replicaSet.metadata.name)
        assertLabelsAreCorrect(replicaSet, sp, revision)
        assertOwnerReferenceIsCorrect(replicaSet, sp)

        val templateSpec = replicaSet.spec.template.spec
        assertEquals(1, templateSpec.containers.size)
        assertEquals("shinyproxy", templateSpec.containers[0].name)
        assertEquals(sp.image, templateSpec.containers[0].image)
        assertEquals(sp.getImagePullPolicy(), templateSpec.containers[0].imagePullPolicy)

        assertEquals(4, templateSpec.containers[0].env.size)
        assertNotNull(templateSpec.containers[0].env.firstOrNull { it.name == "SP_KUBE_POD_UID" })
        assertNotNull(templateSpec.containers[0].env.firstOrNull { it.name == "SP_KUBE_POD_NAME" })
        assertNotNull(templateSpec.containers[0].env.firstOrNull { it.name == "PROXY_REALM_ID" })
        assertNotNull(templateSpec.containers[0].env.firstOrNull { it.name == "PROXY_VERSION" })
        assertEquals(sp.namespace + '-' + sp.name, templateSpec.containers[0].env.firstOrNull { it.name == "PROXY_REALM_ID" }?.value)

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

        assertEquals(5, templateSpec.containers[0].startupProbe.periodSeconds)
        assertEquals(6, templateSpec.containers[0].startupProbe.failureThreshold)
        assertEquals("/actuator/health/liveness", templateSpec.containers[0].startupProbe.httpGet.path)
        assertEquals(IntOrString(9090), templateSpec.containers[0].startupProbe.httpGet.port)

        assertEquals(1, templateSpec.volumes.size)
        assertEquals("config-volume", templateSpec.volumes[0].name)
        assertEquals("sp-${sp.name}-cm-${hash}".take(63), templateSpec.volumes[0].configMap.name)

        assertTrue(Readiness.getInstance().isReady(replicaSet))
    }

    fun assertLabelsAreCorrect(resource: HasMetadata, sp: ShinyProxy, revision: Int) {
        assertEquals(mapOf(
            LabelFactory.APP_LABEL to LabelFactory.APP_LABEL_VALUE,
            LabelFactory.REALM_ID_LABEL to sp.realmId,
            LabelFactory.INSTANCE_LABEL to hash,
            LabelFactory.REVISION_LABEL to revision.toString()
        ), resource.metadata.labels)
    }

    fun assertEventCount(count: Int) {
        val (sp, status, instance) = retrieveInstance()
        val events = client.v1().events().withLabels(LabelFactory.labelsForShinyProxy(sp.realmId)).list().items
        assertEquals(count, events.size)
    }

    fun assertEvent(type: String, action: String, message: String) {
        val events = client.v1().events().withLabels(hashMapOf(
            APP_LABEL to APP_LABEL_VALUE,
            REALM_ID_LABEL to realmId,
            INSTANCE_LABEL to hash,
        )).list().items.filter { it.action == action }
        assertEquals(1, events.size)
        val event = events[0]
        assertEquals(type, event.type)
        assertEquals(message, event.message)
        assertEquals(action, event.reason)
        assertEquals(action, event.action)
        assertEquals("shinyproxy-operator", event.reportingInstance)
        assertEquals("shinyproxy-operator", event.reportingComponent)
        assertEquals("ShinyProxy", event.involvedObject.kind)
        assertEquals("example-shinyproxy", event.involvedObject.name)
        assertEquals(namespace, event.involvedObject.namespace)
        assertEquals(uid, event.involvedObject.uid)
    }

    private fun assertOwnerReferenceIsCorrect(resource: HasMetadata, sp: ShinyProxy) {
        assertEquals(1, resource.metadata.ownerReferences.size)
        assertTrue(resource.metadata.ownerReferences[0].controller)
        assertEquals("ShinyProxy", resource.metadata.ownerReferences[0].kind)
        assertEquals("openanalytics.eu/v1", resource.metadata.ownerReferences[0].apiVersion)
        assertEquals(sp.name, resource.metadata.ownerReferences[0].name)
        assertEquals(uid, resource.metadata.ownerReferences[0].uid)
    }

    fun retrieveInstance(revision: Int = 0): Triple<ShinyProxy, ShinyProxyStatus, ShinyProxyInstance> {
        for (sp in shinyProxyClient.inNamespace(namespace).list().items) {
            val instance = sp.status.instances.find { it.hashOfSpec == hash && it.revision == revision }
            if (sp != null && instance != null) {
                val status = sp.getSpStatus()
                return Triple(
                    ShinyProxy(sp.spec, sp.metadata.name, sp.metadata.namespace),
                    status,
                    ShinyProxyInstance(sp.metadata.name, sp.metadata.namespace, sp.realmId, instance.hashOfSpec, instance.isLatestInstance, instance.revision)
                )
            }
        }
        throw IllegalStateException("Instance not found")
    }

}
