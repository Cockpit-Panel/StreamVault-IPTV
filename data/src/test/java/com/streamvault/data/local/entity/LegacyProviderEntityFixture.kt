@file:Suppress("FunctionName", "LongParameterList", "UNUSED_PARAMETER")

package com.streamvault.data.local.entity

import com.streamvault.domain.model.*

/**
 * Source-compatible constructor for pre-schema-74 DAO fixtures.
 *
 * Configuration arguments are intentionally discarded: tests exercising configuration must seed
 * provider_configs and use ProviderSnapshotRepository. This helper only models the stable row.
 */
fun ProviderEntity(
    id: Long = 0,
    name: String,
    type: ProviderType,
    serverUrl: String,
    username: String = "",
    password: String = "",
    m3uUrl: String = "",
    epgUrl: String = "",
    httpUserAgent: String = "",
    httpHeaders: String = "",
    stalkerMacAddress: String = "",
    stalkerDeviceProfile: String = "",
    stalkerDeviceTimezone: String = "",
    stalkerDeviceLocale: String = "",
    stalkerSerialNumber: String = "",
    stalkerDeviceId: String = "",
    stalkerDeviceId2: String = "",
    stalkerSignature: String = "",
    stalkerAdvancedOptionsJson: String = "",
    stalkerAuthMode: StalkerAuthMode = StalkerAuthMode.AUTO,
    stalkerPortalProfile: StalkerPortalProfile = StalkerPortalProfile.MAG_BASIC,
    stalkerPortalFingerprint: StalkerPortalFingerprint = StalkerPortalFingerprint.BASIC_MAC,
    stalkerMagPreset: StalkerMagPreset = StalkerMagPreset.GENERIC_SAFE,
    stalkerProtocolPreference: StalkerProtocolPreference = StalkerProtocolPreference.AUTO,
    stalkerTransportMode: StalkerTransportMode = StalkerTransportMode.AUTO_STRICT,
    stalkerTransportOrigin: String = "",
    stalkerTlsSpkiSha256: String = "",
    stalkerTransportConsentAt: Long = 0L,
    stalkerConfigurationGeneration: Long = 0L,
    stalkerDiscoverySummary: String = "",
    stalkerCapabilitiesJson: String = "",
    stalkerRequestedProfileId: String = StalkerCompatibilityProfileIds.AUTO,
    stalkerLearnedProfileId: String = "",
    stalkerProfileRevision: Int = 0,
    stalkerProfileVerification: StalkerProfileVerification = StalkerProfileVerification.UNVERIFIED,
    stalkerProtocolFamily: StalkerProtocolFamily = StalkerProtocolFamily.CLASSIC_MAG,
    stalkerLastBootstrapRecipe: StalkerBootstrapRecipe = StalkerBootstrapRecipe.GENERIC_SAFE,
    stalkerEndpointPreference: StalkerEndpointPreference = StalkerEndpointPreference.AUTO,
    stalkerCookieMode: StalkerCookieMode = StalkerCookieMode.NONE,
    stalkerPlaybackBackendHint: StalkerPlaybackBackendHint = StalkerPlaybackBackendHint.AUTO,
    stalkerLastPlaybackMode: String? = null,
    stalkerCredentialsRequired: Boolean = false,
    stalkerMacRequired: Boolean = true,
    stalkerUsesTemporaryLinks: Boolean = false,
    stalkerModuleRestricted: Boolean = false,
    stalkerStrictFingerprintRequired: Boolean = false,
    stalkerRecipeFallbackUsed: Boolean = false,
    stalkerRecipeRediscoveryAttempts: Int = 0,
    isActive: Boolean = true,
    maxConnections: Int = 1,
    expirationDate: Long? = null,
    apiVersion: String? = null,
    allowedOutputFormatsJson: String = "[]",
    epgSyncMode: ProviderEpgSyncMode = ProviderEpgSyncMode.UPFRONT,
    stalkerCatalogMode: StalkerCatalogMode = StalkerCatalogMode.ON_DEMAND,
    guideSourcePolicy: GuideSourcePolicy = GuideSourcePolicy.AUTO,
    channelLogoSourcePolicy: ChannelLogoSourcePolicy = ChannelLogoSourcePolicy.SUPPLIER_PREFERRED,
    xtreamFastSyncEnabled: Boolean = false,
    xtreamLiveSyncMode: ProviderXtreamLiveSyncMode = ProviderXtreamLiveSyncMode.AUTO,
    m3uVodClassificationEnabled: Boolean = false,
    catalogLayout: CatalogLayout = CatalogLayout.SPLIT,
    catalogLayoutDetectionVersion: Int = 0,
    status: ProviderStatus = ProviderStatus.UNKNOWN,
    lastSyncedAt: Long = 0,
    createdAt: Long = System.currentTimeMillis()
): ProviderEntity = ProviderEntity(id, name, type, isActive, status, lastSyncedAt, createdAt)
