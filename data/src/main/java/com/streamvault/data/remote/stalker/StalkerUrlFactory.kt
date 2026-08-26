package com.streamvault.data.remote.stalker

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

enum class StalkerStreamKind(val pathSegment: String) {
    LIVE("live"),
    ARCHIVE("archive"),
    MOVIE("movie"),
    EPISODE("episode")
}

data class StalkerStreamToken(
    val providerId: Long,
    val kind: StalkerStreamKind,
    val itemId: Long,
    val cmd: String,
    val containerExtension: String? = null,
    val seriesNumber: Int? = null,
    val archiveStartSeconds: Long? = null,
    val archiveEndSeconds: Long? = null,
    val playbackDescriptor: StalkerPlaybackDescriptor? = null
)

object StalkerUrlFactory {
    private const val INTERNAL_SCHEME = "stalker"

    fun normalizePortalUrl(url: String): String =
        url.trim()
            .substringBefore('#')
            .substringBefore('?')
            .trimEnd('/')

    fun loadUrlCandidates(portalUrl: String): List<String> {
        val normalized = normalizePortalUrl(portalUrl)
        val direct = normalized.lowercase(Locale.ROOT)
        val candidates = linkedSetOf<String>()
        when {
            direct.endsWith("/server/load.php") || direct.endsWith("/portal.php") -> {
                candidates += normalized
            }

            direct.endsWith("/c/index.html") || direct.endsWith("/c/index.htm") -> {
                val suffix = if (direct.endsWith("/c/index.html")) "/c/index.html" else "/c/index.htm"
                val base = normalized.dropLast(suffix.length)
                candidates += "$base/server/load.php"
                candidates += "$base/portal.php"
            }

            direct.endsWith("/c") -> {
                val base = normalized.removeSuffix("/c")
                candidates += "$base/server/load.php"
                candidates += "$base/portal.php"
            }

            else -> {
                // Prefer the modern Ministra API family (server/load.php) across the known
                // install locations before falling back to the legacy portal.php endpoints.
                candidates += "$normalized/server/load.php"
                candidates += "${normalized.trimEnd('/')}/stalker_portal/server/load.php"
                candidates += "$normalized/portal.php"
                candidates += "${normalized.trimEnd('/')}/stalker_portal/portal.php"
            }
        }
        return candidates.toList()
    }

    /**
     * True when the user supplied only a portal base (host or custom path) rather than an
     * explicit API script (`server/load.php`, `portal.php`) or launcher client path (`/c…`).
     * Only bare bases benefit from probing `/` for a redirect that reveals the install path.
     */
    fun isBarePortalBase(portalUrl: String): Boolean {
        val direct = normalizePortalUrl(portalUrl).lowercase(Locale.ROOT)
        return !direct.endsWith("/server/load.php") &&
            !direct.endsWith("/portal.php") &&
            !direct.endsWith("/c") &&
            !direct.endsWith("/c/index.html") &&
            !direct.endsWith("/c/index.htm")
    }

    fun portalReferer(loadUrl: String): String {
        val normalized = normalizePortalUrl(loadUrl)
        return when {
            normalized.lowercase(Locale.ROOT).endsWith("/server/load.php") ->
                normalized.removeSuffix("/server/load.php") + "/c/"
            normalized.lowercase(Locale.ROOT).endsWith("/portal.php") ->
                normalized.removeSuffix("/portal.php") + "/c/"
            else -> normalized.trimEnd('/') + "/c/"
        }
    }

    fun buildInternalStreamUrl(
        providerId: Long,
        kind: StalkerStreamKind,
        itemId: Long,
        cmd: String,
        containerExtension: String? = null,
        seriesNumber: Int? = null,
        archiveStartSeconds: Long? = null,
        archiveEndSeconds: Long? = null,
        playbackDescriptor: StalkerPlaybackDescriptor? = null
    ): String {
        val resolvedDescriptor = playbackDescriptor?.copy(
            candidates = orderStalkerCommandVariants(playbackDescriptor.candidates)
        )
        val primaryCmd = resolvedDescriptor?.candidates?.firstOrNull()?.cmd ?: cmd
        val query = buildList {
            add("cmd=${encode(primaryCmd)}")
            containerExtension?.trim()
                ?.removePrefix(".")
                ?.takeIf { it.isNotBlank() }
                ?.let { ext -> add("ext=${encode(ext.lowercase(Locale.ROOT))}") }
            seriesNumber?.takeIf { it > 0 }
                ?.let { episode -> add("series=${encode(episode.toString())}") }
            archiveStartSeconds?.takeIf { it > 0L }
                ?.let { start -> add("utc=${encode(start.toString())}") }
            archiveEndSeconds?.takeIf { it > 0L }
                ?.let { end -> add("lutc=${encode(end.toString())}") }
            resolvedDescriptor?.let { descriptor ->
                add("mode=${encode(descriptor.candidates.firstOrNull()?.playbackMode?.name ?: descriptor.primaryMode.name)}")
                add("pm=${encode(descriptor.primaryMode.name)}")
                encodePortalCapabilities(descriptor.capabilities)
                    ?.let { encoded -> add("caps=${encode(encoded)}") }
                encodeCommandVariants(descriptor.candidates.drop(1))
                    ?.let { encoded -> add("alts=${encode(encoded)}") }
            }
        }.joinToString("&")
        return "$INTERNAL_SCHEME://$providerId/${kind.pathSegment}/$itemId?$query"
    }

    fun parseInternalStreamUrl(url: String?): StalkerStreamToken? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals(INTERNAL_SCHEME, ignoreCase = true)) return null
        val providerId = uri.authority?.toLongOrNull() ?: return null
        val pathSegments = uri.path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
        val kind = pathSegments.firstOrNull()?.let(::kindFromPathSegment) ?: return null
        val itemId = pathSegments.getOrNull(1)?.toLongOrNull() ?: return null
        val query = parseQuery(uri.rawQuery)
        val cmd = query["cmd"] ?: return null
        val ext = query["ext"]?.trim()?.takeIf { it.isNotBlank() }
        val seriesNumber = query["series"]?.toIntOrNull()?.takeIf { it > 0 }
        val archiveStartSeconds = query["utc"]?.toLongOrNull()?.takeIf { it > 0L }
        val archiveEndSeconds = query["lutc"]?.toLongOrNull()?.takeIf { it > 0L }
        val capabilities = decodePortalCapabilities(query["caps"])
        val primaryMode = query["mode"]
            ?.let { runCatching { StalkerPlaybackMode.valueOf(it) }.getOrNull() }
            ?: detectStalkerPlaybackMode(cmd, capabilities)
        val alternateVariants = decodeCommandVariants(query["alts"])
        val primaryCandidate = StalkerCommandVariant(
            cmd = cmd,
            playbackMode = primaryMode,
            sourceKey = "cmd",
            priority = 0
        )
        val orderedCandidates = orderStalkerCommandVariants(listOf(primaryCandidate) + alternateVariants)
        val playbackDescriptor = StalkerPlaybackDescriptor(
            primaryMode = query["pm"]
                ?.let { runCatching { StalkerPlaybackMode.valueOf(it) }.getOrNull() }
                ?: if (orderedCandidates.size > 1) StalkerPlaybackMode.MULTI_CMD else primaryMode,
            candidates = orderedCandidates,
            capabilities = capabilities
        )
        return StalkerStreamToken(
            providerId = providerId,
            kind = kind,
            itemId = itemId,
            cmd = cmd,
            containerExtension = ext,
            seriesNumber = seriesNumber,
            archiveStartSeconds = archiveStartSeconds,
            archiveEndSeconds = archiveEndSeconds,
            playbackDescriptor = playbackDescriptor
        )
    }

    fun isInternalStreamUrl(url: String?): Boolean = parseInternalStreamUrl(url) != null

    private fun kindFromPathSegment(segment: String): StalkerStreamKind? = when (segment.lowercase(Locale.ROOT)) {
        StalkerStreamKind.LIVE.pathSegment -> StalkerStreamKind.LIVE
        StalkerStreamKind.ARCHIVE.pathSegment -> StalkerStreamKind.ARCHIVE
        StalkerStreamKind.MOVIE.pathSegment -> StalkerStreamKind.MOVIE
        StalkerStreamKind.EPISODE.pathSegment -> StalkerStreamKind.EPISODE
        else -> null
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&")
            .mapNotNull { pair ->
                val key = pair.substringBefore("=", "")
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                decode(key) to decode(pair.substringAfter("=", ""))
            }
            .toMap()
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun decode(value: String): String =
        URLDecoder.decode(value, Charsets.UTF_8.name())

    private fun encodeCommandVariants(variants: List<StalkerCommandVariant>): String? {
        if (variants.isEmpty()) return null
        return variants.joinToString(";") { variant ->
            listOf(
                variant.sourceKey,
                variant.playbackMode.name,
                Base64.getUrlEncoder().withoutPadding().encodeToString(variant.cmd.toByteArray(Charsets.UTF_8))
            ).joinToString(",")
        }
    }

    private fun decodeCommandVariants(value: String?): List<StalkerCommandVariant> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(';')
            .mapIndexedNotNull { index, encoded ->
                val parts = encoded.split(',', limit = 3)
                if (parts.size != 3) return@mapIndexedNotNull null
                val sourceKey = parts[0].trim().ifBlank { "cmd_$index" }
                val mode = runCatching { StalkerPlaybackMode.valueOf(parts[1]) }.getOrNull() ?: return@mapIndexedNotNull null
                val cmd = runCatching {
                    String(Base64.getUrlDecoder().decode(parts[2]), Charsets.UTF_8)
                }.getOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                StalkerCommandVariant(
                    cmd = cmd,
                    playbackMode = mode,
                    sourceKey = sourceKey,
                    priority = index + 1
                )
            }
    }

    private fun encodePortalCapabilities(capabilities: StalkerPortalCapabilities): String? {
        val encoded = buildList {
            if (capabilities.useHttpTemporaryLink) add("uhtl=1")
            if (capabilities.nginxSecureLink) add("nginx=1")
            if (capabilities.flussonicTemporaryLink) add("flus=1")
            if (capabilities.wowzaTemporaryLink) add("wowza=1")
            if (capabilities.useLoadBalancing) add("lb=1")
            if (capabilities.allowLocalTimeshift) add("lts=1")
            if (capabilities.allowLocalPvr) add("lpvr=1")
            if (capabilities.allowRemotePvr) add("rpvr=1")
            if (capabilities.archiveAvailable) add("arch=1")
            if (capabilities.moduleRestricted) add("mods=1")
            if (capabilities.ambiguousAccountState) add("amb=1")
            if (capabilities.bootstrapStrategy != StalkerBootstrapStrategy.AUTO) {
                add("boot=${capabilities.bootstrapStrategy.name}")
            }
        }.joinToString(",")
        return encoded.takeIf { it.isNotBlank() }
    }

    private fun decodePortalCapabilities(value: String?): StalkerPortalCapabilities {
        if (value.isNullOrBlank()) return StalkerPortalCapabilities()
        val parts = value.split(',').mapNotNull { token ->
            val key = token.substringBefore('=').trim()
            val rawValue = token.substringAfter('=', missingDelimiterValue = "").trim()
            key.takeIf { it.isNotBlank() }?.let { it to rawValue }
        }.toMap()
        return StalkerPortalCapabilities(
            bootstrapStrategy = parts["boot"]
                ?.let { runCatching { StalkerBootstrapStrategy.valueOf(it) }.getOrNull() }
                ?: StalkerBootstrapStrategy.AUTO,
            useHttpTemporaryLink = parts["uhtl"] == "1",
            nginxSecureLink = parts["nginx"] == "1",
            flussonicTemporaryLink = parts["flus"] == "1",
            wowzaTemporaryLink = parts["wowza"] == "1",
            useLoadBalancing = parts["lb"] == "1",
            allowLocalTimeshift = parts["lts"] == "1",
            allowLocalPvr = parts["lpvr"] == "1",
            allowRemotePvr = parts["rpvr"] == "1",
            archiveAvailable = parts["arch"] == "1",
            moduleRestricted = parts["mods"] == "1",
            ambiguousAccountState = parts["amb"] == "1"
        )
    }
}
