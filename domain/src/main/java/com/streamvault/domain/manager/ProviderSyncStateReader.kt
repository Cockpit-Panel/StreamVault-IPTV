package com.streamvault.domain.manager

import com.streamvault.domain.model.SyncState
import com.streamvault.domain.model.StalkerReadinessSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface ProviderSyncStateReader {
    fun currentSyncState(providerId: Long): SyncState
    fun currentStalkerReadiness(providerId: Long): StalkerReadinessSnapshot? = null
    fun observeStalkerReadiness(providerId: Long): Flow<StalkerReadinessSnapshot?> = flowOf(null)
    fun observeBackgroundIndexingActive(providerId: Long): Flow<Boolean>
}
