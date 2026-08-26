package com.streamvault.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.manager.ProviderCredentials
import com.streamvault.domain.manager.ProviderSyncStateReader
import com.streamvault.domain.model.ChannelLogoSourcePolicy
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.StalkerCatalogMode
import com.streamvault.domain.model.StalkerProtocolPreference
import com.streamvault.domain.model.StalkerTransportGrant
import com.streamvault.domain.model.SyncState
import com.streamvault.domain.repository.ProviderDeleteProgress
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.ProviderSetupRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SyncProviderTest {

    @Test
    fun returns_error_when_provider_id_is_missing() = runTest {
        val useCase = SyncProvider(
            providerRepository = FakeSyncProviderRepository(),
            providerSyncStateReader = FakeSyncStateReader()
        )

        val result = useCase(SyncProviderCommand(providerId = 0L))

        assertThat(result).isInstanceOf(SyncProviderResult.Error::class.java)
        assertThat((result as SyncProviderResult.Error).message).isEqualTo("Provider context is unavailable.")
    }

    @Test
    fun returns_partial_warnings_when_sync_state_is_partial() = runTest {
        val repository = FakeSyncProviderRepository(
            refreshResult = Result.success(Unit),
            provider = syncProviderFixture(status = ProviderStatus.PARTIAL)
        )
        val useCase = SyncProvider(
            providerRepository = repository,
            providerSyncStateReader = FakeSyncStateReader(
                syncState = SyncState.Partial(
                    message = "Sync completed with warnings",
                    warnings = listOf("EPG sync failed")
                )
            )
        )

        val result = useCase(SyncProviderCommand(providerId = 7L, force = true))

        assertThat(result).isInstanceOf(SyncProviderResult.Success::class.java)
        val success = result as SyncProviderResult.Success
        assertThat(success.isPartial).isTrue()
        assertThat(success.warnings).containsExactly("EPG sync failed")
        assertThat(repository.lastRefreshCall).isEqualTo(RefreshCall(providerId = 7L, force = true))
    }

    @Test
    fun returns_error_when_repository_refresh_fails() = runTest {
        val useCase = SyncProvider(
            providerRepository = FakeSyncProviderRepository(
                refreshResult = Result.error("Network timeout")
            ),
            providerSyncStateReader = FakeSyncStateReader()
        )

        val result = useCase(SyncProviderCommand(providerId = 7L))

        assertThat(result).isInstanceOf(SyncProviderResult.Error::class.java)
        assertThat((result as SyncProviderResult.Error).message).isEqualTo("Network timeout")
    }
}

private data class RefreshCall(
    val providerId: Long,
    val force: Boolean
)

private class FakeSyncStateReader(
    private val syncState: SyncState = SyncState.Success()
) : ProviderSyncStateReader {
    override fun currentSyncState(providerId: Long): SyncState = syncState
    override fun observeBackgroundIndexingActive(providerId: Long): Flow<Boolean> = flowOf(false)
}

private class FakeSyncProviderRepository(
    private val refreshResult: Result<Unit> = Result.success(Unit),
    private val provider: Provider? = syncProviderFixture()
) : ProviderRepository {
    var lastRefreshCall: RefreshCall? = null

    override fun getProviders(): Flow<List<Provider>> = flowOf(emptyList())

    override fun getActiveProvider(): Flow<Provider?> = flowOf(null)

    override suspend fun getProvider(id: Long): Provider? = provider?.copy(id = id)

    override suspend fun addProvider(provider: Provider): Result<Long> = error("Not used in test")

    override suspend fun updateProvider(provider: Provider): Result<Unit> = error("Not used in test")

    override suspend fun deleteProvider(
        id: Long,
        onProgress: ((ProviderDeleteProgress) -> Unit)?
    ): Result<com.streamvault.domain.repository.ProviderDeleteOutcome> = error("Not used in test")

    override suspend fun getAllProviderCredentials(): List<ProviderCredentials> = emptyList()

    override suspend fun updateProviderPassword(
        serverUrl: String,
        username: String,
        cleartextPassword: String
    ): Boolean = false

    override suspend fun setActiveProvider(id: Long): Result<Unit> = error("Not used in test")

    override suspend fun setupProvider(
        request: ProviderSetupRequest,
        onProgress: ((String) -> Unit)?,
        onCode: ((String) -> Unit)?
    ): Result<Provider> = error("Not used in test")

    suspend fun loginXtream(
        serverUrl: String,
        username: String,
        password: String,
        name: String,
        httpUserAgent: String,
        httpHeaders: String,
        xtreamFastSyncEnabled: Boolean,
        epgSyncMode: ProviderEpgSyncMode,
        xtreamLiveSyncMode: ProviderXtreamLiveSyncMode,
        guideSourcePolicy: GuideSourcePolicy,
        channelLogoSourcePolicy: ChannelLogoSourcePolicy,
        onProgress: ((String) -> Unit)?,
        id: Long?
    ): Result<Provider> = error("Not used in test")

    suspend fun validateM3u(
        url: String,
        name: String,
        httpUserAgent: String,
        httpHeaders: String,
        epgSyncMode: ProviderEpgSyncMode,
        m3uVodClassificationEnabled: Boolean,
        guideSourcePolicy: GuideSourcePolicy,
        channelLogoSourcePolicy: ChannelLogoSourcePolicy,
        onProgress: ((String) -> Unit)?,
        id: Long?
    ): Result<Provider> = error("Not used in test")

    suspend fun loginStalker(
        portalUrl: String,
        macAddress: String,
        name: String,
        authMode: StalkerAuthMode,
        username: String,
        password: String,
        httpUserAgent: String,
        httpHeaders: String,
        deviceProfile: String,
        timezone: String,
        locale: String,
        serialNumber: String,
        deviceId: String,
        deviceId2: String,
        signature: String,
        stalkerAdvancedOptionsJson: String,
        protocolPreference: StalkerProtocolPreference,
        transportGrant: StalkerTransportGrant?,
        saveWithoutVerification: Boolean,
        repairConnection: Boolean,
        requestedProfileId: String,
        epgSyncMode: ProviderEpgSyncMode,
        catalogMode: StalkerCatalogMode,
        guideSourcePolicy: GuideSourcePolicy,
        channelLogoSourcePolicy: ChannelLogoSourcePolicy,
        onProgress: ((String) -> Unit)?,
        id: Long?
    ): Result<Provider> = error("Not used in test")

    suspend fun loginJellyfin(
        serverUrl: String,
        username: String,
        password: String,
        name: String,
        onProgress: ((String) -> Unit)?,
        id: Long?
    ): Result<Provider> = error("Not used in test")

    suspend fun loginJellyfinQuickConnect(
        serverUrl: String,
        name: String,
        onCode: ((String) -> Unit)?,
        onProgress: ((String) -> Unit)?,
        id: Long?
    ): Result<Provider> = error("Not used in test")

    override suspend fun refreshProviderData(
        providerId: Long,
        force: Boolean,
        movieFastSyncOverride: Boolean?,
        epgSyncModeOverride: ProviderEpgSyncMode?,
        onProgress: ((String) -> Unit)?
    ): Result<Unit> {
        lastRefreshCall = RefreshCall(providerId, force)
        onProgress?.invoke("Downloading Live TV...")
        return refreshResult
    }

    override suspend fun getProgramsForLiveStream(
        providerId: Long,
        streamId: Long,
        epgChannelId: String?,
        limit: Int
    ): Result<List<Program>> = error("Not used in test")

    override suspend fun buildCatchUpUrl(providerId: Long, streamId: Long, start: Long, end: Long): String? = null
}

private fun syncProviderFixture(status: ProviderStatus = ProviderStatus.ACTIVE) = Provider(
    id = 1L,
    name = "Provider",
    type = ProviderType.XTREAM_CODES,
    serverUrl = "https://example.com",
    username = "user",
    status = status
)
