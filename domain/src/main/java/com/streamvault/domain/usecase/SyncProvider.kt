package com.streamvault.domain.usecase

import com.streamvault.domain.manager.ProviderSyncStateReader
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.SyncState
import com.streamvault.domain.repository.ProviderRepository
import javax.inject.Inject

data class SyncProviderCommand(
    val providerId: Long,
    val force: Boolean = true,
    val movieFastSyncOverride: Boolean? = null,
    val epgSyncModeOverride: ProviderEpgSyncMode? = null
)

sealed class SyncProviderResult {
    data class Success(
        val provider: Provider,
        val warnings: List<String> = emptyList()
    ) : SyncProviderResult() {
        val isPartial: Boolean
            get() = provider.status == ProviderStatus.PARTIAL || warnings.isNotEmpty()
    }

    data class Error(val message: String, val exception: Throwable? = null) : SyncProviderResult()
}

class SyncProvider @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val providerSyncStateReader: ProviderSyncStateReader
) {
    suspend operator fun invoke(
        command: SyncProviderCommand,
        onProgress: ((String) -> Unit)? = null
    ): SyncProviderResult {
        if (command.providerId <= 0L) {
            return SyncProviderResult.Error("Provider context is unavailable.")
        }

        return when (
            val refreshResult = providerRepository.refreshProviderData(
                providerId = command.providerId,
                force = command.force,
                movieFastSyncOverride = command.movieFastSyncOverride,
                epgSyncModeOverride = command.epgSyncModeOverride,
                onProgress = onProgress
            )
        ) {
            is Result.Success -> {
                val provider = providerRepository.getProvider(command.providerId)
                    ?: return SyncProviderResult.Error("Provider refresh completed, but provider details are unavailable.")
                val warnings = (providerSyncStateReader.currentSyncState(command.providerId) as? SyncState.Partial)
                    ?.warnings
                    .orEmpty()
                SyncProviderResult.Success(provider = provider, warnings = warnings)
            }

            is Result.Error -> SyncProviderResult.Error(refreshResult.message, refreshResult.exception)
            is Result.Loading -> SyncProviderResult.Error("Unexpected loading state")
        }
    }
}
