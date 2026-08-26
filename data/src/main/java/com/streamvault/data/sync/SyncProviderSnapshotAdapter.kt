package com.streamvault.data.sync

import com.streamvault.data.provider.toLegacyProvider
import com.streamvault.data.provider.toProviderSnapshot
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderSnapshot
import com.streamvault.domain.repository.ProviderSnapshotRepository

/**
 * Owns the temporary schema-cutover projection used by the remaining sync adapters.
 *
 * The coordinator and manager exchange [ProviderSnapshot] values. Legacy projections are kept
 * at the adapter edge until the provider-specific executors finish migrating their low-level
 * ports, so the façade does not become the compatibility conversion site again.
 */
internal class SyncProviderSnapshotAdapter(
    private val snapshots: ProviderSnapshotRepository?
) {
    suspend fun getSnapshot(providerId: Long): ProviderSnapshot? =
        snapshots?.getSnapshot(providerId)

    suspend fun getLegacyProvider(providerId: Long): LegacyProvider? =
        getSnapshot(providerId)?.toLegacyProvider()

    fun toSnapshot(provider: LegacyProvider): ProviderSnapshot = provider.toProviderSnapshot()

    fun toLegacyProvider(snapshot: ProviderSnapshot): LegacyProvider = snapshot.toLegacyProvider()
}
