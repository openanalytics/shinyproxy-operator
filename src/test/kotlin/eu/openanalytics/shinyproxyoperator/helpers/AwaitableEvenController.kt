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
package eu.openanalytics.shinyproxyoperator.helpers

import eu.openanalytics.shinyproxyoperator.IEventController
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance
import kotlinx.coroutines.CompletableDeferred


class AwaitableEvenController : IEventController {

    private val listeners = Listener<String>()
    private val deleteListeners = Listener<Unit>()
    private val newInstanceListeners = Listener<ShinyProxyInstance>()
    private val inputErrorListeners = mutableListOf<CompletableDeferred<String>>()
    private lateinit var delegate: IEventController

    fun setDelegate(eventController: IEventController) {
        this.delegate = eventController
    }

    suspend fun waitForNextReconcile(hash: String, revision: Int = 0): String {
        return listeners.add(hash, revision).awaitWithTimeout()
    }

    suspend fun waitForNewInstance(hash: String, revision: Int = 0): ShinyProxyInstance {
        return newInstanceListeners.add(hash, revision).awaitWithTimeout()
    }

    override fun createNewInstanceEvent(shinyProxyInstance: ShinyProxyInstance) {
        delegate.createNewInstanceEvent(shinyProxyInstance)
        newInstanceListeners.complete(shinyProxyInstance, shinyProxyInstance)
    }

    override fun createInstanceReadyEvent(shinyProxyInstance: ShinyProxyInstance) {
        delegate.createInstanceReadyEvent(shinyProxyInstance)
        listeners.complete(shinyProxyInstance, "InstanceReady")
    }

    override fun createInstanceFailed(shinyProxyInstance: ShinyProxyInstance, message: String?) {
        delegate.createInstanceFailed(shinyProxyInstance, message)
        listeners.complete(shinyProxyInstance, "StartingNewInstanceFailed")
    }

    override fun createDeletingInstanceEvent(shinyProxyInstance: ShinyProxyInstance) {
        delegate.createDeletingInstanceEvent(shinyProxyInstance)
    }

    override fun createInstanceDeletedEvent(shinyProxyInstance: ShinyProxyInstance) {
        delegate.createInstanceDeletedEvent(shinyProxyInstance)
        deleteListeners.complete(shinyProxyInstance, Unit)
    }

    override fun createInstanceReconciledEvent(shinyProxyInstance: ShinyProxyInstance) {
        delegate.createInstanceReconciledEvent(shinyProxyInstance)
        listeners.complete(shinyProxyInstance, "Reconciled")
    }

    override fun inputError(message: String) {
        delegate.inputError(message)
        synchronized(inputErrorListeners) {
            inputErrorListeners.forEach { it.complete(message) }
            inputErrorListeners.clear()
        }
    }

    fun waitForDeletion(hash: String, revision: Int = 0): CompletableDeferred<Unit> {
        return deleteListeners.add(hash, revision)
    }

    suspend fun waitForInputError(): String {
        val future = CompletableDeferred<String>()
        synchronized(listeners) {
            inputErrorListeners.add(future)
        }
        return future.awaitWithTimeout()
    }

    class Listener<T> {
        private val listeners = mutableMapOf<Pair<String, Int>, ArrayList<CompletableDeferred<T>>>()

        fun add(hash: String, revision: Int = 0): CompletableDeferred<T> {
            val future = CompletableDeferred<T>()
            synchronized(listeners) {
                val key = Pair(hash, revision)
                if (!listeners.containsKey(key)) {
                    listeners[key] = arrayListOf()
                }
                listeners[key]!!.add(future)
            }
            return future
        }

        fun complete(shinyProxyInstance: ShinyProxyInstance, value: T) {
            synchronized(listeners) {
                val key = Pair(shinyProxyInstance.hashOfSpec, shinyProxyInstance.revision)
                val currentListeners = listeners.remove(key) ?: return
                currentListeners.forEach { it.complete(value) }
            }
        }

    }

}
