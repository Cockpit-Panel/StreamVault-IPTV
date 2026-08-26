package com.streamvault.domain.repository

import com.streamvault.domain.model.ProviderSnapshot
import com.streamvault.domain.model.StalkerPortalLearning
import com.streamvault.domain.model.CatalogLayout

interface ProviderSnapshotRepository {
    suspend fun getSnapshot(providerId: Long): ProviderSnapshot?

    /** Returns false when the observation was produced by a stale configuration generation. */
    suspend fun compareAndSetStalkerLearning(
        providerId: Long,
        learning: StalkerPortalLearning
    ): Boolean

    suspend fun updateCatalogLayout(providerId: Long, layout: CatalogLayout, detectionVersion: Int)
}
