package com.streamvault.data.sync

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.util.KeyedMutexRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the narrower locks used by interactive hydration and incremental index work.
 *
 * The provider-wide execution lane lives in [ProviderWorkLockRegistry]. These locks deliberately
 * remain separate because category hydration and Stalker summary/index work can be concurrent
 * across independent categories while still requiring same-key serialization.
 */
@Singleton
class ProviderSyncLockRegistry @Inject constructor() {
    private val vodCategoryLocks = KeyedMutexRegistry<String>()
    private val stalkerSummaryLocks = KeyedMutexRegistry<Long>()
    private val stalkerIndexSectionLocks = KeyedMutexRegistry<String>()

    suspend fun <T> withVodCategoryLock(
        providerId: Long,
        categoryId: Long,
        splitCatalog: Boolean,
        block: suspend () -> T
    ): T = vodCategoryLocks.withLock(vodCategoryKey(providerId, categoryId, splitCatalog), block)

    suspend fun <T> withStalkerSummaryLock(
        providerId: Long,
        block: suspend () -> T
    ): T = stalkerSummaryLocks.withLock(providerId, block)

    suspend fun <T> withStalkerIndexSectionLock(
        providerId: Long,
        section: ContentType,
        block: suspend () -> T
    ): T = stalkerIndexSectionLocks.withLock("$providerId:${section.name}", block)

    /** Removes idle keys after durable provider deletion. */
    suspend fun forgetProvider(providerId: Long) {
        stalkerSummaryLocks.forget(providerId)
        // Category/section entries release themselves when their last active/waiting user exits.
    }

    private fun vodCategoryKey(providerId: Long, categoryId: Long, splitCatalog: Boolean): String =
        if (splitCatalog) "split:$providerId:$categoryId" else "$providerId:$categoryId"

}
