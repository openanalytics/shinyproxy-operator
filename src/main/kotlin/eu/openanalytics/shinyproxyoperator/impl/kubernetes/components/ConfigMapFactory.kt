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
package eu.openanalytics.shinyproxyoperator.impl.kubernetes.components

import eu.openanalytics.shinyproxyoperator.LabelFactory
import eu.openanalytics.shinyproxyoperator.logPrefix
import eu.openanalytics.shinyproxyoperator.model.ShinyProxy
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.oshai.kotlinlogging.KotlinLogging


class ConfigMapFactory(private val kubeClient: KubernetesClient) {

    private val logger = KotlinLogging.logger {}

    fun create(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance, shinyProxyUid: String) {
        if (shinyProxy.hashOfCurrentSpec != shinyProxyInstance.hashOfSpec) {
            logger.warn { "${logPrefix(shinyProxyInstance)} Cannot re-create ConfigMap for old instance" }
            return
        }

        //@formatter:off
        val configMapDefinition: ConfigMap = ConfigMapBuilder()
                .withNewMetadata()
                    .withNamespace(shinyProxy.namespace)
                    .withName(ResourceNameFactory.createNameForConfigMap(shinyProxy, shinyProxyInstance))
                    .withLabels<String, String>(LabelFactory.labelsForShinyProxyInstance( shinyProxyInstance))
                    .addNewOwnerReference()
                        .withController(true)
                        .withKind("ShinyProxy")
                        .withApiVersion("openanalytics.eu/v1")
                        .withName(shinyProxy.name)
                        .withUid(shinyProxyUid)
                    .endOwnerReference()
                .endMetadata()
                .addToData("application.yml", shinyProxy.specAsYaml)
                .build()
        //@formatter:on
        val createdConfigMap = kubeClient.configMaps().inNamespace(shinyProxy.namespace).resource(configMapDefinition).forceConflicts().serverSideApply()
        logger.debug { "${logPrefix(shinyProxyInstance)} [Component/ConfigMap] Created ${createdConfigMap.metadata.name}" }
    }

}
