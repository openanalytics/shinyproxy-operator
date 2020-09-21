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

import eu.openanalytics.shinyproxyoperator.controller.*
import eu.openanalytics.shinyproxyoperator.crd.DoneableShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyList
import eu.openanalytics.shinyproxyoperator.ingress.skipper.IngressController
import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.apps.ReplicaSetList
import io.fabric8.kubernetes.api.model.networking.v1beta1.Ingress
import io.fabric8.kubernetes.api.model.networking.v1beta1.IngressList
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext
import io.fabric8.kubernetes.client.informers.cache.Lister
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import mu.KotlinLogging

class Operator {

    private val logger = KotlinLogging.logger {}
    private val client = DefaultKubernetesClient()
    private val namespace: String

    /**
     * Initialize client and namespace
     */
    init {
        namespace = if (client.namespace == null) {
            logger.info { "No namespace found via config, assuming default." }
            "default"
        } else {
            client.namespace
        }
        logger.info { "Using namespace : $namespace " }

        var podSetCustomResourceDefinition = client.customResourceDefinitions().withName("shinyproxies.openanalytics.eu").get()
        if (podSetCustomResourceDefinition == null) {
            podSetCustomResourceDefinition = client.customResourceDefinitions().load(object : Any() {}.javaClass.getResourceAsStream("/crd.yaml")).get()
            client.customResourceDefinitions().create(podSetCustomResourceDefinition)
            logger.info { "Created CustomResourceDefinition" }
        }
    }

    /**
     * Main Components
     */
    private val podSetCustomResourceDefinitionContext = CustomResourceDefinitionContext.Builder()
            .withVersion("v1alpha1")
            .withScope("Namespaced")
            .withGroup("openanalytics.eu")
            .withPlural("shinyproxies")
            .build()
    private val shinyProxyClient = client.customResources(podSetCustomResourceDefinitionContext, ShinyProxy::class.java, ShinyProxyList::class.java, DoneableShinyProxy::class.java)
    private val channel = Channel<ShinyProxyEvent>(10000)
    private val sendChannel: SendChannel<ShinyProxyEvent> = channel

    /**
     * Informers
     */
    private val informerFactory = client.informers()
    private val replicaSetInformer = informerFactory.sharedIndexInformerFor(ReplicaSet::class.java, ReplicaSetList::class.java, 10 * 60 * 1000.toLong())
    private val serviceInformer = informerFactory.sharedIndexInformerFor(Service::class.java, ServiceList::class.java, 10 * 60 * 1000.toLong())
    private val configMapInformer = informerFactory.sharedIndexInformerFor(ConfigMap::class.java, ConfigMapList::class.java, 10 * 60 * 1000.toLong())
    private val ingressInformer = informerFactory.sharedIndexInformerFor(Ingress::class.java, IngressList::class.java, 10 * 60 * 1000.toLong())
    private val shinyProxyInformer = informerFactory.sharedIndexInformerForCustomResource(podSetCustomResourceDefinitionContext, ShinyProxy::class.java, ShinyProxyList::class.java, 10 * 60 * 1000)
    private val podInformer = informerFactory.sharedIndexInformerFor(Pod::class.java, PodList::class.java, 10 * 60 * 1000.toLong())

    /**
     * Listers
     */
    private val shinyProxyLister = Lister(shinyProxyInformer.indexer, namespace)
    private val replicaSetLister = Lister(replicaSetInformer.indexer, namespace)
    private val configMapLister = Lister(configMapInformer.indexer, namespace)
    private val serviceLister = Lister(serviceInformer.indexer, namespace)
    private val ingressLister = Lister(ingressInformer.indexer, namespace)
    private val podLister = Lister(podInformer.indexer)

    /**
     * Listeners
     * Note: it is normal that these are unused, since they only perform background processing
     */
    private val shinyProxyListener = ShinyProxyListener(sendChannel, shinyProxyInformer, shinyProxyLister)
    private val replicaSetListener = ResourceListener(sendChannel, replicaSetInformer, shinyProxyLister)
    private val serviceListener = ResourceListener(sendChannel, serviceInformer, shinyProxyLister)
    private val configMapListener = ResourceListener(sendChannel, configMapInformer, shinyProxyLister)

    /**
     * Helpers
     */
    private val resourceRetriever = ResourceRetriever(replicaSetLister, configMapLister, serviceLister, podLister, ingressLister)

    /**
     * Controllers
     */
    private val ingressController = IngressController(channel, ingressInformer, shinyProxyLister, client, resourceRetriever)
    private val shinyProxyController = ShinyProxyController(channel, client, shinyProxyClient, replicaSetInformer, shinyProxyInformer, ingressController, resourceRetriever, shinyProxyLister)

    suspend fun run() {
        informerFactory.startAllRegisteredInformers()

        informerFactory.addSharedInformerEventListener {
            logger.warn { "Exception occurred, but caught $it" }
        }

        shinyProxyController.run()
    }
}