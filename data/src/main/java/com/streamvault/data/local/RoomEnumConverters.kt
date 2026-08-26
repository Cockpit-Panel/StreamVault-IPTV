package com.streamvault.data.local

import androidx.room.TypeConverter
import com.streamvault.data.local.entity.ProviderConfigRevisionState
import com.streamvault.data.local.entity.ProviderWorkflowPhase
import com.streamvault.data.local.entity.ProviderWorkflowPhaseState
import com.streamvault.data.local.entity.ProviderWorkflowReason
import com.streamvault.data.local.entity.ProviderWorkflowState
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.SeriesCatalogOrigin
import com.streamvault.domain.model.ChannelLogoSourcePolicy
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import com.streamvault.domain.model.ProgramReminderDeliveryState
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.StalkerCatalogMode
import com.streamvault.domain.model.StalkerBootstrapRecipe
import com.streamvault.domain.model.StalkerCookieMode
import com.streamvault.domain.model.StalkerEndpointPreference
import com.streamvault.domain.model.StalkerMagPreset
import com.streamvault.domain.model.StalkerPlaybackBackendHint
import com.streamvault.domain.model.StalkerPortalFingerprint
import com.streamvault.domain.model.StalkerPortalProfile
import com.streamvault.domain.model.StalkerProfileVerification
import com.streamvault.domain.model.StalkerProtocolFamily
import com.streamvault.domain.model.StalkerProtocolPreference
import com.streamvault.domain.model.StalkerTransportMode
import com.streamvault.domain.model.XmltvTimezonePolicy
import java.util.logging.Logger

class RoomEnumConverters {
    private companion object {
        val logger: Logger = Logger.getLogger(RoomEnumConverters::class.java.name)
    }

    @TypeConverter
    fun fromProviderType(value: ProviderType?): String? = value?.name

    @TypeConverter
    fun toProviderType(value: String?): ProviderType? =
        enumValueOrDefault(value, ProviderType.M3U, providerTypeAliases())

    @TypeConverter
    fun fromProviderStatus(value: ProviderStatus?): String? = value?.name

    @TypeConverter
    fun toProviderStatus(value: String?): ProviderStatus? = enumValueOrDefault(value, ProviderStatus.UNKNOWN)

    @TypeConverter
    fun fromProviderConfigRevisionState(value: ProviderConfigRevisionState?): String? = value?.name

    @TypeConverter
    fun toProviderConfigRevisionState(value: String?): ProviderConfigRevisionState? =
        enumValueOrDefault(value, ProviderConfigRevisionState.FAILED)

    @TypeConverter
    fun fromProviderWorkflowState(value: ProviderWorkflowState?): String? = value?.name

    @TypeConverter
    fun toProviderWorkflowState(value: String?): ProviderWorkflowState? =
        enumValueOrDefault(value, ProviderWorkflowState.FAILED)

    @TypeConverter
    fun fromProviderWorkflowPhase(value: ProviderWorkflowPhase?): String? = value?.name

    @TypeConverter
    fun toProviderWorkflowPhase(value: String?): ProviderWorkflowPhase? =
        enumValueOrDefault(value, ProviderWorkflowPhase.PREPARE)

    @TypeConverter
    fun fromProviderWorkflowPhaseState(value: ProviderWorkflowPhaseState?): String? = value?.name

    @TypeConverter
    fun toProviderWorkflowPhaseState(value: String?): ProviderWorkflowPhaseState? =
        enumValueOrDefault(value, ProviderWorkflowPhaseState.FAILED_PERMANENT)

    @TypeConverter
    fun fromProviderWorkflowReason(value: ProviderWorkflowReason?): String? = value?.name

    @TypeConverter
    fun toProviderWorkflowReason(value: String?): ProviderWorkflowReason? =
        enumValueOrDefault(value, ProviderWorkflowReason.RECOVERY)

    @TypeConverter
    fun fromProgramReminderDeliveryState(value: ProgramReminderDeliveryState?): String? = value?.name

    @TypeConverter
    fun toProgramReminderDeliveryState(value: String?): ProgramReminderDeliveryState? =
        enumValueOrDefault(value, ProgramReminderDeliveryState.PENDING)

    @TypeConverter
    fun fromProviderEpgSyncMode(value: ProviderEpgSyncMode?): String? = value?.name

    @TypeConverter
    fun toProviderEpgSyncMode(value: String?): ProviderEpgSyncMode? =
        enumValueOrDefault(value, ProviderEpgSyncMode.SKIP, providerEpgSyncModeAliases())

    @TypeConverter
    fun fromStalkerCatalogMode(value: StalkerCatalogMode?): String? = value?.name

    @TypeConverter
    fun toStalkerCatalogMode(value: String?): StalkerCatalogMode? =
        enumValueOrDefault(value, StalkerCatalogMode.ON_DEMAND)

    @TypeConverter
    fun fromGuideSourcePolicy(value: GuideSourcePolicy?): String? = value?.name

    @TypeConverter
    fun toGuideSourcePolicy(value: String?): GuideSourcePolicy? =
        enumValueOrDefault(value, GuideSourcePolicy.AUTO, guideSourcePolicyAliases())

    @TypeConverter
    fun fromChannelLogoSourcePolicy(value: ChannelLogoSourcePolicy?): String? = value?.name

    @TypeConverter
    fun toChannelLogoSourcePolicy(value: String?): ChannelLogoSourcePolicy? =
        enumValueOrDefault(value, ChannelLogoSourcePolicy.SUPPLIER_PREFERRED, channelLogoSourcePolicyAliases())

    @TypeConverter
    fun fromProviderXtreamLiveSyncMode(value: ProviderXtreamLiveSyncMode?): String? = value?.name

    @TypeConverter
    fun toProviderXtreamLiveSyncMode(value: String?): ProviderXtreamLiveSyncMode? =
        enumValueOrDefault(value, ProviderXtreamLiveSyncMode.AUTO, providerXtreamLiveSyncModeAliases())

    @TypeConverter
    fun fromStalkerAuthMode(value: StalkerAuthMode?): String? = value?.name

    @TypeConverter
    fun toStalkerAuthMode(value: String?): StalkerAuthMode? =
        enumValueOrDefault(value, StalkerAuthMode.AUTO)

    @TypeConverter
    fun fromStalkerPortalProfile(value: StalkerPortalProfile?): String? = value?.name

    @TypeConverter
    fun toStalkerPortalProfile(value: String?): StalkerPortalProfile? =
        enumValueOrDefault(value, StalkerPortalProfile.MAG_BASIC)

    @TypeConverter
    fun fromStalkerPortalFingerprint(value: StalkerPortalFingerprint?): String? = value?.name

    @TypeConverter
    fun toStalkerPortalFingerprint(value: String?): StalkerPortalFingerprint? =
        enumValueOrDefault(value, StalkerPortalFingerprint.BASIC_MAC)

    @TypeConverter
    fun fromStalkerMagPreset(value: StalkerMagPreset?): String? = value?.name

    @TypeConverter
    fun toStalkerMagPreset(value: String?): StalkerMagPreset? =
        enumValueOrDefault(value, StalkerMagPreset.GENERIC_SAFE)

    @TypeConverter
    fun fromStalkerProtocolPreference(value: StalkerProtocolPreference?): String? = value?.name

    @TypeConverter
    fun toStalkerProtocolPreference(value: String?): StalkerProtocolPreference? =
        enumValueOrDefault(value, StalkerProtocolPreference.AUTO)

    @TypeConverter
    fun fromStalkerTransportMode(value: StalkerTransportMode?): String? = value?.name

    @TypeConverter
    fun toStalkerTransportMode(value: String?): StalkerTransportMode? =
        enumValueOrDefault(value, StalkerTransportMode.AUTO_STRICT)

    @TypeConverter
    fun fromStalkerProfileVerification(value: StalkerProfileVerification?): String? = value?.name

    @TypeConverter
    fun toStalkerProfileVerification(value: String?): StalkerProfileVerification? =
        enumValueOrDefault(value, StalkerProfileVerification.UNVERIFIED)

    @TypeConverter
    fun fromStalkerProtocolFamily(value: StalkerProtocolFamily?): String? = value?.name

    @TypeConverter
    fun toStalkerProtocolFamily(value: String?): StalkerProtocolFamily? =
        enumValueOrDefault(value, StalkerProtocolFamily.CLASSIC_MAG)

    @TypeConverter
    fun fromStalkerBootstrapRecipe(value: StalkerBootstrapRecipe?): String? = value?.name

    @TypeConverter
    fun toStalkerBootstrapRecipe(value: String?): StalkerBootstrapRecipe? =
        enumValueOrDefault(value, StalkerBootstrapRecipe.GENERIC_SAFE)

    @TypeConverter
    fun fromStalkerEndpointPreference(value: StalkerEndpointPreference?): String? = value?.name

    @TypeConverter
    fun toStalkerEndpointPreference(value: String?): StalkerEndpointPreference? =
        enumValueOrDefault(value, StalkerEndpointPreference.AUTO)

    @TypeConverter
    fun fromStalkerCookieMode(value: StalkerCookieMode?): String? = value?.name

    @TypeConverter
    fun toStalkerCookieMode(value: String?): StalkerCookieMode? =
        enumValueOrDefault(value, StalkerCookieMode.NONE)

    @TypeConverter
    fun fromStalkerPlaybackBackendHint(value: StalkerPlaybackBackendHint?): String? = value?.name

    @TypeConverter
    fun toStalkerPlaybackBackendHint(value: String?): StalkerPlaybackBackendHint? =
        enumValueOrDefault(value, StalkerPlaybackBackendHint.AUTO)

    @TypeConverter
    fun fromContentType(value: ContentType?): String? = value?.name

    @TypeConverter
    fun toContentType(value: String?): ContentType? =
        enumValueOrDefault(value, ContentType.LIVE, contentTypeAliases())

    @TypeConverter
    fun fromCatalogLayout(value: CatalogLayout?): String? = value?.name

    @TypeConverter
    fun toCatalogLayout(value: String?): CatalogLayout? =
        enumValueOrDefault(value, CatalogLayout.SPLIT)

    @TypeConverter
    fun fromSeriesCatalogOrigin(value: SeriesCatalogOrigin?): String? = value?.name

    @TypeConverter
    fun toSeriesCatalogOrigin(value: String?): SeriesCatalogOrigin? =
        enumValueOrDefault(value, SeriesCatalogOrigin.NATIVE)

    @TypeConverter
    fun fromXmltvTimezonePolicy(value: XmltvTimezonePolicy?): String? = value?.name

    @TypeConverter
    fun toXmltvTimezonePolicy(value: String?): XmltvTimezonePolicy? =
        enumValueOrDefault(value, XmltvTimezonePolicy.REQUIRE_OFFSET)

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        value: String?,
        defaultValue: T,
        aliases: Map<String, T> = emptyMap()
    ): T? {
        if (value == null) return null
        val normalizedValue = value.trim()
        aliases[normalizedValue.uppercase()]?.let { return it }
        return enumValues<T>().firstOrNull { candidate ->
            candidate.name.equals(normalizedValue, ignoreCase = true)
        } ?: defaultValue.also {
            logger.warning(
                "Unknown ${T::class.java.simpleName} value '$value'; defaulting to ${defaultValue.name}"
            )
        }
    }

    private fun providerTypeAliases(): Map<String, ProviderType> = mapOf(
        "XTREAM" to ProviderType.XTREAM_CODES,
        "XTREAM_CODES_API" to ProviderType.XTREAM_CODES,
        "STALKER" to ProviderType.STALKER_PORTAL,
        "STB" to ProviderType.STALKER_PORTAL,
        "PLAYLIST" to ProviderType.M3U
    )

    private fun providerEpgSyncModeAliases(): Map<String, ProviderEpgSyncMode> = mapOf(
        "DISABLED" to ProviderEpgSyncMode.SKIP,
        "OFF" to ProviderEpgSyncMode.SKIP,
        "FOREGROUND" to ProviderEpgSyncMode.UPFRONT
    )

    private fun providerXtreamLiveSyncModeAliases(): Map<String, ProviderXtreamLiveSyncMode> = mapOf(
        "SEGMENTED" to ProviderXtreamLiveSyncMode.CATEGORY_BY_CATEGORY,
        "CATEGORY" to ProviderXtreamLiveSyncMode.CATEGORY_BY_CATEGORY,
        "CATEGORIES" to ProviderXtreamLiveSyncMode.CATEGORY_BY_CATEGORY,
        "FULL" to ProviderXtreamLiveSyncMode.STREAM_ALL,
        "FULL_CATALOG" to ProviderXtreamLiveSyncMode.STREAM_ALL,
        "STREAM" to ProviderXtreamLiveSyncMode.STREAM_ALL
    )

    private fun guideSourcePolicyAliases(): Map<String, GuideSourcePolicy> = mapOf(
        "EXTERNAL" to GuideSourcePolicy.EXTERNAL_ONLY,
        "PROVIDER" to GuideSourcePolicy.PROVIDER_ONLY,
        "OFF" to GuideSourcePolicy.DISABLED,
        "NONE" to GuideSourcePolicy.DISABLED
    )

    private fun channelLogoSourcePolicyAliases(): Map<String, ChannelLogoSourcePolicy> = mapOf(
        "SUPPLIER" to ChannelLogoSourcePolicy.SUPPLIER_ONLY,
        "EPG" to ChannelLogoSourcePolicy.EPG_ONLY,
        "EXTERNAL" to ChannelLogoSourcePolicy.EPG_ONLY
    )

    private fun contentTypeAliases(): Map<String, ContentType> = mapOf(
        "EPISODE" to ContentType.SERIES_EPISODE,
        "SHOW" to ContentType.SERIES,
        "CHANNEL" to ContentType.LIVE
    )
}
