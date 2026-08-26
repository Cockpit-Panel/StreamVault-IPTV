package com.streamvault.data.remote.xtream

import com.streamvault.data.provider.ProviderCapabilityResolver
import com.streamvault.data.remote.stalker.StalkerPlaybackResolutionException
import com.streamvault.data.remote.stalker.StalkerUrlFactory
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Result
import com.streamvault.domain.provider.CapabilityResolution
import com.streamvault.domain.provider.PlaybackRequest
import com.streamvault.domain.provider.ProviderContentReference

/** Resolves a typed provider playback capability without constructing provider sessions itself. */
class PlaybackResolverRegistry(
    private val providerCapabilities: ProviderCapabilityResolver
) {
    suspend fun resolve(
        sourceUrl: String,
        providerId: Long,
        streamId: Long?,
        contentType: ContentType,
        containerExtension: String?,
        preferStableUrl: Boolean,
        isStalkerSource: Boolean
    ): ResolvedStreamUrl? {
        val capabilitySet = when (val resolution = providerCapabilities.resolve(providerId)) {
            is CapabilityResolution.Available -> resolution.capability
            is CapabilityResolution.ConfigurationError -> return unavailable(sourceUrl, resolution.reason, isStalkerSource)
            is CapabilityResolution.Restricted -> return unavailable(sourceUrl, resolution.reason, isStalkerSource)
            is CapabilityResolution.Unsupported -> return unavailable(sourceUrl, resolution.reason, isStalkerSource)
        }
        val playback = when (val resolution = capabilitySet.playback()) {
            is CapabilityResolution.Available -> resolution.capability
            is CapabilityResolution.ConfigurationError -> return unavailable(sourceUrl, resolution.reason, isStalkerSource)
            is CapabilityResolution.Restricted -> return unavailable(sourceUrl, resolution.reason, isStalkerSource)
            is CapabilityResolution.Unsupported -> return unavailable(sourceUrl, resolution.reason, isStalkerSource)
        }
        return when (val result = playback.resolve(
            PlaybackRequest(
                sourceUrl = sourceUrl,
                content = ProviderContentReference(providerId = providerId, streamId = streamId),
                contentType = contentType,
                containerExtension = containerExtension,
                preferStableUrl = preferStableUrl
            )
        )) {
            is Result.Success -> result.data.let { resolved ->
                ResolvedStreamUrl(
                    url = resolved.url,
                    expirationTime = resolved.expirationTime ?: extractStreamExpirationTime(resolved.url),
                    containerExtension = resolved.containerExtension,
                    headers = resolved.headers,
                    userAgent = resolved.userAgent,
                    playbackTransportPolicy = resolved.playbackTransportPolicy,
                    allowInvalidSsl = resolved.allowInvalidSsl,
                    proxyHost = resolved.proxyHost,
                    proxyPort = resolved.proxyPort,
                    observations = resolved.observations
                )
            }
            is Result.Error -> {
                if (isStalkerSource) {
                    throw StalkerPlaybackResolutionException(result.message, result.exception)
                }
                null
            }
            is Result.Loading -> null
        }
    }

    private fun unavailable(url: String, reason: String, isStalkerSource: Boolean): ResolvedStreamUrl? {
        if (isStalkerSource) throw StalkerPlaybackResolutionException(reason)
        return url.takeIf {
            it.isNotBlank() &&
                !XtreamUrlFactory.isInternalStreamUrl(it) &&
                !StalkerUrlFactory.isInternalStreamUrl(it)
        }
            ?.let { passthrough ->
                ResolvedStreamUrl(
                    url = passthrough,
                    expirationTime = extractStreamExpirationTime(passthrough)
                )
            }
    }
}
