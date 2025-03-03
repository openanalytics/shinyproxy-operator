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
package eu.openanalytics.shinyproxyoperator.impl.kubernetes.helpers

import eu.openanalytics.shinyproxyoperator.IEventController
import eu.openanalytics.shinyproxyoperator.controller.EventController
import eu.openanalytics.shinyproxyoperator.helpers.junit.awaitWithTimeout
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout


class AwaitableEvenController : IEventController {

    private val listeners = mutableMapOf<String, ArrayList<CompletableDeferred<String>>>()
    private val deleteListeners = mutableMapOf<String, ArrayList<CompletableDeferred<Unit>>>()
    private lateinit var delegate: IEventController

    fun setDelegate(eventController: IEventController) {
        this.delegate = eventController
    }

    suspend fun waitForNextReconcile(instance: ShinyProxyTestInstance): String {
        val hash = instance.hash
        val future = CompletableDeferred<String>()
        synchronized(listeners) {
            if (!listeners.containsKey(hash)) {
                listeners[hash] = arrayListOf()
            }
            listeners[hash]!!.add(future)
        }

        return future.awaitWithTimeout()
    }

    private fun complete(shinyProxyInstance: ShinyProxyInstance, action: String) {
        synchronized(listeners) {
            val currentListeners = listeners.remove(shinyProxyInstance.hashOfSpec) ?: return
            currentListeners.forEach { it.complete(action) }
        }
    }

    override fun createNewInstanceEvent(shinyProxyInstance: ShinyProxyInstance) {
        delegate.createNewInstanceEvent(shinyProxyInstance)
    }

    override fun createInstanceReadyEvent(shinyProxyInstance: ShinyProxyInstance) {
        delegate.createInstanceReadyEvent(shinyProxyInstance)
        complete(shinyProxyInstance, "InstanceReady")
    }

    override fun createInstanceFailed(shinyProxyInstance: ShinyProxyInstance, message: String?) {
        delegate.createInstanceFailed(shinyProxyInstance, message)
        complete(shinyProxyInstance, "StartingNewInstanceFailed")
    }

    override fun createDeletingInstanceEvent(shinyProxyInstance: ShinyProxyInstance) {
        delegate.createDeletingInstanceEvent(shinyProxyInstance)
    }

    override fun createInstanceDeletedEvent(shinyProxyInstance: ShinyProxyInstance) {
        delegate.createInstanceDeletedEvent(shinyProxyInstance)
        synchronized(deleteListeners) {
            val currentListeners = deleteListeners.remove(shinyProxyInstance.hashOfSpec) ?: return
            currentListeners.forEach { it.complete(Unit) }
        }
    }

    override fun createInstanceReconciledEvent(shinyProxyInstance: ShinyProxyInstance) {
        delegate.createInstanceReconciledEvent(shinyProxyInstance)
        complete(shinyProxyInstance, "Reconciled")
    }

    override fun inputError(message: String) {
        delegate.inputError(message)
    }

    fun waitForDeletion(instance: ShinyProxyTestInstance): CompletableDeferred<Unit> {
        val hash = instance.hash
        val future = CompletableDeferred<Unit>()
        synchronized(deleteListeners) {
            if (!deleteListeners.containsKey(hash)) {
                deleteListeners[hash] = arrayListOf()
            }
            deleteListeners[hash]!!.add(future)
        }

        return future
    }

}
