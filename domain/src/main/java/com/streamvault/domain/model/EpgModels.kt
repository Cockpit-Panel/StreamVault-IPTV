package com.streamvault.domain.model

/**
 * Represents an external EPG source (e.g. an XMLTV URL).
 */
enum class XmltvTimezonePolicy {
    /** XMLTV timestamps must contain an explicit offset or UTC designator. */
    REQUIRE_OFFSET,

    /** Offset-less XMLTV timestamps are interpreted as UTC. */
    UTC,

    /** Offset-less XMLTV timestamps are interpreted in [EpgSource.timezoneId]. */
    EXPLICIT_ZONE
}

data class EpgSource(
    val id: Long = 0,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val lastRefreshAt: Long = 0,
    val lastSuccessAt: Long = 0,
    val lastError: String? = null,
    val priority: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val etag: String? = null,
    val lastModifiedHeader: String? = null,
    val timezonePolicy: XmltvTimezonePolicy = XmltvTimezonePolicy.REQUIRE_OFFSET,
    val timezoneId: String? = null
)

/**
 * Represents the assignment of an external EPG source to a provider,
 * with a priority for conflict resolution ordering.
 */
data class ProviderEpgSourceAssignment(
    val id: Long = 0,
    val providerId: Long,
    val epgSourceId: Long,
    val priority: Int = 0,
    val enabled: Boolean = true,
    val epgSourceName: String = "",
    val epgSourceUrl: String = ""
)

/**
 * Represents a channel entry parsed from an EPG source.
 */
data class EpgChannelInfo(
    val id: Long = 0,
    val epgSourceId: Long,
    val xmltvChannelId: String,
    val displayName: String,
    val normalizedName: String = "",
    val iconUrl: String? = null
)

/**
 * How an EPG source was matched to a channel.
 */
enum class EpgMatchType {
    /** Exact tvg-id / xmltv channel id match */
    EXACT_ID,
    /** Normalized display name match */
    NORMALIZED_NAME,
    /** User manually assigned */
    MANUAL,
    /** Provider-native EPG (existing programs table) */
    PROVIDER_NATIVE
}

/**
 * Which type of source supplies the EPG data.
 */
enum class EpgSourceType {
    /** Data comes from the provider's built-in EPG (programs table) */
    PROVIDER,
    /** Data comes from an external XMLTV source (epg_programmes table) */
    EXTERNAL,
    /** No EPG data available */
    NONE
}

/**
 * The resolved EPG mapping for a single app channel.
 * This is precomputed by the resolution engine and persisted.
 */
data class ChannelEpgMapping(
    val id: Long = 0,
    val providerChannelId: Long,
    val providerId: Long,
    val sourceType: EpgSourceType = EpgSourceType.NONE,
    val epgSourceId: Long? = null,
    val xmltvChannelId: String? = null,
    val matchType: EpgMatchType? = null,
    val confidence: Float = 0f,
    val matchedAt: Long = 0L,
    val failedAttempts: Int = 0,
    val source: String? = null,
    val isManualOverride: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

data class EpgOverrideCandidate(
    val epgSourceId: Long,
    val epgSourceName: String,
    val xmltvChannelId: String,
    val displayName: String,
    val iconUrl: String? = null
)

/**
 * Summary statistics from an EPG resolution pass.
 */
data class EpgResolutionSummary(
    val totalChannels: Int = 0,
    val exactIdMatches: Int = 0,
    val normalizedNameMatches: Int = 0,
    val providerNativeMatches: Int = 0,
    val manualMatches: Int = 0,
    val unresolvedChannels: Int = 0,
    val lowConfidenceChannels: Int = 0,
    val rematchCandidateChannels: Int = 0
)
