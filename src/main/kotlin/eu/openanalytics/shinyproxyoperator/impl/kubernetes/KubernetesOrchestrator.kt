/*
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
package eu.openanalytics.shinyproxyoperator.impl.kubernetes

import eu.openanalytics.shinyproxyoperator.Config
import eu.openanalytics.shinyproxyoperator.IOrchestrator
import eu.openanalytics.shinyproxyoperator.IShinyProxySource
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.components.ConfigMapFactory
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.components.EventFactory
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.components.ReplicaSetFactory
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.controller.IngressController
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.controller.PodRetriever
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.controller.ResourceListener
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.controller.ServiceController
import eu.openanalytics.shinyproxyoperator.impl.kubernetes.crd.ShinyProxyCustomResource
import eu.openanalytics.shinyproxyoperator.logPrefix
import eu.openanalytics.shinyproxyoperator.model.ShinyProxy
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyStatus
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapList
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.apps.ReplicaSetList
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.dsl.Resource
import io.fabric8.kubernetes.client.dsl.RollableScalableResource
import io.fabric8.kubernetes.client.readiness.Readiness
import io.github.oshai.kotlinlogging.KotlinLogging

class KubernetesOrchestrator(private val kubernetesClient: KubernetesClient,
                             private val shinyProxyClient: ShinyProxyClient,
                             private val serviceController: ServiceController,
                             private val ingressController: IngressController,
                             private val kubernetesSource: KubernetesSource,
                             private val podRetriever: PodRetriever,
                             private val configMapListener: ResourceListener<ConfigMap, ConfigMapList, Resource<ConfigMap>>,
                             private val replicaSetListener: ResourceListener<ReplicaSet, ReplicaSetList, RollableScalableResource<ReplicaSet>>,
                             config: Config) : IOrchestrator {

    private val logger = KotlinLogging.logger {}
    private val configMapFactory = ConfigMapFactory(kubernetesClient)
    private val replicaSetFactory = ReplicaSetFactory(kubernetesClient, config)
    private val eventFactory = EventFactory(kubernetesClient)

    override suspend fun init(source: IShinyProxySource) {
    }

    override fun getShinyProxyStatus(shinyProxy: ShinyProxy): ShinyProxyStatus? {
        return refreshShinyProxy(shinyProxy)?.getSpStatus()
    }

    override fun getShinyProxyStatuses(): List<ShinyProxyStatus> {
        return kubernetesSource.listStatus()
    }

    override fun addNewInstanceToStatus(shinyProxy: ShinyProxy, newInstance: ShinyProxyInstance) {
        updateStatus(shinyProxy) {
            // Extra check, if this check is positive we have some bug
            val checkExistingInstance = it.getSpStatus().instances.firstOrNull { instance -> instance.hashOfSpec == newInstance.hashOfSpec && instance.revision == newInstance.revision }
            val newInstances = ArrayList(it.getSpStatus().instances)
            if (checkExistingInstance != null) {
                // status has already been updated (e.g. after an HTTP 409 Conflict response)
                // remove the existing instance and add the new one, to ensure that all values are correct.
                newInstances.remove(checkExistingInstance)
            }
            newInstances.add(newInstance)
            return@updateStatus ShinyProxyStatus(shinyProxy.realmId, shinyProxy.hashOfCurrentSpec, newInstances)
        }
    }

    override suspend fun removeInstanceFromStatus(instance: ShinyProxyInstance) {
        val shinyProxy = kubernetesSource.get(instance.namespace, instance.name) ?: return
        updateStatus(shinyProxy) {
            val instances = ArrayList(it.getSpStatus().instances)
            instances.remove(instance)
            return@updateStatus ShinyProxyStatus(shinyProxy.realmId, shinyProxy.hashOfCurrentSpec, instances)
        }
    }

    override fun makeLatest(shinyProxy: ShinyProxy, instance: ShinyProxyInstance) {
        updateStatus(shinyProxy) {
            val instances = it.getSpStatus().instances.map { inst ->
                return@map if (inst.hashOfSpec == instance.hashOfSpec && inst.revision == instance.revision) {
                    inst.copy(isLatestInstance = true)
                } else {
                    inst.copy(isLatestInstance = false)
                }
            }
            return@updateStatus ShinyProxyStatus(shinyProxy.realmId, shinyProxy.hashOfCurrentSpec, instances)
        }
    }

    override suspend fun reconcileInstance(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): Boolean {
        val shinyProxyUid = refreshShinyProxy(shinyProxy)?.metadata?.uid ?: return false
        val configMaps = configMapListener.getByInstance(shinyProxyInstance)
        if (configMaps.isEmpty()) {
            logger.debug { "${logPrefix(shinyProxyInstance)} [Reconciling] [Component/ConfigMap] 0 ConfigMaps found -> creating ConfigMap" }
            configMapFactory.create(shinyProxy, shinyProxyInstance, shinyProxyUid)
            return false
        }

        logger.debug { "${logPrefix(shinyProxyInstance)} [Ok] [Component/ConfigMap]" }

        val replicaSets = replicaSetListener.getByInstance(shinyProxyInstance)
        if (replicaSets.isEmpty()) {
            logger.debug { "${logPrefix(shinyProxyInstance)} [Reconciling] [Component/ReplicaSet] 0 ReplicaSets found -> creating ReplicaSet" }
            replicaSetFactory.create(shinyProxy, shinyProxyInstance, shinyProxyUid)
            return false
        }

        logger.debug { "${logPrefix(shinyProxyInstance)} [Ok] [Component/ReplicaSet]" }

        if (!Readiness.getInstance().isReady(replicaSets[0])) {
            // do no proceed until replicaset is ready
            logger.debug { "${logPrefix(shinyProxyInstance)} [Waiting] [Component/ReplicaSet] ReplicaSet not ready" }
            return false
        }

        logger.debug { "${logPrefix(shinyProxyInstance)} [Ok] [Component/ReplicaSet] ReplicaSet ready" }
        return true
    }

    override suspend fun deleteInstance(shinyProxyInstance: ShinyProxyInstance) {
        for (replicaSet in replicaSetListener.getByInstance(shinyProxyInstance)) {
            kubernetesClient.resource(replicaSet).delete()
        }
        for (configMap in configMapListener.getByInstance(shinyProxyInstance)) {
            kubernetesClient.resource(configMap).delete()
        }
    }

    override suspend fun reconcileIngress(shinyProxy: ShinyProxy, latestShinyProxyInstance: ShinyProxyInstance) {
        val shinyProxyUid = refreshShinyProxy(shinyProxy)?.metadata?.uid ?: return
        serviceController.reconcile(shinyProxy, latestShinyProxyInstance, shinyProxyUid)
        logger.debug { "${logPrefix(latestShinyProxyInstance)} [Ok] [Component/Service]" }

        ingressController.reconcile(shinyProxy, latestShinyProxyInstance, shinyProxyUid)
        logger.debug { "${logPrefix(latestShinyProxyInstance)} [Ok] [Component/Ingress]" }
    }

    override suspend fun deleteRealm(realmId: String) {
        // no-op
    }

    override fun getContainerIPs(shinyProxyInstance: ShinyProxyInstance): List<String> {
        val pods = podRetriever.getShinyProxyPods(shinyProxyInstance)
        return pods.map { it.status.podIP }
    }

    override fun logEvent(shinyProxyInstance: ShinyProxyInstance, type: String, action: String, message: String?) {
        val shinyProxyUid = shinyProxyClient.inNamespace(shinyProxyInstance.namespace).withName(shinyProxyInstance.name).get()?.metadata?.uid ?: return
        eventFactory.logEvent(shinyProxyInstance, type, action, shinyProxyUid, message)
    }

    override fun logEvent(type: String, action: String, message: String?) {
        eventFactory.logEvent(type, action, message)
    }

    private fun refreshShinyProxy(shinyProxy: ShinyProxy): ShinyProxyCustomResource? {
        return shinyProxyClient.inNamespace(shinyProxy.namespace).withName(shinyProxy.name).get()
    }

    private fun updateStatus(shinyProxy: ShinyProxy, updater: (ShinyProxyCustomResource) -> ShinyProxyStatus) {
        /**
         * Tries to update the status, once, in a single step.
         * @throws KubernetesClientException
         */
        fun tryUpdateStatus() {
            val freshShinyProxy = refreshShinyProxy(shinyProxy) ?: return
            val newStatus = updater(freshShinyProxy)
            freshShinyProxy.setSpStatus(newStatus)
            shinyProxyClient.inNamespace(shinyProxy.namespace).resource(freshShinyProxy).updateStatus()
        }

        for (i in 1..5) {
            try {
                logger.debug { "${logPrefix(shinyProxy)} Trying to update status (attempt ${i}/5)" }
                tryUpdateStatus()
                logger.debug { "${logPrefix(shinyProxy)} Status successfully updated" }
                return
            } catch (e: KubernetesClientException) {
                logger.warn(e) { "${logPrefix(shinyProxy)} Update of status not succeeded (attempt ${i}/5)" }
            }
        }
        throw RuntimeException("${logPrefix(shinyProxy)} Unable to update Status of ShinyProxy object after 5 attempts (event will be re-processed)")
    }

}
