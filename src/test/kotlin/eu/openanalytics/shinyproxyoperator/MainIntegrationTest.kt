package eu.openanalytics.shinyproxyoperator

import eu.openanalytics.shinyproxyoperator.crd.DoneableShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyList
import eu.openanalytics.shinyproxyoperator.helpers.ShinyProxyTestInstance
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.NamespacedKubernetesClient
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.arquillian.cube.kubernetes.api.Session
import org.arquillian.cube.kubernetes.impl.requirement.RequiresKubernetes
import org.jboss.arquillian.junit.Arquillian
import org.jboss.arquillian.test.api.ArquillianResource
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue


@RunWith(Arquillian::class)
@RequiresKubernetes
class MainIntegrationTest {

    @ArquillianResource
    lateinit var session: Session

    @ArquillianResource
    lateinit var client: KubernetesClient

    private val customResourceDefinitionContext = CustomResourceDefinitionContext.Builder()
            .withVersion("v1alpha1")
            .withScope("Namespaced")
            .withGroup("openanalytics.eu")
            .withPlural("shinyproxies")
            .build()

    @Test
    fun simple_test() {
        runBlocking {
            // 1. create the operator
            val namespacedClient = DefaultKubernetesClient(client.configuration)
            val operator = Operator(namespacedClient, Mode.NAMESPACED)
            Operator.operatorInstance = operator

            try {
                // 2. create the CRD
                val podSetCustomResourceDefinition = namespacedClient.customResourceDefinitions().load(this.javaClass.getResource("/crd.yaml")).get()
                namespacedClient.customResourceDefinitions().createOrReplace(podSetCustomResourceDefinition)

                val shinyProxyClient = namespacedClient.inNamespace(session.namespace).customResources(customResourceDefinitionContext, ShinyProxy::class.java, ShinyProxyList::class.java, DoneableShinyProxy::class.java)

                // 3. create a SP instance
                val shinyProxyDefinition = """
                apiVersion: openanalytics.eu/v1alpha1
                kind: ShinyProxy
                metadata:
                  name: example-shinyproxy
                  namespace: ${session.namespace}
                spec:
                    fqdn: itest.local
                    proxy:
                      title: Open Analytics Shiny Proxy
                      logoUrl: http://www.openanalytics.eu/sites/www.openanalytics.eu/themes/oa/logo.png
                      landingPage: /
                      heartbeatRate: 10000
                      heartbeatTimeout: 60000
                      port: 8080
                      authentication: simple

                      users:
                      - name: demo
                        password: demo
                        groups: scientists
                      - name: demo2
                        password: demo2
                        groups: mathematicians
                      specs:
                      - id: 01_hello
                        displayName: Hello Application
                        description: Application which demonstrates the basics of a Shiny app
                        containerCmd: ["R", "-e", "shinyproxy::run_01_hello()"]
                        containerImage: openanalytics/shinyproxy-demo
                      - id: 06_tabsets
                        container-cmd: ["R", "-e", "shinyproxy::run_06_tabsets()"]
                        container-image: openanalytics/shinyproxy-demo
            """.trimIndent()

                val spTestInstance = ShinyProxyTestInstance(session.namespace, namespacedClient, shinyProxyClient, shinyProxyDefinition)
                spTestInstance.create()
//                shinyProxyClient.load(ByteArrayInputStream(shinyProxyDefinition.toByteArray())).create()
                // assert that it has been created
//                assertEquals(1, shinyProxyClient.inNamespace(session.namespace).list().items.size)

                // 4. start the operator and let it do it's work
                GlobalScope.launch {
                    operator.run()
                }

                spTestInstance.waitUntilReady()
                spTestInstance.assertInstanceIsCorrect()

//                while (true) {
//                    val sp = shinyProxyClient.inNamespace(session.namespace).list().items[0]
//                    assertNotNull(sp)
//                    if (sp.status.instances.size == 1 && sp.status.instances[0].isLatestInstance == true) {
//                        break
//                    }
//                    println("Instance not ready yet!")
//                    delay(1_000)
//                }
//
//                val sp = shinyProxyClient.inNamespace(session.namespace).list().items[0]
//                assertNotNull(sp)
//                val instance = sp.status.instances[0]
//                assertNotNull(instance)
//                assertEquals(true, instance.isLatestInstance)


            } finally {
                // Remove the CRD
                val podSetCustomResourceDefinition = client.customResourceDefinitions().load(this.javaClass.getResource("/crd.yaml")).get()
                client.customResourceDefinitions().delete(podSetCustomResourceDefinition)
            }
        }

    }


}