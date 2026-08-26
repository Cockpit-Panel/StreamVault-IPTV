package com.streamvault.data.util

/**
 * Builds a Room FTS MATCH query string from a raw user search term.
 *
 * Each whitespace-separated token is stripped of non-word characters and
 * turned into a prefix token (`token*`), joined with whitespace.
 * Tokens shorter than 2 characters are ignored.
 *
 * Example: "star wars" → "star* wars*"
 */
internal fun String.toFtsPrefixQuery(): String? {
    val tokens = trim()
        .split(Regex("\\s+"))
        .map { token -> token.replace(Regex("[^\\p{L}\\p{N}_]"), "") }
        .filter { it.length >= 2 }

    if (tokens.isEmpty()) return null

    return tokens.joinToString(" ") { "$it*" }
}
