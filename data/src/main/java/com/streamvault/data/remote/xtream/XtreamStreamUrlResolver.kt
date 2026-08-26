package com.streamvault.data.remote.xtream

import com.streamvault.data.remote.stalker.StalkerStreamKind
import com.streamvault.data.remote.stalker.StalkerUrlFactory
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackTransportPolicy
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

data class ResolvedStreamUrl(
    val url: String,
    val expirationTime: Long? = null,
    val containerExtension: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val playbackTransportPolicy: PlaybackTransportPolicy? = null,
    val allowInvalidSsl: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: Int? = null,
    val observations: List<com.streamvault.domain.provider.PlaybackObservation> = emptyList()
)

/** Provider-neutral playback entry point. Provider selection is owned by the capability registry. */
@Singleton
class ProviderPlaybackResolver @Inject constructor(
    providerCapabilities: com.streamvault.data.provider.ProviderCapabilityResolver,
    private val observationCoordinator: PlaybackObservationSink? = null
) {
    private val playbackCoordinator = PlaybackResolverRegistry(providerCapabilities)

    fun isInternalStreamUrl(url: String?): Boolean =
        XtreamUrlFactory.isInternalStreamUrl(url) || StalkerUrlFactory.isInternalStreamUrl(url)

    suspend fun resolve(
        url: String,
        fallbackProviderId: Long? = null,
        fallbackStreamId: Long? = null,
        fallbackContentType: ContentType? = null,
        fallbackContainerExtension: String? = null,
        preferStableUrl: Boolean = false
    ): String? = resolveWithMetadata(
        url = url,
        fallbackProviderId = fallbackProviderId,
        fallbackStreamId = fallbackStreamId,
        fallbackContentType = fallbackContentType,
        fallbackContainerExtension = fallbackContainerExtension,
        preferStableUrl = preferStableUrl
    )?.url

    suspend fun resolveWithMetadata(
        url: String,
        fallbackProviderId: Long? = null,
        fallbackStreamId: Long? = null,
        fallbackContentType: ContentType? = null,
        fallbackContainerExtension: String? = null,
        preferStableUrl: Boolean = false
    ): ResolvedStreamUrl? {
        val xtreamToken = XtreamUrlFactory.parseInternalStreamUrl(url)
        val stalkerToken = StalkerUrlFactory.parseInternalStreamUrl(url)
        val providerId = xtreamToken?.providerId
            ?: stalkerToken?.providerId
            ?: fallbackProviderId?.takeIf { it > 0L }
            ?: return url.takeIf(String::isNotBlank)?.let {
                ResolvedStreamUrl(
                    url = it,
                    expirationTime = extractStreamExpirationTime(it),
                    containerExtension = fallbackContainerExtension
                )
            }
        val contentType = fallbackContentType
            ?: xtreamToken?.kind?.toContentType()
            ?: stalkerToken?.kind?.toContentType()
            ?: ContentType.LIVE
        val streamId = xtreamToken?.streamId
            ?: stalkerToken?.itemId
            ?: fallbackStreamId?.takeIf { it > 0L }

        if (providerId <= 0L) {
            return url.takeIf(String::isNotBlank)?.let {
                ResolvedStreamUrl(
                    url = it,
                    expirationTime = extractStreamExpirationTime(it),
                    containerExtension = fallbackContainerExtension
                )
            }
        }

        return playbackCoordinator.resolve(
            sourceUrl = url,
            providerId = providerId,
            streamId = streamId,
            contentType = contentType,
            containerExtension = xtreamToken?.containerExtension
                ?: stalkerToken?.containerExtension
                ?: fallbackContainerExtension,
            preferStableUrl = preferStableUrl,
            isStalkerSource = stalkerToken != null
        )
    }

    /** Explicit resolve-and-commit boundary for callers that want learned playback observations. */
    suspend fun resolveAndCommitMetadata(
        url: String,
        fallbackProviderId: Long? = null,
        fallbackStreamId: Long? = null,
        fallbackContentType: ContentType? = null,
        fallbackContainerExtension: String? = null,
        preferStableUrl: Boolean = false
    ): ResolvedStreamUrl? = resolveWithMetadata(
        url,
        fallbackProviderId,
        fallbackStreamId,
        fallbackContentType,
        fallbackContainerExtension,
        preferStableUrl
    ).also { resolved ->
        resolved?.let {
            checkNotNull(observationCoordinator) {
                "Playback observation coordinator is required for resolveAndCommitMetadata"
            }.persist(it.observations)
        }
    }
}

/** Compatibility alias for callers migrating to the provider-neutral resolver name. */
typealias XtreamStreamUrlResolver = ProviderPlaybackResolver

private fun XtreamStreamKind.toContentType(): ContentType = when (this) {
    XtreamStreamKind.LIVE -> ContentType.LIVE
    XtreamStreamKind.MOVIE -> ContentType.MOVIE
    XtreamStreamKind.SERIES -> ContentType.SERIES_EPISODE
}

private fun StalkerStreamKind.toContentType(): ContentType = when (this) {
    StalkerStreamKind.LIVE,
    StalkerStreamKind.ARCHIVE -> ContentType.LIVE
    StalkerStreamKind.MOVIE -> ContentType.MOVIE
    StalkerStreamKind.EPISODE -> ContentType.SERIES_EPISODE
}

internal fun extractStreamExpirationTime(url: String): Long? {
    val query = runCatching { URI(url).rawQuery }.getOrNull()
        ?: url.substringAfter('?', missingDelimiterValue = "").takeIf { it.isNotBlank() }
        ?: return null
    val expirationKeys = setOf(
        "expire", "expires", "expiry", "expiration", "expires_at", "exp",
        "token_exp", "token_expires", "token_expiry"
    )
    return query.split('&')
        .asSequence()
        .mapNotNull { part ->
            val key = part.substringBefore('=', missingDelimiterValue = "")
                .lowercase()
                .takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            if (key !in expirationKeys) return@mapNotNull null
            parseXtreamExpirationDate(
                XtreamUrlCodec.decode(part.substringAfter('=', missingDelimiterValue = ""))
            )
        }
        .firstOrNull()
}
