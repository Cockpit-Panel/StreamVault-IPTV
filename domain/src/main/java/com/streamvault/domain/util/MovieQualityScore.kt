package com.streamvault.domain.util

fun movieVariantQualityScore(name: String): Int {
    val lower = name.lowercase()
    return when {
        lower.contains("4k") || lower.contains("2160") -> 4
        lower.contains("1080") || lower.contains("fhd") -> 3
        lower.contains("720") || lower.contains("hd") -> 2
        lower.contains("480") || lower.contains("sd") -> 1
        else -> 0
    }
}
