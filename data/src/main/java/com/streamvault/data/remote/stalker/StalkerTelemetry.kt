package com.streamvault.data.remote.stalker

import android.util.Log
import com.streamvault.domain.model.StalkerRequestPriority
import com.streamvault.domain.model.StalkerReadinessSnapshot
import java.security.MessageDigest

/** Sanitized structured diagnostics. Values accepted here must never contain portal secrets or URLs. */
internal object StalkerTelemetry {
    fun catalogLayoutDetected(
        providerId: Long,
        layout: String,
        seriesCategoryCount: Int,
        vodCategoryCount: Int,
        evidence: String
    ) {
        val event = buildString {
            append("event=catalog_layout_detected")
            append(" provider=").append(providerHash(providerId))
            append(" layout=").append(safeLabel(layout))
            append(" series_categories=").append(seriesCategoryCount.coerceAtLeast(0))
            append(" vod_categories=").append(vodCategoryCount.coerceAtLeast(0))
            append(" evidence=").append(safeLabel(evidence))
        }
        runCatching { Log.i(TAG, event) }
    }

    fun missingVodClassification(providerId: Long) {
        val event = "event=vod_classification_missing provider=${providerHash(providerId)} fallback=MOVIE"
        runCatching { Log.w(TAG, event) }
    }

    fun capabilityChanged(providerId: Long, capability: String, outcome: String) {
        val event = buildString {
            append("event=capability_state")
            append(" provider=").append(providerHash(providerId))
            append(" capability=").append(safeLabel(capability))
            append(" outcome=").append(safeLabel(outcome))
        }
        runCatching { Log.i(TAG, event) }
    }

    fun strategySelected(providerId: Long, strategy: String, reason: String) {
        val event = buildString {
            append("event=strategy_selected")
            append(" provider=").append(providerHash(providerId))
            append(" strategy=").append(safeLabel(strategy))
            append(" reason=").append(safeLabel(reason))
        }
        runCatching { Log.i(TAG, event) }
    }

    fun authenticationAttempt(
        providerId: Long,
        profileId: String,
        endpointFamily: String,
        stage: String,
        outcome: String
    ) {
        val event = buildString {
            append("event=authentication_attempt")
            append(" provider=").append(providerHash(providerId))
            append(" profile=").append(safeLabel(profileId))
            append(" endpoint_family=").append(safeLabel(endpointFamily))
            append(" stage=").append(safeLabel(stage))
            append(" outcome=").append(safeLabel(outcome))
        }
        runCatching { Log.i(TAG, event) }
    }

    fun httpResponse(
        providerId: Long,
        endpointFamily: String,
        action: String?,
        durationMillis: Long,
        responseBytes: Long,
        status: Int,
        outcome: String
    ) {
        val event = buildString {
            append("event=http_response")
            append(" provider=").append(providerHash(providerId))
            append(" endpoint_family=").append(safeLabel(endpointFamily))
            append(" action=").append(safeLabel(action.orEmpty()))
            append(" duration_ms=").append(durationMillis.coerceAtLeast(0L))
            append(" bytes=").append(responseBytes.coerceAtLeast(0L))
            append(" status=").append(status.coerceAtLeast(0))
            append(" outcome=").append(safeLabel(outcome))
        }
        runCatching { Log.i(TAG, event) }
    }

    fun indexProgress(
        providerId: Long,
        workId: String,
        section: String,
        state: String,
        completedCategories: Int,
        totalCategories: Int,
        indexedRows: Int
    ) {
        val event = buildString {
            append("event=index_checkpoint")
            append(" provider=").append(providerHash(providerId))
            append(" work_id=").append(hashOpaque(workId))
            append(" section=").append(safeLabel(section))
            append(" state=").append(safeLabel(state))
            append(" completed_categories=").append(completedCategories.coerceAtLeast(0))
            append(" total_categories=").append(totalCategories.coerceAtLeast(0))
            append(" indexed_rows=").append(indexedRows.coerceAtLeast(0))
        }
        runCatching { Log.i(TAG, event) }
    }

    fun readinessMilestone(snapshot: StalkerReadinessSnapshot) {
        val event = buildString {
            append("event=readiness_milestone")
            append(" provider=").append(providerHash(snapshot.providerId))
            append(" state=").append(snapshot.state.name)
            append(" elapsed_ms=").append(
                (milestoneTime(snapshot) - snapshot.syncStartedAt).coerceAtLeast(0L)
            )
            append(" warning_count=").append(snapshot.warningCount.coerceAtLeast(0))
        }
        runCatching { Log.i(TAG, event) }
    }

    fun requestCompleted(
        providerId: Long,
        priority: StalkerRequestPriority,
        descriptor: StalkerRequestDescriptor,
        responseMetrics: StalkerResponseMetrics,
        durationMillis: Long,
        active: Int,
        queued: Int,
        concurrencyLimit: Int,
        stressCooldownUntil: Long,
        outcome: String
    ) {
        val event = buildRequestEvent(
            providerId = providerId,
            priority = priority,
            descriptor = descriptor,
            responseMetrics = responseMetrics,
            durationMillis = durationMillis,
            active = active,
            queued = queued,
            concurrencyLimit = concurrencyLimit,
            stressCooldownUntil = stressCooldownUntil,
            outcome = outcome
        )
        runCatching { Log.i(TAG, event) }
    }

    internal fun buildRequestEvent(
        providerId: Long,
        priority: StalkerRequestPriority,
        descriptor: StalkerRequestDescriptor,
        responseMetrics: StalkerResponseMetrics,
        durationMillis: Long,
        active: Int,
        queued: Int,
        concurrencyLimit: Int,
        stressCooldownUntil: Long,
        outcome: String,
        now: Long = System.currentTimeMillis()
    ): String {
        val cooldownRemaining = (stressCooldownUntil - now).coerceAtLeast(0L)
        return buildString {
            append("event=metadata_request")
            append(" provider=").append(providerHash(providerId))
            append(" priority=").append(priority.name)
            append(" content=").append(safeLabel(descriptor.contentType))
            append(" action=").append(safeLabel(descriptor.action))
            descriptor.page?.let { append(" page=").append(it.coerceAtLeast(1)) }
            descriptor.workId?.let { append(" work_id=").append(hashOpaque(it)) }
            responseMetrics.items?.let { append(" items=").append(it.coerceAtLeast(0)) }
            responseMetrics.pages?.let { append(" pages=").append(it.coerceAtLeast(0)) }
            responseMetrics.advertisedTotal?.let { append(" advertised_total=").append(it.coerceAtLeast(0)) }
            responseMetrics.truncated?.let { append(" truncated=").append(it) }
            responseMetrics.terminationReason?.let { append(" termination=").append(safeTerminationReason(it)) }
            append(" duration_ms=").append(durationMillis.coerceAtLeast(0L))
            append(" active=").append(active.coerceAtLeast(0))
            append(" queued=").append(queued.coerceAtLeast(0))
            append(" concurrency_limit=").append(concurrencyLimit.coerceIn(1, 2))
            append(" cooldown_remaining_ms=").append(cooldownRemaining)
            append(" outcome=").append(safeLabel(outcome))
        }
    }

    private fun providerHash(providerId: Long): String = MessageDigest.getInstance("SHA-256")
        .digest("streamvault/stalker/provider/$providerId".toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun hashOpaque(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun safeLabel(value: String): String = value
        .uppercase()
        .filter { it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }
        .take(40)
        .ifBlank { "UNKNOWN" }

    private fun safeTerminationReason(value: String): String = when (safeLabel(value)) {
        "PAGE_LIMIT" -> "PAGE_LIMIT"
        "REPEATED_PAGE" -> "REPEATED_PAGE"
        "WRONG_PAGE" -> "WRONG_PAGE"
        "EARLY_EMPTY_PAGE" -> "EARLY_EMPTY_PAGE"
        "MALFORMED_PAGE" -> "MALFORMED_PAGE"
        else -> "OTHER"
    }

    private fun milestoneTime(snapshot: StalkerReadinessSnapshot): Long = when (snapshot.state) {
        com.streamvault.domain.model.StalkerReadiness.AUTHENTICATING -> snapshot.syncStartedAt
        com.streamvault.domain.model.StalkerReadiness.LIVE_READY -> snapshot.liveReadyAt
        com.streamvault.domain.model.StalkerReadiness.CATEGORIES_READY -> snapshot.categoriesReadyAt
        com.streamvault.domain.model.StalkerReadiness.READY,
        com.streamvault.domain.model.StalkerReadiness.READY_WITH_WARNINGS -> snapshot.readyAt
    } ?: System.currentTimeMillis()

    private const val TAG = "StalkerTelemetry"
}
