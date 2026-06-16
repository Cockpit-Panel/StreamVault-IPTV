package com.streamvault.data.remote.stalker

import android.util.Log
import com.streamvault.data.util.AdultContentClassifier
import com.streamvault.data.util.UrlSecurityPolicy
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Season
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.StalkerBootstrapRecipe
import com.streamvault.domain.model.StalkerCookieMode
import com.streamvault.domain.model.StalkerEndpointPreference
import com.streamvault.domain.model.StalkerMagPreset
import com.streamvault.domain.model.StalkerPlaybackBackendHint
import com.streamvault.domain.model.StalkerPortalFingerprint
import com.streamvault.domain.model.StalkerPortalProfile
import com.streamvault.domain.provider.IptvProvider
import com.streamvault.domain.util.ChannelNormalizer
import java.io.IOException
import java.net.URI
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class StalkerPlaybackInfo(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val playbackMode: StalkerPlaybackMode = StalkerPlaybackMode.DIRECT_URL,
    val endpointPreference: StalkerEndpointPreference = StalkerEndpointPreference.AUTO,
    val cookieMode: StalkerCookieMode = StalkerCookieMode.NONE,
    val backendHint: StalkerPlaybackBackendHint = StalkerPlaybackBackendHint.AUTO
)

data class StalkerPagedResult<T>(
    val items: List<T>,
    val page: Int,
    val totalPages: Int,
    val pageSize: Int
) {
    val isComplete: Boolean get() = page >= totalPages
}

class StalkerProvider(
    override val providerId: Long,
    private val api: StalkerApiService,
    private val portalUrl: String,
    private val macAddress: String,
    private val authMode: StalkerAuthMode = StalkerAuthMode.AUTO,
    private val username: String = "",
    private val password: String = "",
    private val portalFingerprintHint: StalkerPortalFingerprint = StalkerPortalFingerprint.BASIC_MAC,
    private val magPresetHint: StalkerMagPreset = StalkerMagPreset.GENERIC_SAFE,
    private val bootstrapRecipeHint: StalkerBootstrapRecipe = StalkerBootstrapRecipe.GENERIC_SAFE,
    private val endpointPreferenceHint: StalkerEndpointPreference = StalkerEndpointPreference.AUTO,
    private val cookieModeHint: StalkerCookieMode = StalkerCookieMode.NONE,
    private val playbackBackendHint: StalkerPlaybackBackendHint = StalkerPlaybackBackendHint.AUTO,
    private val portalProfileHint: StalkerPortalProfile = StalkerPortalProfile.MAG_BASIC,
    private val preferredPlaybackMode: StalkerPlaybackMode? = null,
    private val deviceProfile: String,
    private val timezone: String,
    private val locale: String,
    private val serialNumber: String = "",
    private val deviceId: String = "",
    private val deviceId2: String = "",
    private val signature: String = ""
) : IptvProvider {
    private companion object {
        private const val TAG = "StalkerProvider"
    }

    private data class CategorySeed(
        val id: Long,
        val rawId: String,
        val name: String
    )

    private val authMutex = Mutex()
    private var sessionCache: StalkerSession? = null
    private var accountProfileCache: StalkerProviderProfile? = null
    private val categoryCache = mutableMapOf<ContentType, List<CategorySeed>>()

    suspend fun invalidateAuthentication() {
        authMutex.withLock {
            sessionCache = null
            accountProfileCache = null
            categoryCache.clear()
        }
    }

    override suspend fun authenticate(): Result<Provider> {
        return when (val authResult = ensureAuthenticated()) {
            is Result.Success -> {
                val profile = authResult.data.second
                val learnedDeviceProfile = buildLearnedDeviceProfile(profile)
                val hostLabel = portalUrl.substringAfter("://").substringBefore('/').ifBlank { "portal" }
                val providerName = profile.accountName?.takeUnless { it.isBlank() || it == "0" }
                    ?: normalizedUsername().takeIf { it.isNotBlank() }
                    ?: "${normalizedMacAddress().takeLast(8)}@${com.streamvault.data.util.PortalNameResolver.resolve(StalkerUrlFactory.normalizePortalUrl(portalUrl)) ?: "***"}"
                Result.success(
                    Provider(
                        id = providerId,
                        name = providerName,
                        type = ProviderType.STALKER_PORTAL,
                        serverUrl = StalkerUrlFactory.normalizePortalUrl(portalUrl),
                        username = normalizedUsername(),
                        password = normalizedPassword(),
                        stalkerMacAddress = normalizedMacAddress(),
                        stalkerDeviceProfile = learnedDeviceProfile.deviceProfile,
                        stalkerDeviceTimezone = learnedDeviceProfile.timezone,
                        stalkerDeviceLocale = learnedDeviceProfile.locale,
                        stalkerSerialNumber = learnedDeviceProfile.serialNumber,
                        stalkerDeviceId = learnedDeviceProfile.deviceId,
                        stalkerDeviceId2 = learnedDeviceProfile.deviceId2,
                        stalkerSignature = learnedDeviceProfile.signature,
                        stalkerAuthMode = profile.effectiveAuthMode,
                        stalkerPortalProfile = profile.portalProfile,
                        stalkerPortalFingerprint = profile.portalFingerprint,
                        stalkerMagPreset = profile.magPreset,
                        stalkerLastBootstrapRecipe = profile.bootstrapRecipe,
                        stalkerEndpointPreference = profile.fingerprintEvidence.endpointPreference,
                        stalkerCookieMode = profile.fingerprintEvidence.cookieMode,
                        stalkerPlaybackBackendHint = profile.fingerprintEvidence.playbackBackendHint,
                        stalkerLastPlaybackMode = null,
                        stalkerCredentialsRequired = profile.credentialRequired,
                        stalkerMacRequired = profile.macRequired,
                        stalkerUsesTemporaryLinks = profile.portalCapabilities.usesTemporaryLinks,
                        stalkerModuleRestricted = profile.portalCapabilities.moduleRestricted,
                        stalkerStrictFingerprintRequired = profile.strictFingerprintRequired,
                        stalkerRecipeFallbackUsed = profile.fallbackRecipeUsed,
                        stalkerRecipeRediscoveryAttempts = if (profile.rediscoveryAttempted) 1 else 0,
                        maxConnections = profile.maxConnections ?: 1,
                        expirationDate = profile.expirationDate,
                        apiVersion = "Stalker/MAG Portal",
                        status = resolveProviderStatus(profile)
                    )
                )
            }
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    suspend fun getAccountProfile(): Result<StalkerProviderProfile> {
        return when (val authResult = ensureAuthenticated()) {
            is Result.Success -> Result.success(authResult.data.second)
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    override suspend fun getLiveCategories(): Result<List<Category>> =
        mapCategories(ContentType.LIVE) { session, profile ->
            api.getLiveCategories(session, profile)
        }

    override suspend fun getLiveStreams(categoryId: Long?): Result<List<Channel>> =
        mapItems(ContentType.LIVE, categoryId) { session, profile, rawCategoryId ->
            api.getLiveStreams(session, profile, rawCategoryId)
        }.mapData { items ->
            items.mapNotNull(::toChannel)
        }

    suspend fun streamLiveStreams(onChannel: suspend (Channel) -> Unit): Result<Int> {
        return when (val authResult = ensureAuthenticated()) {
            is Result.Success -> {
                val (session, _) = authResult.data
                val result = api.streamLiveStreams(session, currentDeviceProfile()) { item ->
                    toChannel(item)?.let { channel -> onChannel(channel) }
                }
                when (result) {
                    is Result.Success -> Result.success(result.data)
                    is Result.Error -> Result.error(result.message, result.exception)
                    is Result.Loading -> Result.error("Unexpected loading state")
                }
            }
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    override suspend fun getVodCategories(): Result<List<Category>> =
        mapCategories(ContentType.MOVIE) { session, profile ->
            api.getVodCategories(session, profile)
        }

    override suspend fun getVodStreams(categoryId: Long?): Result<List<Movie>> =
        mapItems(ContentType.MOVIE, categoryId) { session, profile, rawCategoryId ->
            api.getVodStreams(session, profile, rawCategoryId)
        }.mapData { items ->
            items.mapNotNull { item -> toMovie(item, requestedCategoryId = categoryId) }
        }

    suspend fun getVodStreamsPage(categoryId: Long?, page: Int): Result<StalkerPagedResult<Movie>> =
        mapPagedItems(ContentType.MOVIE, categoryId) { session, profile, rawCategoryId ->
            api.getVodStreamsPage(session, profile, rawCategoryId, page)
        }.mapData { paged ->
            StalkerPagedResult(
                items = paged.items.mapNotNull { item -> toMovie(item, requestedCategoryId = categoryId) },
                page = paged.page,
                totalPages = paged.totalPages,
                pageSize = paged.pageSize
            )
        }

    suspend fun getVodStreamsPageUsingItemCategories(categoryId: Long?, page: Int): Result<StalkerPagedResult<Movie>> =
        mapPagedItems(ContentType.MOVIE, categoryId) { session, profile, rawCategoryId ->
            api.getVodStreamsPage(session, profile, rawCategoryId, page)
        }.mapData { paged ->
            StalkerPagedResult(
                items = paged.items.mapNotNull { item -> toMovie(item, requestedCategoryId = null) },
                page = paged.page,
                totalPages = paged.totalPages,
                pageSize = paged.pageSize
            )
        }

    override suspend fun getVodInfo(vodId: Long): Result<Movie> {
        return when (val moviesResult = getVodStreams(null)) {
            is Result.Success -> moviesResult.data
                .firstOrNull { movie ->
                    movie.streamId == vodId || movie.id == vodId
                }?.let { movie -> Result.success(movie) }
                ?: Result.error("Movie not found")
            is Result.Error -> Result.error(moviesResult.message, moviesResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    override suspend fun getSeriesCategories(): Result<List<Category>> =
        mapCategories(ContentType.SERIES) { session, profile ->
            api.getSeriesCategories(session, profile)
        }

    override suspend fun getSeriesList(categoryId: Long?): Result<List<Series>> =
        mapItems(ContentType.SERIES, categoryId) { session, profile, rawCategoryId ->
            api.getSeries(session, profile, rawCategoryId)
        }.mapData { items ->
            items.mapNotNull(::toSeries)
        }

    suspend fun getSeriesListPage(categoryId: Long?, page: Int): Result<StalkerPagedResult<Series>> =
        mapPagedItems(ContentType.SERIES, categoryId) { session, profile, rawCategoryId ->
            api.getSeriesPage(session, profile, rawCategoryId, page)
        }.mapData { paged ->
            StalkerPagedResult(
                items = paged.items.mapNotNull(::toSeries),
                page = paged.page,
                totalPages = paged.totalPages,
                pageSize = paged.pageSize
            )
        }

    suspend fun isWildcardCategory(type: ContentType, categoryId: Long): Boolean {
        val normalizedType = when (type) {
            ContentType.SERIES_EPISODE -> ContentType.SERIES
            else -> type
        }
        return resolveRawCategoryId(normalizedType, categoryId)?.trim() == "*" ||
            categoryId == syntheticCategoryId(normalizedType, "*")
    }

    override suspend fun getSeriesInfo(seriesId: Long): Result<Series> = getSeriesInfo(seriesId.toString())

    suspend fun getSeriesInfo(providerSeriesId: String): Result<Series> {
        return when (val authResult = ensureAuthenticated()) {
            is Result.Success -> {
                val (session, _) = authResult.data
                when (val detailsResult = api.getSeriesDetails(session, currentDeviceProfile(), providerSeriesId)) {
                    is Result.Success -> Result.success(detailsResult.data.toSeries())
                    is Result.Error -> Result.error(detailsResult.message, detailsResult.exception)
                    is Result.Loading -> Result.error("Unexpected loading state")
                }
            }
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    override suspend fun getEpg(channelId: String): Result<List<Program>> {
        return when (val authResult = ensureAuthenticated()) {
            is Result.Success -> {
                val (session, _) = authResult.data
                when (val epgResult = api.getEpg(session, currentDeviceProfile(), channelId)) {
                    is Result.Success -> Result.success(epgResult.data.map { it.toProgram() })
                    is Result.Error -> Result.error(epgResult.message, epgResult.exception)
                    is Result.Loading -> Result.error("Unexpected loading state")
                }
            }
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    suspend fun getBulkEpg(periodHours: Int = 6): Result<List<Program>> {
        return when (val authResult = ensureAuthenticated()) {
            is Result.Success -> {
                val (session, _) = authResult.data
                when (val epgResult = api.getBulkEpg(session, currentDeviceProfile(), periodHours)) {
                    is Result.Success -> Result.success(epgResult.data.map { it.toProgram() })
                    is Result.Error -> Result.error(epgResult.message, epgResult.exception)
                    is Result.Loading -> Result.error("Unexpected loading state")
                }
            }
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    /**
     * Streams the bulk EPG payload one program at a time. Use this in place of [getBulkEpg]
     * when the caller can flush programs incrementally; it avoids materialising the full
     * portal response (which can exceed 30 MB on some Stalker servers).
     */
    suspend fun streamBulkEpg(
        periodHours: Int = 6,
        onProgram: suspend (Program) -> Unit
    ): Result<Int> {
        return when (val authResult = ensureAuthenticated()) {
            is Result.Success -> {
                val (session, _) = authResult.data
                api.streamBulkEpg(session, currentDeviceProfile(), periodHours) { record ->
                    onProgram(record.toProgram())
                }
            }
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    /**
     * Streams a per-channel EPG payload. Mirrors [getEpg] but does not buffer the result.
     */
    suspend fun streamEpg(
        channelId: String,
        periodHours: Int = 6,
        onProgram: suspend (Program) -> Unit
    ): Result<Int> {
        return when (val authResult = ensureAuthenticated()) {
            is Result.Success -> {
                val (session, _) = authResult.data
                api.streamEpg(session, currentDeviceProfile(), channelId, periodHours) { record ->
                    onProgram(record.toProgram())
                }
            }
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    override suspend fun getShortEpg(channelId: String, limit: Int): Result<List<Program>> {
        return when (val authResult = ensureAuthenticated()) {
            is Result.Success -> {
                val (session, _) = authResult.data
                when (val epgResult = api.getShortEpg(session, currentDeviceProfile(), channelId, limit)) {
                    is Result.Success -> Result.success(epgResult.data.map { it.toProgram() })
                    is Result.Error -> Result.error(epgResult.message, epgResult.exception)
                    is Result.Loading -> Result.error("Unexpected loading state")
                }
            }
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    suspend fun resolvePlaybackInfo(
        kind: StalkerStreamKind,
        cmd: String,
        seriesNumber: Int? = null,
        archiveStartSeconds: Long? = null,
        archiveEndSeconds: Long? = null
    ): Result<StalkerPlaybackInfo> = resolvePlaybackInfo(
        kind = kind,
        descriptor = buildStalkerPlaybackDescriptor(cmd),
        seriesNumber = seriesNumber,
        archiveStartSeconds = archiveStartSeconds,
        archiveEndSeconds = archiveEndSeconds
    )

    suspend fun resolvePlaybackInfo(
        kind: StalkerStreamKind,
        descriptor: StalkerPlaybackDescriptor?,
        seriesNumber: Int? = null,
        archiveStartSeconds: Long? = null,
        archiveEndSeconds: Long? = null
    ): Result<StalkerPlaybackInfo> {
        validateArchiveWindow(kind, archiveStartSeconds, archiveEndSeconds)?.let { message ->
            return Result.error(message)
        }
        val resolvedDescriptor = descriptor ?: return Result.error("This portal requires a different playback path than the default command.")
        return resolvePlaybackInfoInternal(
            kind = kind,
            descriptor = resolvedDescriptor,
            seriesNumber = seriesNumber,
            archiveStartSeconds = archiveStartSeconds,
            archiveEndSeconds = archiveEndSeconds,
            allowRebootstrap = true
        )
    }

    private suspend fun resolvePlaybackInfoInternal(
        kind: StalkerStreamKind,
        descriptor: StalkerPlaybackDescriptor,
        seriesNumber: Int?,
        archiveStartSeconds: Long?,
        archiveEndSeconds: Long?,
        allowRebootstrap: Boolean
    ): Result<StalkerPlaybackInfo> {
        return when (val authResult = ensureAuthenticated()) {
            is Result.Success -> {
                val (session, accountProfile) = authResult.data
                val profile = currentDeviceProfile()
                var lastError: Result.Error? = null
                val orderedCandidates = orderStalkerCommandVariants(descriptor.candidates)
                    .sortedBy { variant ->
                        if (preferredPlaybackMode != null && variant.playbackMode == preferredPlaybackMode) 0 else 1
                    }
                orderedCandidates.forEach { variant ->
                    val adapter = resolveStalkerPlaybackAdapter(
                        descriptor = descriptor,
                        variant = variant,
                        portalProfileHint = accountProfile.portalProfile.takeUnless {
                            it == StalkerPortalProfile.MAG_BASIC
                        } ?: portalProfileHint,
                        preferredMode = preferredPlaybackMode,
                        backendHint = accountProfile.fingerprintEvidence.playbackBackendHint
                            .takeUnless { it == StalkerPlaybackBackendHint.AUTO }
                            ?: playbackBackendHint,
                        cookieModeHint = accountProfile.fingerprintEvidence.cookieMode
                            .takeUnless { it == StalkerCookieMode.NONE }
                            ?: cookieModeHint
                    )
                    val directUrl = extractDirectPlaybackUrl(variant.cmd)
                    val directCandidates = when (kind) {
                        StalkerStreamKind.ARCHIVE -> buildArchiveDirectCandidates(
                            sourceUrl = directUrl,
                            startSeconds = archiveStartSeconds,
                            endSeconds = archiveEndSeconds
                        )
                        else -> listOfNotNull(directUrl)
                    }
                    directCandidates
                        .firstOrNull { candidate ->
                            adapter.allowsDirectBypass(variant) &&
                                shouldBypassCreateLink(kind, candidate)
                        }
                        ?.let { candidate ->
                            Log.d(
                                TAG,
                                "Resolved direct Stalker playback provider=$providerId kind=${kind.name} mode=${adapter.adapterMode.name} " +
                                    "candidateMode=${variant.playbackMode.name} endpoint=${effectiveArchiveEndpointPreference(kind, session).name}"
                            )
                            return Result.success(
                                StalkerPlaybackInfo(
                                    url = candidate,
                                    headers = buildPlaybackHeaders(session, profile, candidate),
                                    userAgent = profile.userAgent,
                                    playbackMode = adapter.adapterMode,
                                    endpointPreference = effectiveArchiveEndpointPreference(
                                        kind = kind,
                                        session = session
                                    ),
                                    cookieMode = derivePlaybackCookieMode(
                                        current = effectiveArchiveCookieMode(kind, session, candidate),
                                        url = candidate
                                    ),
                                    backendHint = detectPlaybackBackendHint(candidate, descriptor.capabilities, adapter)
                                )
                            )
                        }

                    if (!adapter.requiresCreateLink(variant)) {
                        lastError = Result.error("This portal requires a different playback path than the default command.")
                        return@forEach
                    }

                    when (
                        val linkResult = api.createLink(
                            session = session,
                            profile = profile,
                            kind = kind,
                            cmd = variant.cmd,
                            seriesNumber = seriesNumber,
                            archiveStartSeconds = archiveStartSeconds,
                            archiveEndSeconds = archiveEndSeconds
                        )
                    ) {
                        is Result.Success -> {
                            val resolvedUrl = repairCreateLinkUrl(
                                kind = kind,
                                resolvedUrl = linkResult.data,
                                sourceDirectUrl = directUrl,
                                archiveStartSeconds = archiveStartSeconds,
                                archiveEndSeconds = archiveEndSeconds
                            )
                            Log.d(
                                TAG,
                                "Resolved create_link playback provider=$providerId kind=${kind.name} mode=${adapter.adapterMode.name} " +
                                    "candidateMode=${variant.playbackMode.name} endpoint=${effectiveArchiveEndpointPreference(kind, session).name} " +
                                    "cookie=${effectiveArchiveCookieMode(kind, session, resolvedUrl).name} " +
                                    "liveTarget=${livePlaybackTargetSummary(directUrl, resolvedUrl)}"
                            )
                            return Result.success(
                                StalkerPlaybackInfo(
                                    url = resolvedUrl,
                                    headers = buildPlaybackHeaders(session, profile, resolvedUrl),
                                    userAgent = profile.userAgent,
                                    playbackMode = adapter.adapterMode,
                                    endpointPreference = effectiveArchiveEndpointPreference(
                                        kind = kind,
                                        session = session
                                    ),
                                    cookieMode = derivePlaybackCookieMode(
                                        current = effectiveArchiveCookieMode(kind, session, resolvedUrl),
                                        url = resolvedUrl
                                    ),
                                    backendHint = detectPlaybackBackendHint(resolvedUrl, descriptor.capabilities, adapter)
                                )
                            )
                        }
                        is Result.Error -> lastError = linkResult
                        is Result.Loading -> {
                            lastError = Result.error("Unexpected loading state")
                        }
                    }
                }

                val needsRebootstrap = allowRebootstrap &&
                    orderedCandidates.any { variant ->
                        resolveStalkerPlaybackAdapter(
                            descriptor = descriptor,
                            variant = variant,
                            portalProfileHint = accountProfile.portalProfile.takeUnless {
                                it == StalkerPortalProfile.MAG_BASIC
                            } ?: portalProfileHint,
                            preferredMode = preferredPlaybackMode,
                            backendHint = accountProfile.fingerprintEvidence.playbackBackendHint
                                .takeUnless { it == StalkerPlaybackBackendHint.AUTO }
                                ?: playbackBackendHint,
                            cookieModeHint = accountProfile.fingerprintEvidence.cookieMode
                                .takeUnless { it == StalkerCookieMode.NONE }
                                ?: cookieModeHint
                        ).allowsRebootstrap(descriptor, accountProfile)
                    } &&
                    isLikelyAuthOrSessionFailure(lastError?.message.orEmpty(), lastError?.exception)
                if (needsRebootstrap) {
                    invalidateAuthentication()
                    return resolvePlaybackInfoInternal(
                        kind = kind,
                        descriptor = descriptor,
                        seriesNumber = seriesNumber,
                        archiveStartSeconds = archiveStartSeconds,
                        archiveEndSeconds = archiveEndSeconds,
                        allowRebootstrap = false
                    )
                }

                val message = when {
                    accountProfile.strictFingerprintRequired && lastError?.message.isNullOrBlank() ->
                        "Portal requires stricter MAG emulation."

                    accountProfile.fallbackRecipeUsed && descriptor.capabilities.usesTemporaryLinks ->
                        "Portal matched a legacy MAG recipe and was retried automatically, but playback still failed."

                    accountProfile.rediscoveryAttempted ->
                        "Stored portal recipe failed; rediscovery attempted."

                    descriptor.capabilities.ambiguousAccountState || accountProfile.ambiguousState ->
                        "Portal profile is ambiguous; playback/session validation failed."

                    descriptor.primaryMode == StalkerPlaybackMode.MULTI_CMD || descriptor.candidates.size > 1 ->
                        "This portal requires a different playback path than the default command."

                    descriptor.capabilities.usesTemporaryLinks ->
                        lastError?.message?.takeIf { it.isNotBlank() }
                            ?: "Portal could not issue a valid temporary playback link for this stream."

                    else -> lastError?.message?.takeIf { it.isNotBlank() }
                        ?: "Portal family detected, but no working recipe succeeded."
                }
                Log.w(
                    TAG,
                    "Stalker playback failed provider=$providerId kind=${kind.name} " +
                        "fingerprint=${accountProfile.portalFingerprint.name} preset=${accountProfile.magPreset.name} " +
                        "recipe=${accountProfile.bootstrapRecipe.name} endpoint=${accountProfile.fingerprintEvidence.endpointPreference.name} " +
                        "cookie=${accountProfile.fingerprintEvidence.cookieMode.name} backend=${accountProfile.fingerprintEvidence.playbackBackendHint.name} " +
                        "fallback=${accountProfile.fallbackRecipeUsed} rediscovery=${accountProfile.rediscoveryAttempted} " +
                        "reason=$message"
                )
                Result.error(
                    message,
                    StalkerPlaybackResolutionException(
                        message = message,
                        cause = lastError?.exception,
                        streamKind = kind,
                        portalFingerprint = accountProfile.portalFingerprint,
                        magPreset = accountProfile.magPreset,
                        bootstrapRecipe = accountProfile.bootstrapRecipe,
                        endpointPreference = accountProfile.fingerprintEvidence.endpointPreference,
                        cookieMode = accountProfile.fingerprintEvidence.cookieMode,
                        playbackBackendHint = accountProfile.fingerprintEvidence.playbackBackendHint,
                        fallbackRecipeUsed = accountProfile.fallbackRecipeUsed,
                        rediscoveryAttempted = accountProfile.rediscoveryAttempted
                    )
                )
            }
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    suspend fun resolvePlaybackUrl(
        kind: StalkerStreamKind,
        cmd: String,
        seriesNumber: Int? = null,
        archiveStartSeconds: Long? = null,
        archiveEndSeconds: Long? = null
    ): Result<String> =
        resolvePlaybackInfo(
            kind = kind,
            cmd = cmd,
            seriesNumber = seriesNumber,
            archiveStartSeconds = archiveStartSeconds,
            archiveEndSeconds = archiveEndSeconds
        ).mapData(StalkerPlaybackInfo::url)

    override suspend fun buildStreamUrl(streamId: Long, containerExtension: String?): String {
        throw UnsupportedOperationException("Stalker stream URLs require a command token context.")
    }

    override suspend fun buildCatchUpUrl(streamId: Long, start: Long, end: Long): String? =
        buildCatchUpUrls(streamId, start, end).firstOrNull()

    override suspend fun buildCatchUpUrls(streamId: Long, start: Long, end: Long): List<String> =
        buildCatchUpUrls(streamId, start, end, sourceStreamUrl = null, sourceCatchUpSource = null)

    suspend fun buildCatchUpUrls(
        streamId: Long,
        start: Long,
        end: Long,
        sourceStreamUrl: String?,
        sourceCatchUpSource: String?
    ): List<String> {
        val safeStart = start.takeIf { it > 0L } ?: return emptyList()
        val safeEnd = end.takeIf { it > safeStart } ?: return emptyList()
        val seedToken = sequenceOf(sourceCatchUpSource, sourceStreamUrl)
            .mapNotNull(StalkerUrlFactory::parseInternalStreamUrl)
            .firstOrNull()
            ?: return emptyList()
        if (seedToken.providerId != providerId) {
            return emptyList()
        }
        if (seedToken.kind != StalkerStreamKind.LIVE && seedToken.kind != StalkerStreamKind.ARCHIVE) {
            return emptyList()
        }
        val seedDescriptor = seedToken.playbackDescriptor
            ?: buildStalkerPlaybackDescriptor(seedToken.cmd)
            ?: return emptyList()
        val orderedCandidates = seedDescriptor.candidates.sortedBy { variant ->
            if (preferredPlaybackMode != null && variant.playbackMode == preferredPlaybackMode) 0 else 1
        }
        return orderedCandidates.mapIndexed { index, variant ->
            StalkerUrlFactory.buildInternalStreamUrl(
                providerId = providerId,
                kind = StalkerStreamKind.ARCHIVE,
                itemId = streamId.takeIf { it > 0L } ?: seedToken.itemId,
                cmd = variant.cmd,
                containerExtension = seedToken.containerExtension,
                archiveStartSeconds = safeStart,
                archiveEndSeconds = safeEnd,
                playbackDescriptor = StalkerPlaybackDescriptor(
                    primaryMode = variant.playbackMode,
                    candidates = listOf(variant.copy(priority = index)),
                    capabilities = seedDescriptor.capabilities
                )
            )
        }.distinct()
    }

    private suspend fun mapCategories(
        type: ContentType,
        loader: suspend (StalkerSession, StalkerDeviceProfile) -> Result<List<StalkerCategoryRecord>>
    ): Result<List<Category>> {
        return when (val authResult = ensureAuthenticated()) {
            is Result.Success -> {
                val (session, _) = authResult.data
                when (val result = loader(session, currentDeviceProfile())) {
                    is Result.Success -> {
                        val categoryRecords = result.data.ifEmpty {
                            when (type) {
                                ContentType.MOVIE -> listOf(StalkerCategoryRecord(id = "*", name = "All Movies"))
                                ContentType.SERIES -> listOf(StalkerCategoryRecord(id = "*", name = "All Series"))
                                else -> emptyList()
                            }
                        }
                        val categories = categoryRecords.map { record ->
                            val id = syntheticCategoryId(type, record.id.ifBlank { record.name })
                            CategorySeed(
                                id = id,
                                rawId = record.id,
                                name = record.name
                            )
                        }
                        categoryCache[type] = categories
                        Result.success(
                            categories.map { seed ->
                                Category(
                                    id = seed.id,
                                    name = seed.name,
                                    type = type,
                                    isAdult = AdultContentClassifier.isAdultCategoryName(seed.name)
                                )
                            }
                        )
                    }
                    is Result.Error -> Result.error(result.message, result.exception)
                    is Result.Loading -> Result.error("Unexpected loading state")
                }
            }
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    private suspend fun mapItems(
        type: ContentType,
        categoryId: Long?,
        loader: suspend (StalkerSession, StalkerDeviceProfile, String?) -> Result<List<StalkerItemRecord>>
    ): Result<List<StalkerItemRecord>> {
        return when (val authResult = ensureAuthenticated()) {
            is Result.Success -> {
                val (session, _) = authResult.data
                val rawCategoryId = resolveRawCategoryId(type, categoryId)
                loader(session, currentDeviceProfile(), rawCategoryId)
            }
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    private suspend fun mapPagedItems(
        type: ContentType,
        categoryId: Long?,
        loader: suspend (StalkerSession, StalkerDeviceProfile, String?) -> Result<StalkerPagedItems>
    ): Result<StalkerPagedItems> {
        return when (val authResult = ensureAuthenticated()) {
            is Result.Success -> {
                val (session, _) = authResult.data
                val rawCategoryId = resolveRawCategoryId(type, categoryId)
                loader(session, currentDeviceProfile(), rawCategoryId)
            }
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    private suspend fun ensureAuthenticated(): Result<Pair<StalkerSession, StalkerProviderProfile>> =
        authMutex.withLock {
            val cachedSession = sessionCache
            val cachedProfile = accountProfileCache
            if (cachedSession != null && cachedProfile != null) {
                return@withLock Result.success(cachedSession to cachedProfile)
            }

            val profile = buildStalkerDeviceProfile(
                portalUrl = portalUrl,
                macAddress = normalizedMacAddress(),
                authMode = authMode,
                magPresetHint = magPresetHint,
                portalFingerprintHint = portalFingerprintHint,
                bootstrapRecipeHint = bootstrapRecipeHint,
                endpointPreferenceHint = endpointPreferenceHint,
                cookieModeHint = cookieModeHint,
                playbackBackendHint = playbackBackendHint,
                username = normalizedUsername(),
                password = normalizedPassword(),
                deviceProfile = normalizedDeviceProfile(),
                timezone = normalizedTimezone(),
                locale = normalizedLocale(),
                serialNumberOverride = normalizedSerialNumber(),
                deviceIdOverride = normalizedDeviceId(),
                deviceId2Override = normalizedDeviceId2(),
                signatureOverride = normalizedSignature()
            )
            when (val authResult = api.authenticate(profile)) {
                is Result.Success -> {
                    sessionCache = authResult.data.first
                    accountProfileCache = authResult.data.second
                    Result.success(authResult.data)
                }
                is Result.Error -> Result.error(authResult.message, authResult.exception)
                is Result.Loading -> Result.error("Unexpected loading state")
            }
        }

    private fun currentDeviceProfile(): StalkerDeviceProfile {
        return buildStalkerDeviceProfile(
            portalUrl = portalUrl,
            macAddress = normalizedMacAddress(),
            authMode = authMode,
            magPresetHint = magPresetHint,
            portalFingerprintHint = portalFingerprintHint,
            bootstrapRecipeHint = bootstrapRecipeHint,
            endpointPreferenceHint = endpointPreferenceHint,
            cookieModeHint = cookieModeHint,
            playbackBackendHint = playbackBackendHint,
            username = normalizedUsername(),
            password = normalizedPassword(),
            deviceProfile = normalizedDeviceProfile(),
            timezone = normalizedTimezone(),
            locale = normalizedLocale(),
            serialNumberOverride = normalizedSerialNumber(),
            deviceIdOverride = normalizedDeviceId(),
            deviceId2Override = normalizedDeviceId2(),
            signatureOverride = normalizedSignature()
        )
    }

    private fun buildLearnedDeviceProfile(profile: StalkerProviderProfile): StalkerDeviceProfile {
        return buildStalkerDeviceProfile(
            portalUrl = portalUrl,
            macAddress = normalizedMacAddress(),
            authMode = profile.effectiveAuthMode,
            magPresetHint = profile.magPreset,
            portalFingerprintHint = profile.portalFingerprint,
            bootstrapRecipeHint = profile.bootstrapRecipe,
            endpointPreferenceHint = profile.fingerprintEvidence.endpointPreference,
            cookieModeHint = profile.fingerprintEvidence.cookieMode,
            playbackBackendHint = profile.fingerprintEvidence.playbackBackendHint,
            username = normalizedUsername(),
            password = normalizedPassword(),
            deviceProfile = normalizedDeviceProfile(),
            timezone = normalizedTimezone(),
            locale = normalizedLocale(),
            serialNumberOverride = normalizedSerialNumber(),
            deviceIdOverride = normalizedDeviceId(),
            deviceId2Override = normalizedDeviceId2(),
            signatureOverride = normalizedSignature()
        )
    }

    private fun buildPlaybackHeaders(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        url: String
    ): Map<String, String> = buildMap {
        val omitAuthorization = shouldOmitPlaybackAuthorization(url)
        val serverCookieHeader = api.currentCookieHeader(session)
            .ifBlank { session.serverCookieHeader }
        put("Referer", session.portalReferer)
        put("Accept", "*/*")
        put("Accept-Encoding", "identity")
        put("Cookie", buildPlaybackCookieHeader(serverCookieHeader, profile))
        put("X-User-Agent", profile.xUserAgent)
        session.token.takeIf { it.isNotBlank() && !omitAuthorization }?.let { token ->
            put("Authorization", "Bearer $token")
        }
    }

    private fun shouldOmitPlaybackAuthorization(url: String): Boolean {
        val path = runCatching { URI(url).path?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
        return path.endsWith("/play/live.php") || path.endsWith("/play/movie.php")
    }

    private fun buildPlaybackCookieHeader(
        serverCookieHeader: String,
        profile: StalkerDeviceProfile
    ): String {
        val cookies = linkedMapOf(
            "mac" to profile.macAddress,
            "stb_lang" to profile.locale,
            "timezone" to profile.timezone,
            "sn" to profile.serialNumber,
            "device_id" to profile.deviceId,
            "device_id2" to profile.deviceId2,
            "signature" to profile.signature
        )
        serverCookieHeader.split(';')
            .mapNotNull { part ->
                val key = part.substringBefore('=', missingDelimiterValue = "").trim()
                val value = part.substringAfter('=', missingDelimiterValue = "").trim()
                key.takeIf { it.isNotBlank() && value.isNotBlank() }?.let { it to value }
            }.forEach { (key, value) ->
                cookies.putIfAbsent(key, value)
        }
        return cookies.entries.joinToString("; ") { (key, value) -> "$key=$value" }
    }

    private fun derivePlaybackCookieMode(
        current: StalkerCookieMode,
        url: String
    ): StalkerCookieMode {
        val path = runCatching { URI(url).path?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
        val playbackNeedsCookies = path.endsWith("/play/live.php") || path.endsWith("/play/movie.php")
        return when {
            playbackNeedsCookies && current == StalkerCookieMode.CREATE_LINK -> StalkerCookieMode.BOTH
            playbackNeedsCookies -> StalkerCookieMode.PLAYBACK
            else -> current
        }
    }

    private fun effectiveArchiveCookieMode(
        kind: StalkerStreamKind,
        session: StalkerSession,
        url: String
    ): StalkerCookieMode {
        val base = session.fingerprintEvidence.cookieMode
        if (kind != StalkerStreamKind.ARCHIVE) {
            return base
        }
        return when (base) {
            StalkerCookieMode.NONE -> StalkerCookieMode.PLAYBACK
            StalkerCookieMode.CREATE_LINK -> StalkerCookieMode.BOTH
            else -> derivePlaybackCookieMode(base, url)
        }
    }

    private fun effectiveArchiveEndpointPreference(
        kind: StalkerStreamKind,
        session: StalkerSession
    ): StalkerEndpointPreference =
        if (kind == StalkerStreamKind.ARCHIVE) {
            session.fingerprintEvidence.archiveEndpointPreference.takeUnless {
                it == StalkerEndpointPreference.AUTO
            } ?: session.fingerprintEvidence.endpointPreference
        } else {
            session.fingerprintEvidence.endpointPreference
        }

    private fun buildArchiveDirectCandidates(
        sourceUrl: String?,
        startSeconds: Long?,
        endSeconds: Long?
    ): List<String> {
        val normalizedSource = sourceUrl?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val safeStart = startSeconds?.takeIf { it > 0L } ?: return listOf(normalizedSource)
        val safeEnd = endSeconds?.takeIf { it > safeStart } ?: return listOf(normalizedSource)
        val liveNow = maxOf(safeEnd, System.currentTimeMillis() / 1000L)
        val withUtc = appendArchiveQueryParameter(normalizedSource, "utc", safeStart.toString())
        val withLutc = appendArchiveQueryParameter(withUtc ?: normalizedSource, "lutc", liveNow.toString())
        return listOfNotNull(
            normalizedSource.takeIf { hasArchiveQueryHints(it) },
            withLutc
        ).distinct()
    }

    private fun hasArchiveQueryHints(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val query = uri.rawQuery?.lowercase(Locale.ROOT).orEmpty()
        return query.contains("utc=") ||
            query.contains("lutc=") ||
            query.contains("timeshift=") ||
            uri.path?.lowercase(Locale.ROOT)?.contains("timeshift") == true
    }

    private fun appendArchiveQueryParameter(url: String, name: String, value: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val rawQuery = uri.rawQuery
        val existingParts = rawQuery
            ?.split('&')
            ?.filter { it.isNotBlank() }
            .orEmpty()
            .toMutableList()
        val replaced = existingParts.map { part ->
            val key = part.substringBefore('=', missingDelimiterValue = "")
            if (key.equals(name, ignoreCase = true)) {
                "$key=$value"
            } else {
                part
            }
        }.toMutableList()
        if (replaced.none { part ->
                part.substringBefore('=', missingDelimiterValue = "").equals(name, ignoreCase = true)
            }
        ) {
            replaced += "$name=$value"
        }
        val query = replaced.joinToString("&")
        return URI(uri.scheme, uri.authority, uri.path, query, uri.fragment).toString()
    }

    private fun detectPlaybackBackendHint(
        url: String,
        capabilities: StalkerPortalCapabilities,
        adapter: StalkerPlaybackAdapter
    ): StalkerPlaybackBackendHint {
        val path = runCatching { URI(url).path?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
        return when {
            path.endsWith("/play/live.php") -> StalkerPlaybackBackendHint.PLAY_LIVE
            path.endsWith("/play/movie.php") -> StalkerPlaybackBackendHint.PLAY_MOVIE
            adapter.adapterMode == StalkerPlaybackMode.TEMP_LINK_FLUSSONIC ||
                adapter.adapterMode == StalkerPlaybackMode.TEMP_LINK_WOWZA ||
                adapter.adapterMode == StalkerPlaybackMode.TEMP_LINK_NGINX ||
                adapter.adapterMode == StalkerPlaybackMode.PLAY_LIVE_PORTAL ||
                adapter.adapterMode == StalkerPlaybackMode.PLAY_MOVIE_PORTAL ->
                if (capabilities.nginxSecureLink || capabilities.useHttpTemporaryLink) {
                    StalkerPlaybackBackendHint.TEMP_LINK_STRICT
                } else {
                    StalkerPlaybackBackendHint.TEMP_LINK
                }

            else -> StalkerPlaybackBackendHint.DIRECT
        }
    }

    private fun extractDirectPlaybackUrl(cmd: String): String? {
        return cmd
            .substringAfter(' ', missingDelimiterValue = cmd)
            .trim()
            .takeIf(UrlSecurityPolicy::isAllowedStreamEntryUrl)
    }

    private fun shouldBypassCreateLink(kind: StalkerStreamKind, directUrl: String): Boolean {
        val parsed = runCatching { URI(directUrl) }.getOrNull() ?: return false
        val host = parsed.host?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (host.isBlank()) return false
        if (host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0") return false
        if ((kind == StalkerStreamKind.LIVE || kind == StalkerStreamKind.ARCHIVE) &&
            parsed.isStalkerChannelCommandPath()
        ) {
            return false
        }
        if ((kind == StalkerStreamKind.LIVE || kind == StalkerStreamKind.ARCHIVE) && !hasUsableLiveStreamTarget(parsed)) return false

        return true
    }

    private fun repairCreateLinkUrl(
        kind: StalkerStreamKind,
        resolvedUrl: String,
        sourceDirectUrl: String?,
        archiveStartSeconds: Long? = null,
        archiveEndSeconds: Long? = null
    ): String {
        val repairedArchive = if (kind == StalkerStreamKind.ARCHIVE) {
            buildArchiveDirectCandidates(resolvedUrl, archiveStartSeconds, archiveEndSeconds).firstOrNull()
                ?: resolvedUrl
        } else {
            resolvedUrl
        }
        if (kind != StalkerStreamKind.LIVE || sourceDirectUrl.isNullOrBlank()) {
            return repairedArchive
        }

        val resolvedUri = runCatching { URI(repairedArchive) }.getOrNull() ?: return repairedArchive
        if (!isLivePlayPath(resolvedUri)) {
            return repairedArchive
        }
        val resolvedStreamId = resolvedUri.queryParameter("stream")?.takeIf { it.isUsableStreamId() }
        if (resolvedStreamId != null) {
            return repairedArchive
        }

        val sourceUri = runCatching { URI(sourceDirectUrl) }.getOrNull()
        val sourceStreamId = sourceUri?.liveStreamTargetId() ?: return repairedArchive
        return upsertQueryParameter(resolvedUri, "stream", sourceStreamId) ?: repairedArchive
    }

    private fun hasUsableLiveStreamTarget(uri: URI): Boolean {
        if (!isLivePlayPath(uri)) {
            return true
        }
        return uri.queryParameter("stream")?.isUsableStreamId() == true
    }

    private fun isLivePlayPath(uri: URI): Boolean =
        uri.path?.trim()?.lowercase(Locale.ROOT).orEmpty().endsWith("/play/live.php")

    private fun URI.liveStreamTargetId(): String? {
        queryParameter("stream")?.takeIf { it.isUsableStreamId() }?.let { return it }
        val path = path?.trim('/') ?: return null
        val segments = path.split('/').filter { it.isNotBlank() }
        val channelSegment = segments
            .dropLast(1)
            .zip(segments.drop(1))
            .firstOrNull { (previous, _) -> previous.equals("ch", ignoreCase = true) }
            ?.second
            ?: return null
        return channelSegment.trimEnd('_').takeIf { it.isUsableStreamId() }
    }

    private fun String.isUsableStreamId(): Boolean {
        val value = trim()
        return value.isNotBlank() &&
            value != "0" &&
            !value.equals("null", ignoreCase = true)
    }

    private fun livePlaybackTargetSummary(sourceDirectUrl: String?, resolvedUrl: String): String {
        val sourceUri = sourceDirectUrl?.let { runCatching { URI(it) }.getOrNull() }
        val resolvedUri = runCatching { URI(resolvedUrl) }.getOrNull()
        if (sourceUri == null && resolvedUri == null) {
            return "none"
        }
        val sourceTarget = sourceUri?.liveStreamTargetId().orEmpty()
        val resolvedTarget = resolvedUri?.takeIf(::isLivePlayPath)?.queryParameter("stream").orEmpty()
        return "source=${sourceTarget.ifBlank { "none" }} resolved=${resolvedTarget.ifBlank { "none" }}"
    }

    private fun URI.queryParameter(name: String): String? {
        val rawQuery = rawQuery ?: return null
        return rawQuery.split('&')
            .asSequence()
            .map { part ->
                val key = part.substringBefore('=', missingDelimiterValue = "")
                val value = part.substringAfter('=', missingDelimiterValue = "")
                key to value
            }
            .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
            ?.second
    }

    private fun upsertQueryParameter(uri: URI, name: String, value: String): String? {
        val rawQuery = uri.rawQuery.orEmpty()
        val parts = rawQuery.split('&')
            .filter { it.isNotBlank() }
        var replaced = false
        val updatedParts = parts.map { part ->
            val key = part.substringBefore('=', missingDelimiterValue = "")
            if (key.equals(name, ignoreCase = true)) {
                replaced = true
                "$key=$value"
            } else {
                part
            }
        }
        val updated = if (replaced) {
            updatedParts
        } else {
            updatedParts + "$name=$value"
        }.joinToString("&")
        return URI(uri.scheme, uri.authority, uri.path, updated, uri.fragment).toString()
    }

    private suspend fun resolveRawCategoryId(type: ContentType, categoryId: Long?): String? {
        val normalizedType = when (type) {
            ContentType.SERIES_EPISODE -> ContentType.SERIES
            else -> type
        }
        val targetId = categoryId ?: return null
        val cached = categoryCache[normalizedType]
        if (cached != null) {
            return cached.firstOrNull { it.id == targetId }?.rawId
        }
        when (val categoriesResult = when (normalizedType) {
            ContentType.LIVE -> getLiveCategories()
            ContentType.MOVIE -> getVodCategories()
            ContentType.SERIES -> getSeriesCategories()
            ContentType.SERIES_EPISODE -> Result.success(emptyList())
        }) {
            is Result.Success -> return categoryCache[normalizedType]?.firstOrNull { it.id == targetId }?.rawId
            else -> return null
        }
    }

    private fun toChannel(item: StalkerItemRecord): Channel? {
        val numericId = stableItemId(ContentType.LIVE, item.id)
        val category = resolveCategory(ContentType.LIVE, item.categoryId, item.categoryName)
        val directStreamUrl = item.streamUrl
            ?.substringAfter(' ', missingDelimiterValue = item.streamUrl)
            ?.trim()
            ?.takeIf(UrlSecurityPolicy::isAllowedStreamEntryUrl)
        val streamUrl = item.cmd?.takeIf { it.isNotBlank() }?.let { cmd ->
            StalkerUrlFactory.buildInternalStreamUrl(
                providerId = providerId,
                kind = StalkerStreamKind.LIVE,
                itemId = numericId,
                cmd = cmd,
                containerExtension = item.containerExtension,
                playbackDescriptor = item.playbackDescriptor
            )
        } ?: directStreamUrl
            ?: return null
        val resolvedName = item.name.ifBlank { "Channel $numericId" }
        val catchUpSupported = item.archiveAvailable == true ||
            item.portalCapabilities.archiveAvailable ||
            item.allowLocalTimeshift == true ||
            item.allowLocalPvr == true ||
            item.allowRemotePvr == true
        return Channel(
            id = 0L,
            name = resolvedName,
            logoUrl = item.logoUrl,
            categoryId = category.id,
            categoryName = category.name,
            streamUrl = streamUrl,
            epgChannelId = item.epgChannelId ?: item.id,
            number = item.number.coerceAtLeast(0),
            catchUpSupported = catchUpSupported,
            catchUpDays = 0,
            catchUpSource = streamUrl.takeIf { catchUpSupported },
            providerId = providerId,
            isAdult = item.isAdult || AdultContentClassifier.isAdultCategoryName(category.name),
            isUserProtected = false,
            logicalGroupId = ChannelNormalizer.getLogicalGroupId(resolvedName, providerId),
            streamId = numericId
        )
    }

    private fun toMovie(item: StalkerItemRecord, requestedCategoryId: Long? = null): Movie? {
        val numericId = stableItemId(ContentType.MOVIE, item.id)
        val category = requestedCategorySeed(ContentType.MOVIE, requestedCategoryId)
            ?: resolveCategory(ContentType.MOVIE, item.categoryId, item.categoryName)
        val directStreamUrl = item.streamUrl
            ?.substringAfter(' ', missingDelimiterValue = item.streamUrl)
            ?.trim()
            ?.takeIf(UrlSecurityPolicy::isAllowedStreamEntryUrl)
        val streamUrl = item.cmd?.takeIf { it.isNotBlank() }?.let { cmd ->
            StalkerUrlFactory.buildInternalStreamUrl(
                providerId = providerId,
                kind = StalkerStreamKind.MOVIE,
                itemId = numericId,
                cmd = cmd,
                containerExtension = item.containerExtension,
                playbackDescriptor = item.playbackDescriptor
            )
        } ?: directStreamUrl
            ?: return null
        return Movie(
            id = 0L,
            name = item.name.ifBlank { "Movie $numericId" },
            posterUrl = item.logoUrl,
            backdropUrl = item.backdropUrl,
            categoryId = category.id,
            categoryName = category.name,
            streamUrl = streamUrl,
            containerExtension = item.containerExtension,
            plot = item.plot,
            cast = item.cast,
            director = item.director,
            genre = item.genre,
            releaseDate = item.releaseDate,
            rating = item.rating.coerceIn(0f, 10f),
            tmdbId = item.tmdbId,
            youtubeTrailer = item.youtubeTrailer,
            providerId = providerId,
            isAdult = item.isAdult || AdultContentClassifier.isAdultCategoryName(category.name),
            isUserProtected = false,
            streamId = numericId,
            addedAt = item.addedAt
        )
    }

    private fun requestedCategorySeed(type: ContentType, categoryId: Long?): CategorySeed? {
        val targetId = categoryId ?: return null
        val normalizedType = when (type) {
            ContentType.SERIES_EPISODE -> ContentType.SERIES
            else -> type
        }
        return categoryCache[normalizedType]?.firstOrNull { category ->
            category.id == targetId || category.rawId.toLongOrNull() == targetId
        }
    }

    private fun toSeries(item: StalkerItemRecord): Series? {
        val numericId = stableItemId(ContentType.SERIES, item.id)
        val category = resolveCategory(ContentType.SERIES, item.categoryId, item.categoryName)
        return Series(
            id = 0L,
            name = item.name.ifBlank { "Series $numericId" },
            posterUrl = item.logoUrl,
            backdropUrl = item.backdropUrl,
            categoryId = category.id,
            categoryName = category.name,
            plot = item.plot,
            cast = item.cast,
            director = item.director,
            genre = item.genre,
            releaseDate = item.releaseDate,
            rating = item.rating.coerceIn(0f, 10f),
            tmdbId = item.tmdbId,
            youtubeTrailer = item.youtubeTrailer,
            providerId = providerId,
            isAdult = item.isAdult || AdultContentClassifier.isAdultCategoryName(category.name),
            isUserProtected = false,
            lastModified = item.addedAt,
            seriesId = item.id.toLongOrNull() ?: numericId,
            providerSeriesId = item.id
        )
    }

    private fun StalkerSeriesDetails.toSeries(): Series {
        val mappedSeries = toSeries(series)
        val baseSeries = if (mappedSeries != null) {
            mappedSeries.copy(name = series.name)
        } else {
            Series(
                id = 0L,
                name = series.name,
                providerId = providerId,
                seriesId = series.id.toLongOrNull() ?: stableItemId(ContentType.SERIES, series.id),
                providerSeriesId = series.id
            )
        }
        val mappedSeasons = seasons
            .sortedBy { it.seasonNumber }
            .map { season ->
                val episodes = season.episodes.mapIndexed { index, episode ->
                    episode.toEpisode(
                        fallbackSeriesId = baseSeries.seriesId,
                        fallbackSeasonNumber = season.seasonNumber,
                        fallbackEpisodeNumber = index + 1
                    )
                }
                Season(
                    seasonNumber = season.seasonNumber.coerceAtLeast(0),
                    name = season.name.ifBlank { "Season ${season.seasonNumber}" },
                    coverUrl = season.coverUrl,
                    episodes = episodes,
                    episodeCount = episodes.size
                )
            }
        return baseSeries.copy(seasons = mappedSeasons)
    }

    private fun StalkerEpisodeRecord.toEpisode(
        fallbackSeriesId: Long,
        fallbackSeasonNumber: Int,
        fallbackEpisodeNumber: Int
    ): Episode {
        val numericId = stableItemId(ContentType.SERIES_EPISODE, id)
        val directStreamUrl = cmd
            ?.substringAfter(' ', missingDelimiterValue = cmd)
            ?.trim()
            ?.takeIf(UrlSecurityPolicy::isAllowedStreamEntryUrl)
        val resolvedStreamUrl = cmd?.takeIf { it.isNotBlank() }?.let { resolvedCmd ->
            StalkerUrlFactory.buildInternalStreamUrl(
                providerId = providerId,
                kind = StalkerStreamKind.EPISODE,
                itemId = numericId,
                cmd = resolvedCmd,
                containerExtension = containerExtension,
                seriesNumber = seasonShellEpisodeSelector(resolvedCmd, episodeNumber),
                playbackDescriptor = buildStalkerPlaybackDescriptor(
                    primaryCmd = resolvedCmd,
                    capabilities = StalkerPortalCapabilities()
                )
            )
        } ?: directStreamUrl.orEmpty()
        return Episode(
            id = numericId,
            title = title.ifBlank { "Episode $fallbackEpisodeNumber" },
            episodeNumber = episodeNumber.coerceAtLeast(1),
            seasonNumber = seasonNumber.takeIf { it > 0 } ?: fallbackSeasonNumber.coerceAtLeast(1),
            streamUrl = resolvedStreamUrl,
            containerExtension = containerExtension,
            coverUrl = coverUrl,
            plot = plot,
            durationSeconds = durationSeconds.coerceAtLeast(0),
            rating = rating.coerceIn(0f, 10f),
            releaseDate = releaseDate,
            seriesId = fallbackSeriesId,
            providerId = providerId,
            isAdult = false,
            isUserProtected = false,
            episodeId = id.toLongOrNull() ?: numericId
        )
    }

    private fun seasonShellEpisodeSelector(cmd: String, episodeNumber: Int): Int? {
        if (episodeNumber <= 0) {
            return null
        }
        val decoded = runCatching {
            String(Base64.getDecoder().decode(cmd.trim()), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val normalized = decoded.lowercase(Locale.ROOT)
        if (!normalized.contains("\"type\":\"series\"")) {
            return null
        }
        if (!normalized.contains("\"season_num\"") && !normalized.contains("\"season_id\"")) {
            return null
        }
        if (normalized.contains("\"episode_number\"") || normalized.contains("\"series_number\"")) {
            return null
        }
        return episodeNumber
    }

    private fun StalkerProgramRecord.toProgram(): Program =
        Program(
            id = id.toLongOrNull() ?: stableItemId(ContentType.LIVE, id),
            channelId = channelId,
            title = title,
            description = description,
            startTime = startTimeMillis,
            endTime = endTimeMillis,
            hasArchive = hasArchive,
            isNowPlaying = isNowPlaying,
            providerId = providerId
        )

    private fun resolveCategory(type: ContentType, rawId: String?, rawName: String?): CategorySeed {
        val normalizedName = rawName?.trim().takeUnless { it.isNullOrBlank() }
        val normalizedRawId = rawId?.trim().takeUnless { it.isNullOrBlank() }
        val cached = categoryCache[type]
            ?.firstOrNull { category ->
                category.rawId == normalizedRawId ||
                    (normalizedName != null && category.name.equals(normalizedName, ignoreCase = true))
            }
        if (cached != null) {
            return cached
        }
        val fallbackSeed = normalizedRawId ?: normalizedName ?: "uncategorized"
        return CategorySeed(
            id = syntheticCategoryId(type, fallbackSeed),
            rawId = normalizedRawId ?: fallbackSeed,
            name = normalizedName ?: "Category $fallbackSeed"
        )
    }

    private fun stableItemId(type: ContentType, rawId: String): Long =
        rawId.trim().toLongOrNull()?.takeIf { it > 0 } ?: syntheticCategoryId(type, rawId)

    private fun syntheticCategoryId(type: ContentType, seed: String): Long {
        val normalized = "$providerId/${type.name}/${seed.trim().lowercase(Locale.ROOT)}"
        return (normalized.hashCode().toLong() and 0x7fff_ffffL).coerceAtLeast(1L)
    }

    private fun normalizedMacAddress(): String =
        macAddress.trim().uppercase(Locale.ROOT)

    private fun normalizedUsername(): String =
        username.trim()

    private fun normalizedPassword(): String =
        password

    private fun normalizedDeviceProfile(): String =
        deviceProfile.trim().ifBlank { "MAG250" }

    private fun normalizedTimezone(): String =
        timezone.trim().ifBlank { java.util.TimeZone.getDefault().id }

    private fun normalizedLocale(): String =
        locale.trim().ifBlank { Locale.getDefault().language.ifBlank { "en" } }

    private fun normalizedSerialNumber(): String =
        serialNumber.trim().uppercase(Locale.ROOT)

    private fun normalizedDeviceId(): String =
        deviceId.trim().uppercase(Locale.ROOT)

    private fun normalizedDeviceId2(): String =
        deviceId2.trim().uppercase(Locale.ROOT)

    private fun normalizedSignature(): String =
        signature.trim().uppercase(Locale.ROOT)

    private fun resolveProviderStatus(profile: StalkerProviderProfile): ProviderStatus {
        val normalizedStatus = profile.statusLabel?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (normalizedStatus in setOf("disabled", "blocked", "banned")) {
            return ProviderStatus.DISABLED
        }
        val expirationDate = profile.expirationDate
        if (expirationDate != null && expirationDate in 1 until System.currentTimeMillis()) {
            return ProviderStatus.EXPIRED
        }
        if (normalizedStatus in setOf("active", "enabled", "1")) {
            return ProviderStatus.ACTIVE
        }
        if (normalizedStatus == "0" || profile.authAccess == false || profile.ambiguousState) {
            return ProviderStatus.PARTIAL
        }
        return ProviderStatus.UNKNOWN
    }

    private fun isLikelyAuthOrSessionFailure(message: String, exception: Throwable?): Boolean {
        val normalizedMessage = message.lowercase(Locale.ROOT)
        val exceptionMessage = exception?.message?.lowercase(Locale.ROOT).orEmpty()
        return normalizedMessage.contains("http 401") ||
            normalizedMessage.contains("http 403") ||
            normalizedMessage.contains("http 204") ||
            normalizedMessage.contains("http 456") ||
            normalizedMessage.contains("authorization") ||
            normalizedMessage.contains("token") ||
            normalizedMessage.contains("access denied") ||
            normalizedMessage.contains("forbidden") ||
            normalizedMessage.contains("temporary playback link") ||
            normalizedMessage.contains("no content") ||
            normalizedMessage.contains("empty temporary link") ||
            exceptionMessage.contains("http 401") ||
            exceptionMessage.contains("http 403") ||
            exceptionMessage.contains("http 204") ||
            exceptionMessage.contains("http 456") ||
            exceptionMessage.contains("authorization") ||
            exceptionMessage.contains("token") ||
            exceptionMessage.contains("no content")
    }

    private fun validateArchiveWindow(
        kind: StalkerStreamKind,
        archiveStartSeconds: Long?,
        archiveEndSeconds: Long?
    ): String? {
        if (kind != StalkerStreamKind.ARCHIVE) {
            return null
        }
        val safeStart = archiveStartSeconds?.takeIf { it > 0L }
            ?: return "Archive playback requires a valid start time."
        val safeEnd = archiveEndSeconds?.takeIf { it > safeStart }
            ?: return "Archive playback requires an end time after the start time."
        val maxWindowSeconds = 7L * 24L * 60L * 60L
        return if (safeEnd - safeStart > maxWindowSeconds) {
            "Archive playback window is too large for a single request."
        } else {
            null
        }
    }
}

private inline fun <T, R> Result<T>.mapData(transform: (T) -> R): Result<R> = when (this) {
    is Result.Success -> Result.success(transform(data))
    is Result.Error -> Result.error(message, exception)
    is Result.Loading -> Result.error("Unexpected loading state")
}
