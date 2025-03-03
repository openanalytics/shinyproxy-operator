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
package eu.openanalytics.shinyproxyoperator

import eu.openanalytics.shinyproxyoperator.model.ShinyProxy
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyStatus


interface IOrchestrator {

    suspend fun init(source: IShinyProxySource)

    fun getShinyProxyStatus(shinyProxy: ShinyProxy): ShinyProxyStatus?

    fun getShinyProxyStatuses() : List<ShinyProxyStatus>

    fun addNewInstanceToStatus(shinyProxy: ShinyProxy, newInstance: ShinyProxyInstance)

    suspend fun removeInstanceFromStatus(instance: ShinyProxyInstance)

    fun makeLatest(shinyProxy: ShinyProxy, instance: ShinyProxyInstance)

    suspend fun reconcileInstance(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance): Boolean

    suspend fun deleteInstance(shinyProxyInstance: ShinyProxyInstance)

    suspend fun reconcileIngress(shinyProxy: ShinyProxy, latestShinyProxyInstance: ShinyProxyInstance)

    suspend fun deleteRealm(realmId: String)

    fun getContainerIPs(shinyProxyInstance: ShinyProxyInstance): List<String>

    fun logEvent(shinyProxyInstance: ShinyProxyInstance, type: String, action: String, message: String? = null)

    fun logEvent(type: String, action: String, message: String? = null)

}
