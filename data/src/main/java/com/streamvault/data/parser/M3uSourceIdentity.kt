package com.streamvault.data.parser

import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.MovieEntity
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** Stable, destination-independent identity for an M3U entry. */
internal object M3uSourceIdentity {
    fun fromEntry(providerId: Long, entry: M3uParser.M3uEntry): String =
        hash(providerId, entry.tvgId ?: entry.tvgName, entry.url, entry.name)

    fun stableLongId(providerId: Long, entry: M3uParser.M3uEntry): Long =
        stableLong(providerId, entry.tvgId ?: entry.tvgName, entry.url, entry.name)

    fun fromChannel(channel: ChannelEntity): String =
        hash(channel.providerId, channel.epgChannelId, channel.streamUrl, channel.name)

    fun fromMovie(movie: MovieEntity): String =
        hash(movie.providerId, null, movie.streamUrl, movie.name)

    fun groupKey(groupTitle: String?): String = normalize(groupTitle.orEmpty())

    private fun hash(providerId: Long, externalId: String?, url: String, title: String): String {
        val identity = identity(providerId, externalId, url, title)
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
    }

    private fun stableLong(providerId: Long, externalId: String?, url: String, title: String): Long {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity(providerId, externalId, url, title).toByteArray(Charsets.UTF_8))
        var result = 0L
        repeat(8) { index -> result = (result shl 8) or (digest[index].toLong() and 0xff) }
        return (result and Long.MAX_VALUE).coerceAtLeast(1L)
    }

    private fun identity(providerId: Long, externalId: String?, url: String, title: String): String {
        val normalizedExternalId = externalId?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return if (normalizedExternalId.isNotBlank()) {
            // tvg-id/tvg-name is the provider's durable identity when supplied. Titles and
            // signed URL query parameters commonly change during a playlist refresh.
            "$providerId|external=$normalizedExternalId"
        } else {
            "$providerId|url=${canonicalUrl(url)}|title=${normalize(title)}"
        }
    }

    private fun canonicalUrl(url: String): String = runCatching {
        val parsed = URI(url)
        buildString {
            append(parsed.scheme?.lowercase(Locale.ROOT).orEmpty())
            append("://")
            append(parsed.host?.lowercase(Locale.ROOT).orEmpty())
            parsed.port.takeIf { it > 0 }?.let { append(':').append(it) }
            append(parsed.rawPath.orEmpty())
            // Query parameters are intentionally retained. M3U URLs frequently use the
            // query as a credential or token, so this identity is never shown to users.
            parsed.rawQuery?.let { append('?').append(it) }
        }
    }.getOrElse { url.substringBefore('#') }

    private fun normalize(value: String): String = value
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")
}
