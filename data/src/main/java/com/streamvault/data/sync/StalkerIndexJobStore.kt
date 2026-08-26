package com.streamvault.data.sync

import com.streamvault.data.local.dao.StalkerIndexJobDao
import com.streamvault.data.local.entity.StalkerIndexJobEntity
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.StalkerIndexState
import javax.inject.Inject
import javax.inject.Singleton

/** Owns the durable Stalker movie/series index-job representation and state mapping. */
@Singleton
class StalkerIndexJobStore @Inject constructor(
    private val dao: StalkerIndexJobDao
) {
    suspend fun get(providerId: Long, section: ContentType): StalkerIndexJobEntity? =
        dao.get(providerId, section.name)

    suspend fun deleteByProvider(providerId: Long): Int = dao.deleteByProvider(providerId)

    internal fun shouldRunSummary(job: StalkerIndexJobEntity?): Boolean {
        if (job == null) return true
        if (job.state in setOf(
                StalkerIndexState.QUEUED,
                StalkerIndexState.RUNNING,
                StalkerIndexState.PARTIAL,
                StalkerIndexState.RETRY_WAIT
            )) return true
        return ContentCachePolicy.shouldRefresh(job.lastSuccessAt, ContentCachePolicy.CATALOG_TTL_MILLIS)
    }

    internal fun toLegacyState(state: StalkerIndexState): String = when (state) {
        StalkerIndexState.DISABLED -> "DISABLED"
        StalkerIndexState.QUEUED -> "QUEUED"
        StalkerIndexState.RUNNING -> "RUNNING"
        StalkerIndexState.RETRY_WAIT -> "FAILED_RETRYABLE"
        StalkerIndexState.PARTIAL -> "PARTIAL"
        StalkerIndexState.COMPLETE -> "SUCCESS"
        StalkerIndexState.TRUNCATED -> "TRUNCATED"
        StalkerIndexState.FAILED -> "FAILED_PERMANENT"
    }

    internal suspend fun upsertLegacy(
        providerId: Long,
        section: ContentType,
        state: String,
        now: Long,
        totalCategories: Int? = null,
        completedCategories: Int? = null,
        nextCategoryIndex: Int? = null,
        failedCategories: Int? = null,
        indexedRows: Int? = null,
        skippedMalformedRows: Int? = null,
        deletedPrunedRows: Int? = null,
        lastAttemptAt: Long? = null,
        lastSuccessAt: Long? = null,
        lastError: String? = null
    ) {
        upsert(
            StalkerIndexJobUpdate(
                providerId = providerId,
                section = section,
                state = state.toStalkerState(),
                now = now,
                totalCategories = totalCategories,
                completedCategories = completedCategories,
                nextCategoryIndex = nextCategoryIndex,
                failedCategories = failedCategories,
                indexedRows = indexedRows,
                skippedMalformedRows = skippedMalformedRows,
                deletedPrunedRows = deletedPrunedRows,
                lastAttemptAt = lastAttemptAt,
                lastSuccessAt = lastSuccessAt,
                lastError = lastError
            )
        )
    }

    internal suspend fun upsert(update: StalkerIndexJobUpdate) {
        require(update.section in setOf(ContentType.MOVIE, ContentType.SERIES)) {
            "Unsupported Stalker index section: ${update.section}"
        }
        val existing = dao.get(update.providerId, update.section.name)
        dao.upsert(
            (existing ?: StalkerIndexJobEntity(
                providerId = update.providerId,
                section = update.section
            )).copy(
                state = update.state,
                totalCategories = update.totalCategories ?: existing?.totalCategories ?: 0,
                completedCategories = update.completedCategories ?: existing?.completedCategories ?: 0,
                nextCategoryIndex = update.nextCategoryIndex ?: existing?.nextCategoryIndex ?: 0,
                failedCategories = update.failedCategories ?: existing?.failedCategories ?: 0,
                indexedRows = update.indexedRows ?: existing?.indexedRows ?: 0,
                skippedMalformedRows = update.skippedMalformedRows ?: existing?.skippedMalformedRows ?: 0,
                deletedPrunedRows = update.deletedPrunedRows ?: existing?.deletedPrunedRows ?: 0,
                lastError = update.lastError,
                lastAttemptAt = update.lastAttemptAt ?: existing?.lastAttemptAt ?: 0L,
                lastSuccessAt = update.lastSuccessAt ?: existing?.lastSuccessAt ?: 0L,
                updatedAt = update.now
            )
        )
    }

    private fun String.toStalkerState(): StalkerIndexState = when (this) {
        "QUEUED", "STALE" -> StalkerIndexState.QUEUED
        "RUNNING" -> StalkerIndexState.RUNNING
        "FAILED_RETRYABLE" -> StalkerIndexState.RETRY_WAIT
        "PARTIAL" -> StalkerIndexState.PARTIAL
        "SUCCESS", "COMPLETE" -> StalkerIndexState.COMPLETE
        "TRUNCATED" -> StalkerIndexState.TRUNCATED
        "FAILED_PERMANENT", "FAILED" -> StalkerIndexState.FAILED
        "DISABLED", "IDLE" -> StalkerIndexState.DISABLED
        else -> StalkerIndexState.PARTIAL
    }
}

internal data class StalkerIndexJobUpdate(
    val providerId: Long,
    val section: ContentType,
    val state: StalkerIndexState,
    val now: Long,
    val totalCategories: Int? = null,
    val completedCategories: Int? = null,
    val nextCategoryIndex: Int? = null,
    val failedCategories: Int? = null,
    val indexedRows: Int? = null,
    val skippedMalformedRows: Int? = null,
    val deletedPrunedRows: Int? = null,
    val lastAttemptAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastError: String? = null
)
