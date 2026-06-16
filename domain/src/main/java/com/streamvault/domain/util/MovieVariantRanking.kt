package com.streamvault.domain.util

import java.text.Normalizer
import java.util.Locale

private val QUALITY_TOKENS: List<Pair<String, Int>> = listOf(
    "8k" to 4320,
    "4320p" to 4320,
    "uhd" to 2160,
    "ultra hd" to 2160,
    "ultrahd" to 2160,
    "4k" to 2160,
    "2160p" to 2160,
    "2k" to 1440,
    "qhd" to 1440,
    "1440p" to 1440,
    "full hd" to 1080,
    "fullhd" to 1080,
    "fhd" to 1080,
    "1080p" to 1080,
    "1080i" to 1080,
    "hd" to 720,
    "720p" to 720,
    "576p" to 576,
    "540p" to 540,
    "hq" to 576,
    "sd" to 576,
    "480p" to 480,
    "360p" to 360,
    "240p" to 240
)

private val QUALITY_BONUS_TOKENS: List<Pair<String, Int>> = listOf(
    "dolby vision" to 120,
    "hdr10" to 80,
    "hdr" to 60,
    "remux" to 80,
    "blu ray" to 60,
    "bluray" to 60,
    "bdrip" to 40,
    "web dl" to 30,
    "web-dl" to 30,
    "webrip" to 24,
    "hevc" to 18,
    "h265" to 18,
    "x265" to 18,
    "av1" to 22
)

fun movieVariantQualityScore(title: String): Int {
    val normalized = normalizeMovieVariantTitle(title)
    var score = 0

    QUALITY_TOKENS.forEach { (token, tokenScore) ->
        if (containsToken(normalized, token)) {
            score = maxOf(score, tokenScore)
        }
    }

    QUALITY_BONUS_TOKENS.forEach { (token, bonus) ->
        if (containsToken(normalized, token)) {
            score += bonus
        }
    }

    return score
}

private fun normalizeMovieVariantTitle(value: String): String {
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return " $normalized "
}

private fun containsToken(normalizedTitle: String, token: String): Boolean {
    val normalizedToken = token.lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9]+"""), " ").trim()
    if (normalizedToken.isBlank()) return false
    return normalizedTitle.contains(" $normalizedToken ")
}
