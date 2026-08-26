package com.streamvault.data.remote.stalker

import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StalkerDiscoveryProgress
import com.streamvault.domain.model.StalkerDiscoveryStage

/**
 * Foreground boundary for bounded classic-MAG discovery.
 *
 * The RPC service still owns wire decoding and compatibility recipes; callers no longer need to
 * know that authentication also performs the Live readiness gate. This boundary is intentionally
 * small so endpoint planning can continue moving out of [OkHttpStalkerApiService] incrementally.
 */
class StalkerDiscoveryCoordinator(
    private val api: StalkerApiService
) {
    suspend fun authenticate(
        profile: StalkerDeviceProfile,
        onProgress: ((StalkerDiscoveryProgress) -> Unit)? = null
    ): Result<Pair<StalkerSession, StalkerProviderProfile>> {
        val startedAt = System.currentTimeMillis()
        onProgress?.invoke(
            StalkerDiscoveryProgress(
                stage = StalkerDiscoveryStage.AUTHENTICATION,
                attempt = 1,
                limit = profile.discoveryBudget.maxRequests,
                elapsedMillis = 0L
            )
        )
        val result = api.authenticate(profile)
        onProgress?.invoke(
            StalkerDiscoveryProgress(
                stage = if (result is Result.Success) {
                    StalkerDiscoveryStage.LIVE_READINESS
                } else {
                    StalkerDiscoveryStage.AUTHENTICATION
                },
                attempt = profile.discoveryRuntime.requestCount,
                limit = profile.discoveryBudget.maxRequests,
                elapsedMillis = System.currentTimeMillis() - startedAt
            )
        )
        return result
    }
}
