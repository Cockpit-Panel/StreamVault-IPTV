package com.streamvault.data.remote.xtream

import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.http.toGenericRequestProfile
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.data.remote.stalker.StalkerApiService
import com.streamvault.data.remote.stalker.StalkerPlaybackMode
import com.streamvault.data.remote.stalker.StalkerPlaybackResolutionException
import com.streamvault.data.remote.stalker.StalkerStreamKind
import com.streamvault.data.remote.stalker.StalkerTrafficCoordinator
import com.streamvault.data.remote.stalker.StalkerUrlFactory
import com.streamvault.data.remote.stalker.buildStalkerPlaybackDescriptor
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.data.util.UrlSecurityPolicy
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LiveStreamFormatMode
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.StalkerBootstrapRecipe
import com.streamvault.domain.model.StalkerCookieMode
import com.streamvault.domain.model.StalkerEndpointPreference
import com.streamvault.domain.model.StalkerMagPreset
import com.streamvault.domain.model.StalkerPlaybackBackendHint
import com.streamvault.domain.model.StalkerPortalFingerprint
import com.streamvault.domain.model.ProviderType
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

data class ResolvedStreamUrl(
    val url: String,
    val expirationTime: Long? = null,
    val containerExtension: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String? = null
)

@Singleton
class XtreamStreamUrlResolver @Inject constructor(
    private val providerDao: ProviderDao,
    private val credentialCrypto: CredentialCrypto,
    private val stalkerApiService: StalkerApiService,
    private val preferencesRepository: PreferencesRepository
) {
    private data class CachedStalkerProvider(
        val serverUrl: String,
        val macAddress: String,
        val authMode: StalkerAuthMode,
        val username: String,
        val password: String,
        val portalFingerprint: StalkerPortalFingerprint,
        val magPreset: StalkerMagPreset,
        val bootstrapRecipe: StalkerBootstrapRecipe,
        val endpointPreference: StalkerEndpointPreference,
        val cookieMode: StalkerCookieMode,
        val playbackBackendHint: StalkerPlaybackBackendHint,
        val deviceProfile: String,
        val timezone: String,
        val locale: String,
        val serialNumber: String,
        val deviceId: String,
        val deviceId2: String,
        val signature: String,
        val provider: StalkerProvider
    )

    private val stalkerProviders = ConcurrentHashMap<Long, CachedStalkerProvider>()

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

    /**
     * @param preferStableUrl when true, skip the tokenized direct-source URL and return
     *   the credential-based portal URL instead. Use this for long-lived sessions (e.g.
     *   Chromecast) where the token may expire before the session ends.
     */
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
        val providerId = xtreamToken?.providerId ?: stalkerToken?.providerId ?: fallbackProviderId?.takeIf { it > 0 }
        val provider = providerId?.let { id -> providerDao.getById(id) }

        if (url.isNotBlank() && !XtreamUrlFactory.isInternalStreamUrl(url) && !StalkerUrlFactory.isInternalStreamUrl(url)) {
            if (provider?.type == ProviderType.XTREAM_CODES) {
                resolveDirectXtreamUrl(
                    provider = provider,
                    url = url,
                    fallbackStreamId = fallbackStreamId,
                    fallbackContentType = fallbackContentType,
                    fallbackContainerExtension = fallbackContainerExtension
                )?.let { return it }
            }
            if (provider?.type == ProviderType.STALKER_PORTAL) {
                resolveDirectStalkerUrl(
                    provider = provider,
                    url = url,
                    fallbackStreamId = fallbackStreamId,
                    fallbackContentType = fallbackContentType,
                    fallbackContainerExtension = fallbackContainerExtension
                )?.let { return it }
            }
            return provider.applyPlaybackRequestProfile(
                ResolvedStreamUrl(
                    url = url,
                    expirationTime = extractStreamExpirationTime(url)
                )
            )
        }

        val resolvedProviderId = providerId ?: return null
        val resolvedProvider = provider ?: return null
        return when (resolvedProvider.type) {
            ProviderType.XTREAM_CODES -> {
                val kind = xtreamToken?.kind ?: fallbackContentType?.let(XtreamUrlFactory::kindForContentType) ?: return null
                val streamId = xtreamToken?.streamId ?: fallbackStreamId?.takeIf { it > 0 } ?: return null
                val ext = xtreamToken?.containerExtension ?: fallbackContainerExtension
                val resolvedExt = resolveLiveContainerExtension(kind, ext)
                val directSource = xtreamToken?.directSource
                    ?.takeIf { kind != XtreamStreamKind.LIVE }
                    ?.takeIf(UrlSecurityPolicy::isAllowedStreamEntryUrl)
                val decryptedPassword = credentialCrypto.decryptIfNeeded(resolvedProvider.password)

                val fallbackResolvedUrl = XtreamUrlFactory.buildPlaybackUrl(
                    serverUrl = resolvedProvider.serverUrl,
                    username = resolvedProvider.username,
                    password = decryptedPassword,
                    kind = kind,
                    streamId = streamId,
                    containerExtension = resolvedExt
                )
                val resolvedUrl = if (preferStableUrl) fallbackResolvedUrl else (directSource ?: fallbackResolvedUrl)

                resolvedProvider.applyPlaybackRequestProfile(
                    ResolvedStreamUrl(
                        url = resolvedUrl,
                        expirationTime = extractStreamExpirationTime(resolvedUrl),
                        containerExtension = resolvedExt
                    )
                )
            }
            ProviderType.STALKER_PORTAL -> {
                val token = stalkerToken ?: return url.takeIf { it.isNotBlank() }?.let { passthroughUrl ->
                    ResolvedStreamUrl(
                        url = passthroughUrl,
                        expirationTime = extractStreamExpirationTime(passthroughUrl)
                    )
                }
                val playbackInfo = when (
                    val resolvedResult = getOrCreateStalkerProvider(resolvedProvider)
                        .resolvePlaybackInfo(
                            kind = token.kind,
                            descriptor = token.playbackDescriptor,
                            seriesNumber = token.seriesNumber,
                            archiveStartSeconds = token.archiveStartSeconds,
                            archiveEndSeconds = token.archiveEndSeconds
                        )
                ) {
                    is com.streamvault.domain.model.Result.Success -> resolvedResult.data
                    is com.streamvault.domain.model.Result.Error -> throw StalkerPlaybackResolutionException(
                        resolvedResult.message,
                        resolvedResult.exception
                    )
                    else -> return null
                }
                persistStalkerPlaybackLearning(resolvedProvider, playbackInfo)
                ResolvedStreamUrl(
                    url = playbackInfo.url,
                    expirationTime = extractStreamExpirationTime(playbackInfo.url),
                    containerExtension = token.containerExtension ?: fallbackContainerExtension,
                    headers = playbackInfo.headers,
                    userAgent = playbackInfo.userAgent
                )
            }
            ProviderType.M3U -> url.takeIf { it.isNotBlank() }?.let { passthroughUrl ->
                resolvedProvider.applyPlaybackRequestProfile(
                    ResolvedStreamUrl(
                        url = passthroughUrl,
                        expirationTime = extractStreamExpirationTime(passthroughUrl)
                    )
                )
            }
        }
    }

    private fun ProviderEntity?.applyPlaybackRequestProfile(resolved: ResolvedStreamUrl): ResolvedStreamUrl {
        if (this == null || type == ProviderType.STALKER_PORTAL) {
            return resolved
        }
        val requestProfile = toGenericRequestProfile(ownerTag = "provider:$id/playback")
        return resolved.copy(
            headers = requestProfile.headers + resolved.headers,
            userAgent = resolved.userAgent?.takeIf { it.isNotBlank() } ?: requestProfile.userAgent
        )
    }

    private suspend fun resolveDirectXtreamUrl(
        provider: ProviderEntity,
        url: String,
        fallbackStreamId: Long?,
        fallbackContentType: ContentType?,
        fallbackContainerExtension: String?
    ): ResolvedStreamUrl? {
        val parsed = XtreamUrlFactory.parseCredentialedStreamUrl(url, provider.id)
        val kind = parsed?.kind ?: fallbackContentType?.let(XtreamUrlFactory::kindForContentType) ?: return null
        if (kind != XtreamStreamKind.LIVE) {
            return null
        }
        val streamId = parsed?.streamId ?: fallbackStreamId?.takeIf { it > 0L } ?: return null
        val ext = parsed?.containerExtension ?: fallbackContainerExtension
        val resolvedExt = resolveLiveContainerExtension(kind, ext)
        val decryptedPassword = credentialCrypto.decryptIfNeeded(provider.password)
        val resolvedUrl = XtreamUrlFactory.buildPlaybackUrl(
            serverUrl = provider.serverUrl,
            username = provider.username,
            password = decryptedPassword,
            kind = kind,
            streamId = streamId,
            containerExtension = resolvedExt
        )
        return provider.applyPlaybackRequestProfile(
            ResolvedStreamUrl(
                url = resolvedUrl,
                expirationTime = extractStreamExpirationTime(resolvedUrl),
                containerExtension = resolvedExt
            )
        )
    }

    private suspend fun resolveLiveContainerExtension(
        kind: XtreamStreamKind,
        containerExtension: String?
    ): String? {
        if (kind != XtreamStreamKind.LIVE) {
            return containerExtension
        }
        return when (preferencesRepository.playerLiveStreamFormatMode.first()) {
            LiveStreamFormatMode.AUTO -> containerExtension
            LiveStreamFormatMode.HLS -> "m3u8"
            LiveStreamFormatMode.MPEG_TS -> "ts"
        }
    }

    private suspend fun resolveDirectStalkerUrl(
        provider: ProviderEntity,
        url: String,
        fallbackStreamId: Long?,
        fallbackContentType: ContentType?,
        fallbackContainerExtension: String?
    ): ResolvedStreamUrl? {
        val kind = when (fallbackContentType) {
            ContentType.LIVE -> StalkerStreamKind.LIVE
            ContentType.MOVIE -> StalkerStreamKind.MOVIE
            ContentType.SERIES_EPISODE -> StalkerStreamKind.EPISODE
            else -> return null
        }
        val repairedUrl = repairDirectStalkerUrl(url, kind, fallbackStreamId)
        if (!UrlSecurityPolicy.isAllowedStreamEntryUrl(repairedUrl)) {
            return null
        }
        val descriptor = buildStalkerPlaybackDescriptor(
            primaryCmd = repairedUrl,
            capabilities = com.streamvault.data.remote.stalker.StalkerPortalCapabilities()
        )
        val playbackInfo = when (val resolvedResult = getOrCreateStalkerProvider(provider).resolvePlaybackInfo(kind, descriptor)) {
            is com.streamvault.domain.model.Result.Success -> resolvedResult.data
            is com.streamvault.domain.model.Result.Error -> throw StalkerPlaybackResolutionException(
                resolvedResult.message,
                resolvedResult.exception
            )
            else -> return null
        }
        persistStalkerPlaybackLearning(provider, playbackInfo)
        return ResolvedStreamUrl(
            url = playbackInfo.url,
            expirationTime = extractStreamExpirationTime(playbackInfo.url),
            containerExtension = fallbackContainerExtension,
            headers = playbackInfo.headers,
            userAgent = playbackInfo.userAgent
        )
    }

    private fun repairDirectStalkerUrl(
        url: String,
        kind: StalkerStreamKind,
        fallbackStreamId: Long?
    ): String {
        if (kind != StalkerStreamKind.LIVE || fallbackStreamId == null || fallbackStreamId <= 0L) {
            return url
        }
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val path = uri.path?.lowercase() ?: return url
        if (!path.endsWith("/play/live.php")) {
            return url
        }

        val rawQuery = uri.rawQuery ?: return url
        val parts = rawQuery.split('&').filter { it.isNotBlank() }
        if (parts.isEmpty()) {
            return url
        }

        var hasStream = false
        var changed = false
        val repairedParts = parts.map { part ->
            val key = part.substringBefore('=', missingDelimiterValue = "").lowercase()
            if (key != "stream") {
                return@map part
            }
            hasStream = true
            val value = part.substringAfter('=', missingDelimiterValue = "")
            if (value.isNotBlank()) {
                return@map part
            }
            changed = true
            "stream=$fallbackStreamId"
        }.toMutableList()

        if (!hasStream && rawQuery.contains("play_token=")) {
            repairedParts += "stream=$fallbackStreamId"
            changed = true
        }

        if (!changed) {
            return url
        }

        return URI(
            uri.scheme,
            uri.authority,
            uri.path,
            repairedParts.joinToString("&"),
            uri.fragment
        ).toString()
    }

    private fun getOrCreateStalkerProvider(provider: ProviderEntity): StalkerProvider {
        val providerId = provider.id
        val cached = stalkerProviders[providerId]
        if (cached != null &&
            cached.serverUrl == provider.serverUrl &&
            cached.macAddress == provider.stalkerMacAddress &&
            cached.authMode == provider.stalkerAuthMode &&
            cached.username == provider.username &&
            cached.password == provider.password &&
            cached.portalFingerprint == provider.stalkerPortalFingerprint &&
            cached.magPreset == provider.stalkerMagPreset &&
            cached.bootstrapRecipe == provider.stalkerLastBootstrapRecipe &&
            cached.endpointPreference == provider.stalkerEndpointPreference &&
            cached.cookieMode == provider.stalkerCookieMode &&
            cached.playbackBackendHint == provider.stalkerPlaybackBackendHint &&
            cached.deviceProfile == provider.stalkerDeviceProfile &&
            cached.timezone == provider.stalkerDeviceTimezone &&
            cached.locale == provider.stalkerDeviceLocale &&
            cached.serialNumber == provider.stalkerSerialNumber &&
            cached.deviceId == provider.stalkerDeviceId &&
            cached.deviceId2 == provider.stalkerDeviceId2 &&
            cached.signature == provider.stalkerSignature
        ) {
            return cached.provider
        }

        val decryptedPassword = credentialCrypto.decryptIfNeeded(provider.password)
        val resolvedProvider = StalkerProvider(
            providerId = provider.id,
            api = stalkerApiService,
            portalUrl = provider.serverUrl,
            macAddress = provider.stalkerMacAddress,
            authMode = provider.stalkerAuthMode,
            username = provider.username,
            password = decryptedPassword,
            portalFingerprintHint = provider.stalkerPortalFingerprint,
            magPresetHint = provider.stalkerMagPreset,
            bootstrapRecipeHint = provider.stalkerLastBootstrapRecipe,
            endpointPreferenceHint = provider.stalkerEndpointPreference,
            cookieModeHint = provider.stalkerCookieMode,
            playbackBackendHint = provider.stalkerPlaybackBackendHint,
            portalProfileHint = provider.stalkerPortalProfile,
            preferredPlaybackMode = provider.stalkerLastPlaybackMode
                ?.let { value -> runCatching { StalkerPlaybackMode.valueOf(value) }.getOrNull() },
            deviceProfile = provider.stalkerDeviceProfile,
            timezone = provider.stalkerDeviceTimezone,
            locale = provider.stalkerDeviceLocale,
            serialNumber = provider.stalkerSerialNumber,
            deviceId = provider.stalkerDeviceId,
            deviceId2 = provider.stalkerDeviceId2,
            signature = provider.stalkerSignature
        )
        stalkerProviders[providerId] = CachedStalkerProvider(
            serverUrl = provider.serverUrl,
            macAddress = provider.stalkerMacAddress,
            authMode = provider.stalkerAuthMode,
            username = provider.username,
            password = provider.password,
            portalFingerprint = provider.stalkerPortalFingerprint,
            magPreset = provider.stalkerMagPreset,
            bootstrapRecipe = provider.stalkerLastBootstrapRecipe,
            endpointPreference = provider.stalkerEndpointPreference,
            cookieMode = provider.stalkerCookieMode,
            playbackBackendHint = provider.stalkerPlaybackBackendHint,
            deviceProfile = provider.stalkerDeviceProfile,
            timezone = provider.stalkerDeviceTimezone,
            locale = provider.stalkerDeviceLocale,
            serialNumber = provider.stalkerSerialNumber,
            deviceId = provider.stalkerDeviceId,
            deviceId2 = provider.stalkerDeviceId2,
            signature = provider.stalkerSignature,
            provider = resolvedProvider
        )
        return resolvedProvider
    }

    private suspend fun persistStalkerPlaybackLearning(
        provider: ProviderEntity,
        playbackInfo: com.streamvault.data.remote.stalker.StalkerPlaybackInfo
    ) {
        val updated = provider.copy(
            stalkerLastPlaybackMode = playbackInfo.playbackMode.name,
            stalkerEndpointPreference = playbackInfo.endpointPreference,
            stalkerCookieMode = playbackInfo.cookieMode,
            stalkerPlaybackBackendHint = playbackInfo.backendHint
        )
        if (updated == provider) {
            return
        }
        providerDao.update(updated)
    }
}

internal fun extractStreamExpirationTime(url: String): Long? {
    val query = runCatching { URI(url).rawQuery }.getOrNull()
        ?: url.substringAfter('?', missingDelimiterValue = "").takeIf { it.isNotBlank() }
        ?: return null

    val expirationKeys = setOf(
        "expire",
        "expires",
        "expiry",
        "expiration",
        "expires_at",
        "exp",
        "token_exp",
        "token_expires",
        "token_expiry"
    )

    return query.split('&')
        .asSequence()
        .mapNotNull { part ->
            val key = part.substringBefore('=', missingDelimiterValue = "")
                .lowercase()
                .takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            if (key !in expirationKeys) return@mapNotNull null

            val rawValue = part.substringAfter('=', missingDelimiterValue = "")
            val decodedValue = XtreamUrlCodec.decode(rawValue)
            parseXtreamExpirationDate(decodedValue)
        }
        .firstOrNull()
}
