/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021-2022 Open Analytics
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

import eu.openanalytics.shinyproxyoperator.controller.IReconcileListener
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxyInstance
import kotlinx.coroutines.CompletableDeferred

class ReconcileListener : IReconcileListener {

    private val listeners = mutableMapOf<String, ArrayList<CompletableDeferred<ShinyProxyInstance>>>()

    override fun onInstanceFullyReconciled(shinyProxy: ShinyProxy, shinyProxyInstance: ShinyProxyInstance) {
        val currentListeners = listeners[shinyProxyInstance.hashOfSpec]?.toList() ?: return
        listeners[shinyProxyInstance.hashOfSpec]?.clear()

        currentListeners.forEach { it.complete(shinyProxyInstance) }
    }

    fun waitForNextReconcile(hash: String): CompletableDeferred<ShinyProxyInstance> {
        val future = CompletableDeferred<ShinyProxyInstance>()
        if (!listeners.containsKey(hash)) {
            listeners[hash] = arrayListOf()
        }
        listeners[hash]!!.add(future)

        return future
    }

}