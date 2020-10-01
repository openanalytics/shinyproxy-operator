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