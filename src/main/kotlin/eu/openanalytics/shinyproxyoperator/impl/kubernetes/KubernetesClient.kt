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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.NamespacedKubernetesClient
import io.fabric8.kubernetes.client.http.HttpClient
import io.fabric8.kubernetes.client.utils.KubernetesSerialization


fun createKubernetesClient(httpClientFactory: HttpClient.Factory? = null): NamespacedKubernetesClient {
    val objectMapper = ObjectMapper()
    objectMapper.registerKotlinModule()
    val kubernetesSerialization = KubernetesSerialization(objectMapper, true)
    val builder = KubernetesClientBuilder()

    if (httpClientFactory != null) {
        builder.withHttpClientFactory(httpClientFactory)
    }

    return builder.withKubernetesSerialization(kubernetesSerialization)
        .build()
        .adapt(NamespacedKubernetesClient::class.java)
}
