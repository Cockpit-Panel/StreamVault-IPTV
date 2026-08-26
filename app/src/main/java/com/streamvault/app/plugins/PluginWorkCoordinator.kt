package com.streamvault.app.plugins

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Application-lifetime owner for non-durable plugin reconciliation and catalog refresh work. */
@Singleton
class PluginWorkCoordinator @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var catalogRefreshJob: Job? = null
    private val _catalogRefreshRunning = MutableStateFlow(false)
    val catalogRefreshRunning: StateFlow<Boolean> = _catalogRefreshRunning.asStateFlow()

    fun launchReconciliation(block: suspend () -> Unit): Job = scope.launch { runSafely(block) }

    fun replaceCatalogRefresh(block: suspend () -> Unit) {
        synchronized(lock) {
            catalogRefreshJob?.cancel()
            catalogRefreshJob = scope.launch {
                _catalogRefreshRunning.value = true
                try {
                    runSafely(block)
                } finally {
                    _catalogRefreshRunning.value = false
                    synchronized(lock) {
                        if (catalogRefreshJob === coroutineContext[Job]) catalogRefreshJob = null
                    }
                }
            }
        }
    }

    private suspend fun runSafely(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Startup/package lifecycle reconciliation is the retry boundary.
        }
    }
}
