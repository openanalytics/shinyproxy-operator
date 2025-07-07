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
package eu.openanalytics.shinyproxyoperator.model

data class ShinyProxyStatus(val realmId: String, val hashOfCurrentSpec: String, val instances: List<ShinyProxyInstance> = arrayListOf()) {

    fun getInstanceByHash(hash: String): ShinyProxyInstance? {
        return instances.filter { it.hashOfSpec == hash }.maxByOrNull { it.revision }
    }

    fun getInstance(shinyProxyInstance: ShinyProxyInstance): ShinyProxyInstance? {
        return instances.firstOrNull { it.hashOfSpec == shinyProxyInstance.hashOfSpec && it.revision == shinyProxyInstance.revision }
    }

    fun latestInstance(): ShinyProxyInstance? {
        return instances.firstOrNull { it.isLatestInstance }
    }

}
