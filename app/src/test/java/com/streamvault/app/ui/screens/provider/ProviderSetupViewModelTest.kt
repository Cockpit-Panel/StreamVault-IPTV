package com.streamvault.app.ui.screens.provider

import com.google.common.truth.Truth.assertThat
import com.streamvault.app.pairing.ProviderQrPairingManager
import com.streamvault.app.pairing.ProviderQrPairingState
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.CombinedM3uProfile
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupImportResult
import com.streamvault.domain.manager.DriveAuthState
import com.streamvault.domain.manager.DriveBackupSyncManager
import com.streamvault.domain.usecase.ImportBackup
import com.streamvault.domain.usecase.ImportBackupResult
import com.streamvault.domain.usecase.ValidateAndAddProvider
import com.streamvault.domain.usecase.ValidateAndAddProviderResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderSetupViewModelTest {

    private val providerRepository: ProviderRepository = mock()
    private val combinedM3uRepository: CombinedM3uRepository = mock()
    private val validateAndAddProvider: ValidateAndAddProvider = mock()
    private val importBackup: ImportBackup = mock()
    private val driveBackupSyncManager: DriveBackupSyncManager = mock()
    private val providerQrPairingManager: ProviderQrPairingManager = mock()
    private val okHttpClient: okhttp3.OkHttpClient = mock()
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(null))
        whenever(providerRepository.getProviders()).thenReturn(flowOf(emptyList()))
        whenever(combinedM3uRepository.getActiveLiveSource()).thenReturn(flowOf(null))
        whenever(driveBackupSyncManager.authState).thenReturn(flowOf(DriveAuthState.SignedOut))
        whenever(providerQrPairingManager.state).thenReturn(MutableStateFlow(ProviderQrPairingState()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `adding m3u while combined source is active prepares attach prompt with names`() = runTest {
        val createdProvider = Provider(
            id = 7L,
            name = "Playlist 7",
            type = ProviderType.M3U,
            serverUrl = "https://example.com",
            m3uUrl = "https://example.com/list.m3u"
        )
        whenever(combinedM3uRepository.getActiveLiveSource()).thenReturn(
            flowOf(ActiveLiveSource.CombinedM3uSource(44L))
        )
        whenever(combinedM3uRepository.getProfile(44L)).thenReturn(
            CombinedM3uProfile(id = 44L, name = "Weekend Set")
        )
        whenever(validateAndAddProvider.addM3u(any(), any())).thenReturn(
            ValidateAndAddProviderResult.Success(createdProvider)
        )

        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
            okHttpClient = okHttpClient,
        )

        viewModel.addM3u("https://example.com/list.m3u", "Playlist 7", "", "")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.pendingCombinedAttachProfileId).isEqualTo(44L)
        assertThat(viewModel.uiState.value.pendingCombinedAttachProfileName).isEqualTo("Weekend Set")
        assertThat(viewModel.uiState.value.createdProviderName).isEqualTo("Playlist 7")
        assertThat(viewModel.uiState.value.loginSuccess).isFalse()
        assertThat(viewModel.uiState.value.onboardingCompletion)
            .isEqualTo(ProviderSetupViewModel.OnboardingCompletion.READY)
    }

    @Test
    fun `login xtream saved with sync warning marks onboarding as resuming instead of ready`() = runTest {
        val createdProvider = Provider(
            id = 8L,
            name = "Premium",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.com"
        )
        whenever(validateAndAddProvider.loginXtream(any(), any())).thenReturn(
            ValidateAndAddProviderResult.SavedWithWarning(
                provider = createdProvider,
                warning = "Provider login succeeded, but initial sync failed. The provider was saved and can be retried from Settings: timeout"
            )
        )

        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
            okHttpClient = okHttpClient,
        )

        viewModel.loginXtream("https://example.com", "alice", "secret", "Premium", "", "")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.loginSuccess).isFalse()
        assertThat(viewModel.uiState.value.onboardingCompletion)
            .isEqualTo(ProviderSetupViewModel.OnboardingCompletion.SAVED_RESUMING)
        assertThat(viewModel.uiState.value.createdProviderId).isEqualTo(8L)
        assertThat(viewModel.uiState.value.completionWarning).contains("initial sync failed")
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `confirm backup import completes onboarding when providers are restored`() = runTest {
        val importedProvider = Provider(
            id = 9L,
            name = "Restored",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.com"
        )
        whenever(providerRepository.getProviders()).thenReturn(flowOf(listOf(importedProvider)))
        whenever(importBackup.confirm(any())).thenReturn(
            ImportBackupResult.Success(BackupImportResult(importedSections = listOf("Providers")))
        )

        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
            okHttpClient = okHttpClient,
        )
        val field = ProviderSetupViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<ProviderSetupState>
        stateFlow.value = stateFlow.value.copy(
            pendingBackupUri = "content://backup.json",
            backupImportPlan = BackupImportPlan()
        )

        viewModel.confirmBackupImport()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.backupImportSuccess).isTrue()
        assertThat(viewModel.uiState.value.pendingBackupUri).isNull()
        assertThat(viewModel.uiState.value.isImportingBackup).isFalse()
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `attach created provider to combined keeps combined source active`() = runTest {
        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
            okHttpClient = okHttpClient,
        )

        val seededState = viewModel.uiState.value.copy(
            createdProviderId = 12L,
            pendingCombinedAttachProfileId = 99L,
            onboardingCompletion = ProviderSetupViewModel.OnboardingCompletion.READY
        )
        val field = ProviderSetupViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<ProviderSetupState>
        stateFlow.value = seededState

        viewModel.attachCreatedProviderToCombined()
        advanceUntilIdle()

        verify(combinedM3uRepository).addProvider(99L, 12L)
        verify(combinedM3uRepository).setActiveLiveSource(eq(ActiveLiveSource.CombinedM3uSource(99L)))
        assertThat(viewModel.uiState.value.loginSuccess).isTrue()
        assertThat(viewModel.uiState.value.onboardingCompletion)
            .isEqualTo(ProviderSetupViewModel.OnboardingCompletion.READY)
        assertThat(viewModel.uiState.value.pendingCombinedAttachProfileId).isNull()
    }

    @Test
    fun `skipping combined attach after saved warning keeps onboarding in resuming state`() = runTest {
        val createdProvider = Provider(
            id = 7L,
            name = "Playlist 7",
            type = ProviderType.M3U,
            serverUrl = "https://example.com",
            m3uUrl = "https://example.com/list.m3u"
        )
        whenever(combinedM3uRepository.getActiveLiveSource()).thenReturn(
            flowOf(ActiveLiveSource.CombinedM3uSource(44L))
        )
        whenever(combinedM3uRepository.getProfile(44L)).thenReturn(
            CombinedM3uProfile(id = 44L, name = "Weekend Set")
        )
        whenever(validateAndAddProvider.addM3u(any(), any())).thenReturn(
            ValidateAndAddProviderResult.SavedWithWarning(
                provider = createdProvider,
                warning = "Playlist saved, but initial sync failed. Resume has been queued."
            )
        )

        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
            okHttpClient = okHttpClient,
        )

        viewModel.addM3u("https://example.com/list.m3u", "Playlist 7", "", "")
        advanceUntilIdle()
        viewModel.skipCreatedProviderCombinedAttach()

        assertThat(viewModel.uiState.value.pendingCombinedAttachProfileId).isNull()
        assertThat(viewModel.uiState.value.loginSuccess).isFalse()
        assertThat(viewModel.uiState.value.onboardingCompletion)
            .isEqualTo(ProviderSetupViewModel.OnboardingCompletion.SAVED_RESUMING)
        assertThat(viewModel.uiState.value.completionWarning).contains("initial sync failed")
    }

    @Test
    fun `stalker source defaults epg sync mode to background when user has not customized it`() = runTest {
        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
            okHttpClient = okHttpClient,
        )

        viewModel.applySourceDefaults(ProviderSetupViewModel.SetupSourceType.STALKER)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.epgSyncMode).isEqualTo(ProviderEpgSyncMode.BACKGROUND)
        assertThat(viewModel.uiState.value.hasCustomizedEpgSyncMode).isFalse()
    }

    @Test
    fun `xtream source defaults epg sync mode to background when user has not customized it`() = runTest {
        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
            okHttpClient = okHttpClient,
        )

        viewModel.applySourceDefaults(ProviderSetupViewModel.SetupSourceType.XTREAM)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.epgSyncMode).isEqualTo(ProviderEpgSyncMode.BACKGROUND)
        assertThat(viewModel.uiState.value.hasCustomizedEpgSyncMode).isFalse()
    }

    @Test
    fun `m3u source defaults epg sync mode to background when user has not customized it`() = runTest {
        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
            okHttpClient = okHttpClient,
        )

        viewModel.applySourceDefaults(ProviderSetupViewModel.SetupSourceType.M3U)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.epgSyncMode).isEqualTo(ProviderEpgSyncMode.BACKGROUND)
        assertThat(viewModel.uiState.value.hasCustomizedEpgSyncMode).isFalse()
    }

    @Test
    fun `source defaults do not override customized epg sync mode`() = runTest {
        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
            okHttpClient = okHttpClient,
        )

        viewModel.updateEpgSyncMode(ProviderEpgSyncMode.SKIP)
        viewModel.applySourceDefaults(ProviderSetupViewModel.SetupSourceType.STALKER)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.epgSyncMode).isEqualTo(ProviderEpgSyncMode.SKIP)
        assertThat(viewModel.uiState.value.hasCustomizedEpgSyncMode).isTrue()
    }

    @Test
    fun `editing m3u provider while combined source is active does not re-prompt for combined attach`() = runTest {
        val editedProvider = Provider(
            id = 7L,
            name = "Playlist 7",
            type = ProviderType.M3U,
            serverUrl = "https://example.com",
            m3uUrl = "https://example.com/list.m3u"
        )
        whenever(combinedM3uRepository.getActiveLiveSource()).thenReturn(
            flowOf(ActiveLiveSource.CombinedM3uSource(44L))
        )
        whenever(validateAndAddProvider.addM3u(any(), any())).thenReturn(
            ValidateAndAddProviderResult.Success(editedProvider)
        )

        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
            okHttpClient = okHttpClient,
        )

        // Simulate being in edit mode for provider 7.
        val field = ProviderSetupViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<ProviderSetupState>
        stateFlow.value = stateFlow.value.copy(isEditing = true, existingProviderId = 7L)

        viewModel.addM3u("https://example.com/list.m3u", "Playlist 7", "", "")
        advanceUntilIdle()

        // Edit flows must complete directly without the combined-attach dialog.
        assertThat(viewModel.uiState.value.pendingCombinedAttachProfileId).isNull()
        assertThat(viewModel.uiState.value.loginSuccess).isTrue()
        assertThat(viewModel.uiState.value.onboardingCompletion)
            .isEqualTo(ProviderSetupViewModel.OnboardingCompletion.READY)
    }

    @Test
    fun `m3u sync failure error does not include could not validate playlist prefix`() = runTest {
        whenever(validateAndAddProvider.addM3u(any(), any())).thenReturn(
            ValidateAndAddProviderResult.Error(
                message = "Playlist saved, but initial sync failed. The provider was saved and can be retried from Settings: timeout"
            )
        )

        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
            okHttpClient = okHttpClient,
        )

        viewModel.addM3u("https://example.com/list.m3u", "Playlist", "", "")
        advanceUntilIdle()

        val error = viewModel.uiState.value.error
        assertThat(error).doesNotContain("Could not validate playlist")
        assertThat(error).contains("saved")
    }

    @Test
    fun `stalker error maps sync failure to user friendly message`() = runTest {
        whenever(validateAndAddProvider.loginStalker(any(), any())).thenReturn(
            ValidateAndAddProviderResult.Error(
                message = "Provider login succeeded, but initial sync failed. The provider was saved and can be retried from Settings: timeout"
            )
        )

        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
            okHttpClient = okHttpClient,
        )

        viewModel.loginStalker(
            portalUrl = "https://portal.example.com",
            macAddress = "00:1A:79:12:34:56",
            authMode = StalkerAuthMode.AUTO,
            username = "",
            password = "",
            name = "MAG",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )
        advanceUntilIdle()

        val error = viewModel.uiState.value.error
        assertThat(error).doesNotContain("initial sync failed. The provider was saved")
        assertThat(error).contains("sync failed")
    }
}
