package com.streamvault.app.ui.screens.settings

import android.app.Application
import com.streamvault.app.R
import com.streamvault.app.ui.model.LiveTvChannelMode
import com.streamvault.app.ui.model.LiveTvQuickFilterVisibilityMode
import com.streamvault.app.ui.model.VodViewMode
import com.streamvault.domain.model.AppTimeFormat
import com.streamvault.domain.model.AppLandingDestination
import com.streamvault.domain.model.AudioOutputPreference
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ExternalPlaybackMode
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.GroupedChannelLabelMode
import com.streamvault.domain.model.LiveChannelGroupingMode
import com.streamvault.domain.model.LiveVariantPreferenceMode
import com.streamvault.domain.model.VodHttpProtocolMode
import com.streamvault.domain.model.PlayerSurfaceMode
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.RemoteShortcutPreferences

enum class ProviderWarningAction {
    EPG,
    MOVIES,
    SERIES
}

enum class ProviderSyncSelection {
    SYNC_NOW,
    REBUILD_INDEX,
    TV,
    MOVIES,
    SERIES,
    EPG
}

internal data class SettingsPreferenceSnapshot(
    val providers: List<Provider>,
    val activeProviderId: Long?,
    val parentalControlLevel: Int,
    val hasParentalPin: Boolean,
    val appLanguage: String,
    val appLandingDestination: AppLandingDestination,
    val appTimeFormat: AppTimeFormat,
    val preferredAudioLanguage: String,
    val playerMediaSessionEnabled: Boolean,
    val playerFastRetryOnTransientFailures: Boolean,
    val playerDecoderMode: DecoderMode,
    val playerAudioOutputPreference: AudioOutputPreference,
    val playerCompatibilityMemoryEnabled: Boolean,
    val playerSurfaceMode: PlayerSurfaceMode,
    val playerVodHttpProtocolMode: VodHttpProtocolMode,
    val playerPlaybackSpeed: Float,
    val playerExternalPlaybackMode: ExternalPlaybackMode,
    val playerAudioVideoSyncEnabled: Boolean,
    val playerAudioVideoOffsetMs: Int,
    val centerTwoSlotMultiviewLayout: Boolean,
    val multiViewRespectProviderConnectionLimit: Boolean,
    val playerControlsTimeoutSeconds: Int,
    val playerLiveOverlayTimeoutSeconds: Int,
    val playerNoticeTimeoutSeconds: Int,
    val playerDiagnosticsTimeoutSeconds: Int,
    val subtitleTextScale: Float,
    val subtitleTextColor: Int,
    val subtitleBackgroundColor: Int,
    val playerLiveTranslationEnabled: Boolean,
    val playerLiveTranslationEndpoint: String,
    val wifiMaxVideoHeight: Int?,
    val ethernetMaxVideoHeight: Int?,
    val playerTimeshiftEnabled: Boolean,
    val playerTimeshiftDepthMinutes: Int,
    val defaultStopPlaybackTimerMinutes: Int,
    val defaultIdleStandbyTimerMinutes: Int,
    val lastSpeedTestMegabits: Double?,
    val lastSpeedTestTimestamp: Long?,
    val lastSpeedTestTransport: String?,
    val lastSpeedTestRecommendedHeight: Int?,
    val lastSpeedTestEstimated: Boolean,
    val isIncognitoMode: Boolean,
    val useXtreamTextClassification: Boolean,
    val xtreamBase64TextCompatibility: Boolean,
    val liveTvChannelMode: LiveTvChannelMode,
    val showLiveSourceSwitcher: Boolean,
    val showAllChannelsCategory: Boolean,
    val showRecentChannelsCategory: Boolean,
    val remoteShortcutPreferences: RemoteShortcutPreferences,
    val liveTvCategoryFilters: List<String>,
    val liveTvQuickFilterVisibilityMode: LiveTvQuickFilterVisibilityMode,
    val liveChannelNumberingMode: ChannelNumberingMode,
    val liveChannelGroupingMode: LiveChannelGroupingMode,
    val groupedChannelLabelMode: GroupedChannelLabelMode,
    val liveVariantPreferenceMode: LiveVariantPreferenceMode,
    val vodViewMode: VodViewMode,
    val vodInfiniteScroll: Boolean,
    val guideDefaultCategoryId: Long,
    val guideDefaultCategoryOptions: List<Category>,
    val preventStandbyDuringPlayback: Boolean,
    val zapAutoRevert: Boolean,
    val autoPlayNextEpisode: Boolean,
    val autoCheckAppUpdates: Boolean,
    val autoDownloadAppUpdates: Boolean,
    val lastAppUpdateCheckAt: Long?,
    val cachedAppUpdateVersionName: String?,
    val cachedAppUpdateVersionCode: Int?,
    val cachedAppUpdateReleaseUrl: String?,
    val cachedAppUpdateDownloadUrl: String?,
    val cachedAppUpdateReleaseNotes: String,
    val cachedAppUpdatePublishedAt: String?
)

internal fun ProviderSyncSelection.label(application: Application): String = when (this) {
    ProviderSyncSelection.SYNC_NOW -> application.getString(R.string.settings_sync_option_sync_now)
    ProviderSyncSelection.REBUILD_INDEX -> application.getString(R.string.settings_sync_option_rebuild_index)
    ProviderSyncSelection.TV -> application.getString(R.string.settings_sync_option_tv)
    ProviderSyncSelection.MOVIES -> application.getString(R.string.settings_sync_option_movies)
    ProviderSyncSelection.SERIES -> application.getString(R.string.settings_sync_option_series)
    ProviderSyncSelection.EPG -> application.getString(R.string.settings_sync_option_epg)
}
