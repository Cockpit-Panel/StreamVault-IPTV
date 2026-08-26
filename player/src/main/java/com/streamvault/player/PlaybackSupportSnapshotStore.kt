package com.streamvault.player

import android.content.Context
import android.system.Os
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackSupportSnapshotStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val reportFile = File(context.filesDir, "diagnostics/crash/latest-playback-support.txt")
    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val failureLogger = RateLimitedSnapshotFailureLogger(
        log = { error -> Log.w(TAG, "Unable to persist playback support snapshot", error) }
    )
    private val writer = CoalescingSnapshotWriter(writerScope, ::writeLatest)

    /**
     * Queues only the newest diagnostic snapshot. Playback diagnostics are optional, so a
     * slow storage device must never make the player accumulate pending file writes.
     */
    fun write(report: String) {
        writer.submit(report)
    }

    private fun writeLatest(report: String) {
        runCatching {
            writeSnapshotAtomically(reportFile, report) { temporaryPath, targetPath ->
                Os.rename(temporaryPath, targetPath)
            }
        }.onFailure(::logWriteFailure)
    }

    private fun logWriteFailure(error: Throwable) {
        failureLogger.log(error)
    }

    private companion object {
        const val TAG = "PlaybackSupportSnapshot"
    }
}

internal class RateLimitedSnapshotFailureLogger(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val intervalMs: Long = 60_000L,
    private val log: (Throwable) -> Unit
) {
    private var lastLoggedAtMs: Long? = null

    @Synchronized
    fun log(error: Throwable) {
        val now = nowMs()
        val lastLogged = lastLoggedAtMs
        if (lastLogged != null && now - lastLogged < intervalMs) return
        lastLoggedAtMs = now
        log.invoke(error)
    }
}

internal fun writeSnapshotAtomically(
    reportFile: File,
    report: String,
    rename: (temporaryPath: String, targetPath: String) -> Unit
) {
    val parent = reportFile.parentFile ?: return
    parent.mkdirs()
    val temporaryFile = File(parent, "${reportFile.name}.tmp")
    temporaryFile.writeText(report, Charsets.UTF_8)
    rename(temporaryFile.absolutePath, reportFile.absolutePath)
}

/** Single-owner, latest-value-wins writer used by optional playback diagnostics. */
internal class CoalescingSnapshotWriter(
    scope: CoroutineScope,
    private val writeLatest: (String) -> Unit
) {
    private val pendingReports = Channel<String>(capacity = Channel.CONFLATED)
    private val worker = scope.launch {
        for (report in pendingReports) {
            writeLatest(report)
        }
    }

    fun submit(report: String) {
        pendingReports.trySend(report)
    }

    suspend fun close() {
        pendingReports.close()
        worker.join()
    }
}
