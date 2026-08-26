package com.streamvault.data.platform

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

enum class DataSyncServiceOwner {
    RECORDING,
    DOWNLOAD
}

data class DataSyncQuotaLease(
    val token: String,
    val owner: DataSyncServiceOwner
)

data class DataSyncQuotaSnapshot(
    val windowStartedAtMs: Long,
    val consumedMs: Long,
    val remainingMs: Long,
    val activeOwners: Set<DataSyncServiceOwner>
)

sealed interface DataSyncQuotaAcquireResult {
    data class Granted(
        val lease: DataSyncQuotaLease,
        val snapshot: DataSyncQuotaSnapshot
    ) : DataSyncQuotaAcquireResult

    data class Exhausted(
        val snapshot: DataSyncQuotaSnapshot
    ) : DataSyncQuotaAcquireResult
}

/**
 * Persists one application-wide accounting ledger for Android's shared dataSync budget.
 *
 * Android remains the authority that invokes Service.onTimeout; this owner makes recording and
 * download lifetimes visible to the same budget, survives process death by charging abandoned
 * sessions on the next operation, and gives reduced-quota device tests a deterministic seam.
 */
@Singleton
class DataSyncQuotaOwner @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val processId = UUID.randomUUID().toString()

    fun acquire(
        owner: DataSyncServiceOwner,
        nowMs: Long = System.currentTimeMillis()
    ): DataSyncQuotaAcquireResult = synchronized(lock) {
        val ledger = loadLedger()
        ledger.recoverAbandonedSessions(processId, nowMs)
        val result = ledger.acquire(owner, nowMs, processId)
        saveLedger(ledger)
        result
    }

    fun release(
        lease: DataSyncQuotaLease,
        nowMs: Long = System.currentTimeMillis()
    ): DataSyncQuotaSnapshot = synchronized(lock) {
        val ledger = loadLedger()
        ledger.recoverAbandonedSessions(processId, nowMs)
        ledger.release(lease, nowMs)
        saveLedger(ledger)
        ledger.snapshot(nowMs)
    }

    fun snapshot(nowMs: Long = System.currentTimeMillis()): DataSyncQuotaSnapshot = synchronized(lock) {
        val ledger = loadLedger()
        ledger.recoverAbandonedSessions(processId, nowMs)
        val snapshot = ledger.snapshot(nowMs)
        saveLedger(ledger)
        snapshot
    }

    private fun loadLedger(): DataSyncQuotaLedger {
        val sessions = runCatching {
            val array = JSONArray(preferences.getString(KEY_SESSIONS, "[]").orEmpty())
            buildMap {
                repeat(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    val token = item.getString(KEY_TOKEN)
                    val owner = DataSyncServiceOwner.valueOf(item.getString(KEY_OWNER))
                    put(
                        token,
                        DataSyncQuotaSession(
                            token = token,
                            owner = owner,
                            startedAtMs = item.getLong(KEY_STARTED_AT),
                            processId = item.optString(KEY_PROCESS_ID)
                        )
                    )
                }
            }.toMutableMap()
        }.getOrDefault(mutableMapOf())
        return DataSyncQuotaLedger(
            windowStartedAtMs = preferences.getLong(KEY_WINDOW_STARTED_AT, 0L),
            consumedMs = preferences.getLong(KEY_CONSUMED_MS, 0L),
            sessions = sessions
        )
    }

    private fun saveLedger(ledger: DataSyncQuotaLedger) {
        val sessions = JSONArray()
        ledger.sessions.values.forEach { session ->
            sessions.put(
                JSONObject()
                    .put(KEY_TOKEN, session.token)
                    .put(KEY_OWNER, session.owner.name)
                    .put(KEY_STARTED_AT, session.startedAtMs)
                    .put(KEY_PROCESS_ID, session.processId)
            )
        }
        // Lease acquisition/release is release-gate state. Commit synchronously so a process kill
        // cannot lose the ledger update that is needed to reconcile the next process.
        preferences.edit(commit = true) {
            putLong(KEY_WINDOW_STARTED_AT, ledger.windowStartedAtMs)
            putLong(KEY_CONSUMED_MS, ledger.consumedMs)
            putString(KEY_SESSIONS, sessions.toString())
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "foreground_service_quota"
        const val KEY_WINDOW_STARTED_AT = "window_started_at"
        const val KEY_CONSUMED_MS = "consumed_ms"
        const val KEY_SESSIONS = "sessions"
        const val KEY_TOKEN = "token"
        const val KEY_OWNER = "owner"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_PROCESS_ID = "process_id"
    }
}

internal data class DataSyncQuotaSession(
    val token: String,
    val owner: DataSyncServiceOwner,
    val startedAtMs: Long,
    val processId: String = ""
)

internal class DataSyncQuotaLedger(
    var windowStartedAtMs: Long,
    var consumedMs: Long,
    val sessions: MutableMap<String, DataSyncQuotaSession>,
    private val maxQuotaMs: Long = DEFAULT_MAX_QUOTA_MS,
    private val windowMs: Long = DEFAULT_WINDOW_MS
) {
    init {
        require(maxQuotaMs > 0L)
        require(windowMs > 0L)
    }

    fun acquire(
        owner: DataSyncServiceOwner,
        nowMs: Long,
        processId: String = ""
    ): DataSyncQuotaAcquireResult {
        accrue(nowMs)
        val current = snapshot(nowMs)
        if (current.remainingMs <= 0L) return DataSyncQuotaAcquireResult.Exhausted(current)

        val lease = DataSyncQuotaLease(UUID.randomUUID().toString(), owner)
        sessions[lease.token] = DataSyncQuotaSession(lease.token, owner, nowMs, processId)
        return DataSyncQuotaAcquireResult.Granted(lease, snapshot(nowMs))
    }

    fun recoverAbandonedSessions(processId: String, nowMs: Long) {
        if (processId.isBlank()) return
        accrue(nowMs)
        sessions.entries.removeIf { (_, session) ->
            session.processId.isNotBlank() && session.processId != processId
        }
    }

    fun release(lease: DataSyncQuotaLease, nowMs: Long) {
        accrue(nowMs)
        sessions.remove(lease.token)
    }

    fun snapshot(nowMs: Long): DataSyncQuotaSnapshot {
        accrue(nowMs)
        return DataSyncQuotaSnapshot(
            windowStartedAtMs = windowStartedAtMs,
            consumedMs = consumedMs.coerceIn(0L, maxQuotaMs),
            remainingMs = (maxQuotaMs - consumedMs).coerceAtLeast(0L),
            activeOwners = sessions.values.mapTo(mutableSetOf(), DataSyncQuotaSession::owner)
        )
    }

    private fun accrue(nowMs: Long) {
        if (windowStartedAtMs <= 0L || nowMs - windowStartedAtMs >= windowMs) {
            windowStartedAtMs = nowMs
            consumedMs = 0L
            sessions.clear()
            return
        }

        if (nowMs <= windowStartedAtMs) return
        val updatedSessions = sessions.values.map { session ->
            val elapsed = (nowMs - session.startedAtMs).coerceAtLeast(0L)
            consumedMs += elapsed
            session.copy(startedAtMs = nowMs)
        }
        sessions.clear()
        updatedSessions.forEach { sessions[it.token] = it }
        consumedMs = consumedMs.coerceAtMost(maxQuotaMs)
    }

    companion object {
        const val DEFAULT_MAX_QUOTA_MS = 6L * 60L * 60L * 1_000L
        const val DEFAULT_WINDOW_MS = 24L * 60L * 60L * 1_000L
    }
}
