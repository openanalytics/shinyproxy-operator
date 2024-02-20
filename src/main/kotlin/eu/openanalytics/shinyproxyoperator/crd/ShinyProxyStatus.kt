/**
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
package eu.openanalytics.shinyproxyoperator.crd

import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.fabric8.kubernetes.api.model.KubernetesResource


@JsonDeserialize(using = JsonDeserializer.None::class)
data class ShinyProxyStatus(val instances: ArrayList<ShinyProxyInstance> = arrayListOf()) : KubernetesResource {

    fun getInstanceByHash(hash: String): ShinyProxyInstance? {
        return instances.filter { it.hashOfSpec == hash }.maxByOrNull { it.revision }
    }

    fun latestInstance(): ShinyProxyInstance? {
        return instances.firstOrNull { it.isLatestInstance }
    }

}
