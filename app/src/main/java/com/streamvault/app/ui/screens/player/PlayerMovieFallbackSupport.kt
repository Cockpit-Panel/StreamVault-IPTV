package com.streamvault.app.ui.screens.player

import com.streamvault.domain.model.Movie
import com.streamvault.domain.util.movieVariantQualityScore
import java.util.Locale

private val AVC_CODEC_TOKENS = listOf("avc", "h264", "x264")
private val HEVC_CODEC_TOKENS = listOf("hevc", "h265", "x265", "hev1", "hvc1")
private val MOVIE_CODEC_TOKEN_REGEX = Regex("""[^a-z0-9]+""")

internal suspend fun PlayerViewModel.tryFallbackToAvcMovieVariant(
    requestVersion: Long,
    playbackUrl: String
): Boolean {
    if (currentContentType != com.streamvault.domain.model.ContentType.MOVIE) return false
    if (currentProviderId <= 0L || currentContentId <= 0L) return false
    if (hasRetriedWithAvcMovieVariant) return false
    if (!isActivePlaybackSession(requestVersion, playbackUrl)) return false

    val currentMovie = movieRepository.getMovie(currentContentId) ?: return false
    val variants = movieRepository.getMovieVariants(currentContentId)
    val fallbackMovie = selectAvcMovieFallbackVariant(currentMovie, variants) ?: return false
    val fallbackStreamInfo = movieRepository.getStreamInfo(fallbackMovie).getOrNull() ?: return false

    if (!isActivePlaybackSession(requestVersion, playbackUrl)) return false

    hasRetriedWithAvcMovieVariant = true
    currentContentId = fallbackMovie.id
    currentStreamUrl = fallbackMovie.streamUrl
    currentTitle = fallbackMovie.name
    playbackTitleFlow.value = fallbackMovie.name
    currentArtworkUrl = fallbackMovie.posterUrl ?: fallbackMovie.backdropUrl ?: currentArtworkUrl

    val resolvedStreamInfo = fallbackStreamInfo.copy(title = fallbackStreamInfo.title ?: fallbackMovie.name)
    setLastFailureReason("HEVC playback failed. Retrying with an AVC/H.264 movie variant.")
    appendRecoveryAction("Switched to AVC/H.264 movie variant")
    showPlayerNotice(
        message = "Retrying with an AVC/H.264 movie variant.",
        recoveryType = PlayerRecoveryType.DECODER,
        actions = buildRecoveryActions(PlayerRecoveryType.DECODER)
    )
    if (!preparePlayer(resolvedStreamInfo, requestVersion)) return false
    playerEngine.play()
    return true
}

internal fun selectAvcMovieFallbackVariant(
    currentMovie: Movie,
    variants: List<Movie>
): Movie? {
    val candidateVariants = variants.asSequence()
        .filter { it.id != currentMovie.id }
        .filter { it.streamUrl.isNotBlank() }
        .sortedWith(
            compareByDescending<Movie> { movieCodecFallbackPriority(it.name) }
                .thenByDescending { movieVariantQualityScore(it.name) }
                .thenByDescending { it.addedAt }
                .thenByDescending { it.rating }
                .thenBy { it.name.lowercase(Locale.ROOT) }
                .thenBy { it.id }
        )

    return candidateVariants.firstOrNull { movieCodecFallbackPriority(it.name) > 0 }
}

private fun movieCodecFallbackPriority(title: String): Int {
    val normalized = normalizeMovieVariantCodecText(title)
    val hasHevc = HEVC_CODEC_TOKENS.any { token -> normalized.contains(" $token ") }
    if (hasHevc) return 0

    val hasAvc = AVC_CODEC_TOKENS.any { token -> normalized.contains(" $token ") }
    return if (hasAvc) 2 else 1
}

private fun normalizeMovieVariantCodecText(value: String): String {
    val normalized = value.lowercase(Locale.ROOT)
        .replace(MOVIE_CODEC_TOKEN_REGEX, " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return " $normalized "
}
