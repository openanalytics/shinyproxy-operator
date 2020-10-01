package eu.openanalytics.shinyproxyoperator.helpers

import eu.openanalytics.shinyproxyoperator.ShinyProxyClient
import eu.openanalytics.shinyproxyoperator.components.LabelFactory
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.networking.v1beta1.Ingress
import io.fabric8.kubernetes.client.NamespacedKubernetesClient
import io.fabric8.kubernetes.client.internal.readiness.Readiness
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShinyProxyTestInstance(private val namespace: String,
                             private val client: NamespacedKubernetesClient,
                             private val shinyProxyClient: ShinyProxyClient,
                             private val specification: String) {

    private var hash: String? = null

    fun create() {
        val sp: ShinyProxy = shinyProxyClient.load(ByteArrayInputStream(specification.toByteArray())).create()
        hash = sp.hashOfCurrentSpec

        // assert that it has been created
        assertEquals(1, shinyProxyClient.inNamespace(namespace).list().items.size)
    }

    suspend fun waitUntilReady() {
        checkLoop@ while (true) {
            for (sp in shinyProxyClient.inNamespace(namespace).list().items) {
                if (sp?.hashOfCurrentSpec == hash) {
                    if (sp?.status?.instances?.size == 1 && sp.status.instances[0].isLatestInstance == true) {
                        break@checkLoop
                    }
                    if (sp?.status?.instances?.size != null && sp.status.instances.size > 1) {
                        TODO("Not implemented")
                    }
                    break
                }
            }

            delay(1_000)
        }
    }

    fun assertInstanceIsCorrect() {
        val sp = retrieveInstance()
        assertNotNull(sp)
        val instance = sp.status.instances[0]
        assertNotNull(instance)
        assertEquals(true, instance.isLatestInstance)

        // check confgimap
        val configMaps = client.inNamespace(namespace).configMaps().list().items
        assertEquals(1, configMaps.size)
        val configMap = configMaps[0]
        assertConfigMapIsCorrect(configMap, sp)

        // check replicaset
        val replicaSets = client.inNamespace(namespace).apps().replicaSets().list().items
        assertEquals(1, replicaSets.size)
        val replicaSet = replicaSets[0]
        assertReplicaSetIsCorrect(replicaSet, sp)

        val services = client.inNamespace(namespace).services().list().items
        assertEquals(1, services.size)
        val service = services[0]
        assertServiceIsCorrect(service, sp)

        val ingresses = client.inNamespace(namespace).network().ingresses().list().items
        assertEquals(1, ingresses.size)
        val ingress = ingresses[0]
        assertIngressIsCorrect(ingress, sp)

    }

    private fun assertIngressIsCorrect(ingress: Ingress?, sp: ShinyProxy) {
        assertNotNull(ingress)
        assertEquals("sp-${sp.metadata.name}-ing-${hash}".take(63), ingress.metadata.name)

        assertEquals(mapOf(
                LabelFactory.APP_LABEL to LabelFactory.APP_LABEL_VALUE,
                LabelFactory.NAME_LABEL to sp.metadata.name,
                LabelFactory.INSTANCE_LABEL to hash,
                LabelFactory.INGRESS_IS_LATEST to "true"
        ), ingress.metadata.labels)

        assertEquals(1, ingress.metadata.ownerReferences.size)
        assertTrue(ingress.metadata.ownerReferences[0].controller)
        assertEquals("ReplicaSet", ingress.metadata.ownerReferences[0].kind)
        assertEquals("v1", ingress.metadata.ownerReferences[0].apiVersion)
        assertEquals("sp-${sp.metadata.name}-rs-${hash}".take(63), ingress.metadata.ownerReferences[0].name)

        assertEquals(mapOf(
                "kubernetes.io/ingress.class" to "skipper",
                "zalando.org/skipper-predicate" to "True()",
                "zalando.org/skipper-filter" to """jsCookie("sp-instance", "${sp.hashOfCurrentSpec}") -> jsCookie("sp-latest-instance", "${sp.hashOfCurrentSpec}")"""
        ), ingress.metadata.annotations)

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

    private fun assertServiceIsCorrect(service: Service?, sp: ShinyProxy) {
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

    private fun assertConfigMapIsCorrect(configMap: ConfigMap?, sp: ShinyProxy) {
        assertNotNull(configMap)
        assertEquals("sp-${sp.metadata.name}-cm-${hash}".take(63), configMap.metadata.name)
        assertEquals(listOf("application.yml"), configMap.data.keys.toList())
        assertEquals(sp.specAsYaml, configMap.data["application.yml"])

        assertLabelsAreCorrect(configMap, sp)
        assertOwnerReferenceIsCorrect(configMap, sp)

//        assertTrue(configMap.immutable) // TODO make the configmap immutable?
    }

    private fun assertReplicaSetIsCorrect(replicaSet: ReplicaSet, sp: ShinyProxy) {
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

        assertEquals(2, templateSpec.containers[0].env.size)
        assertNotNull(templateSpec.containers[0].env.firstOrNull { it.name == "SP_KUBE_POD_UID" })
        assertNotNull(templateSpec.containers[0].env.firstOrNull { it.name == "SP_KUBE_POD_NAME" })

        assertEquals(1, templateSpec.containers[0].volumeMounts.size)
        assertEquals("config-volume", templateSpec.containers[0].volumeMounts[0].name)
        assertEquals("/etc/shinyproxy/application.yml", templateSpec.containers[0].volumeMounts[0].mountPath)
        assertEquals("application.yml", templateSpec.containers[0].volumeMounts[0].subPath)

        assertEquals(1, templateSpec.containers[0].livenessProbe.periodSeconds)
        assertEquals("/actuator/health/liveness", templateSpec.containers[0].livenessProbe.httpGet.path)
        assertEquals(IntOrString(8080), templateSpec.containers[0].livenessProbe.httpGet.port)

        assertEquals(1, templateSpec.containers[0].readinessProbe.periodSeconds)
        assertEquals("/actuator/health/readiness", templateSpec.containers[0].readinessProbe.httpGet.path)
        assertEquals(IntOrString(8080), templateSpec.containers[0].readinessProbe.httpGet.port)

        assertEquals(5, templateSpec.containers[0].startupProbe.periodSeconds)
        assertEquals(6, templateSpec.containers[0].startupProbe.failureThreshold)
        assertEquals("/actuator/health/liveness", templateSpec.containers[0].startupProbe.httpGet.path)
        assertEquals(IntOrString(8080), templateSpec.containers[0].startupProbe.httpGet.port)

        assertEquals(1, templateSpec.volumes.size)
        assertEquals("config-volume", templateSpec.volumes[0].name)
        assertEquals("sp-${sp.metadata.name}-cm-${hash}".take(63), templateSpec.volumes[0].configMap.name)

        assertTrue(Readiness.isReady(replicaSet))
    }

    private fun assertLabelsAreCorrect(resource: HasMetadata, sp: ShinyProxy) {
        assertEquals(mapOf(
                LabelFactory.APP_LABEL to LabelFactory.APP_LABEL_VALUE,
                LabelFactory.NAME_LABEL to sp.metadata.name,
                LabelFactory.INSTANCE_LABEL to hash
        ), resource.metadata.labels)
    }

    private fun assertOwnerReferenceIsCorrect(resource: HasMetadata, sp: ShinyProxy) {
        assertEquals(1, resource.metadata.ownerReferences.size)
        assertTrue(resource.metadata.ownerReferences[0].controller)
        assertEquals("ShinyProxy", resource.metadata.ownerReferences[0].kind)
        assertEquals("openanalytics.eu/v1alpha1", resource.metadata.ownerReferences[0].apiVersion)
        assertEquals(sp.metadata.name, resource.metadata.ownerReferences[0].name)
        assertEquals(sp.metadata.uid, resource.metadata.ownerReferences[0].uid)
    }

    private fun retrieveInstance(): ShinyProxy {
        for (sp in shinyProxyClient.inNamespace(namespace).list().items) {
            if (sp != null && sp.hashOfCurrentSpec == hash) {
                return sp
            }
        }
        throw Exception("Instance not found")
    }

}