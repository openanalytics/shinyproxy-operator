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

import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.utils.HttpClientUtils
import mu.KotlinLogging
import okhttp3.Interceptor
import okhttp3.Response
import java.net.HttpURLConnection
import kotlin.random.Random


class ChaosInterceptor : Interceptor {

    private val logger = KotlinLogging.logger { }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (request.method() != "GET" && request.method() != "DELETE" && Random.nextInt(0, 10) < 5) {
            logger.warn { "Intercepting request to ${request.method()} @ ${request.url()} -> returning 500" }
            throw KubernetesClientException(
                "The ${request.method()} operation could not be completed at this time, please try again",
                HttpURLConnection.HTTP_INTERNAL_ERROR,
                StatusBuilder().withCode(HttpURLConnection.HTTP_INTERNAL_ERROR).build())
        }

        if ((request.method() == "POST" || request.method() == "PUT") && Random.nextInt(0, 10) < 5) {
            chain.proceed(request)
            throw KubernetesClientException(
                "Already exist",
                HttpURLConnection.HTTP_CONFLICT,
                StatusBuilder().withCode(HttpURLConnection.HTTP_CONFLICT).build())
        }

        return chain.proceed(request)
    }

    companion object {
        fun createChaosKubernetesClient(): DefaultKubernetesClient {
            val config = ConfigBuilder().build()
            val httpClient = HttpClientUtils.createHttpClient(config).newBuilder().addInterceptor(ChaosInterceptor()).build()

            return DefaultKubernetesClient(httpClient, config)
        }
    }

}
