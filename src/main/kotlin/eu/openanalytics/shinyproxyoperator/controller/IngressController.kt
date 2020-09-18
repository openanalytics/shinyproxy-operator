package eu.openanalytics.shinyproxyoperator.controller

import eu.openanalytics.shinyproxyoperator.ShinyProxyClient
import eu.openanalytics.shinyproxyoperator.components.LabelFactory
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance
import eu.openanalytics.shinyproxyoperator.skipper.IngressFactory
import eu.openanalytics.shinyproxyoperator.skipper.IngressListener
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.networking.v1beta1.Ingress
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.fabric8.kubernetes.client.informers.cache.Lister
import kotlinx.coroutines.channels.Channel
import mu.KotlinLogging
import java.lang.RuntimeException

class IngressController(
        channel: Channel<ShinyProxyEvent>,
        ingressInformer: SharedIndexInformer<Ingress>,
        shinyProxyListener: Lister<ShinyProxy>,
        private val kubernetesClient: KubernetesClient,
        private val shinyProxyClient: ShinyProxyClient,
        private val resourceRetriever: ResourceRetriever
) {

    private val logger = KotlinLogging.logger {}
    private val ingressFactory = IngressFactory(kubernetesClient)
    private val ingressListener = IngressListener(channel, kubernetesClient, ingressInformer, shinyProxyListener)

    fun onNewInstance(shinyProxy: ShinyProxy, newInstance: ShinyProxyInstance) {
        for (instance in shinyProxy.status.instances) {
            val replicaSet = getReplicaSet(shinyProxy, instance) ?: continue // ignore since we still have to update the others
            ingressFactory.createOrReplaceIngress(shinyProxy, instance, replicaSet)
        }
    }

    fun reconcileInstance(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance) {
        val ingresses = resourceRetriever.getIngressByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance))
        if (ingresses.isEmpty()) {
            logger.debug { "0 Ingresses found -> creating Ingress" }
            val replicaSet = getReplicaSet(shinyProxy, shinyProxyInstance)
            if (replicaSet == null) {
                // throw exception so that this renoncile will be called again
                throw RuntimeException("Cannot reconcile Ingress for ${shinyProxyInstance.hashOfSpec} since it has no ReplicaSet")
            }
            ingressFactory.createOrReplaceIngress(shinyProxy, shinyProxyInstance, replicaSet)
        }
    }

    private fun getReplicaSet(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): ReplicaSet? {
        val replicaSets = resourceRetriever.getReplicaSetByLabels(LabelFactory.labelsForShinyProxyInstance(shinyProxy, shinyProxyInstance))
        if (replicaSets.isEmpty()) {
            logger.warn { "Cannot reconcile Ingress when there is no replicaset for instance ${shinyProxyInstance.hashOfSpec} (maybe it is already removed?)" }
            return null
        }
        return replicaSets[0]
    }

}