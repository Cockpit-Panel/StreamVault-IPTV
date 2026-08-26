package com.streamvault.data.util

import com.streamvault.domain.util.StreamEntryUrlPolicy
import java.net.URI
import java.util.Locale

object UrlSecurityPolicy {
    private val secureRemoteSchemes = setOf("https")
    private val playlistSourceSchemes = setOf("http", "https")
    private val xtreamServerSchemes = setOf("http", "https")
    private val localSchemes = setOf("file", "content")
    // Playlist storage only ever writes file:// paths via the internal copy flow;
    // content:// URIs are not openable by SyncManagerM3uImporter (OkHttp + java.io.File only).
    private val playlistLocalSchemes = setOf("file")
    // IPTV stream/asset URLs may legitimately use plain HTTP or RTSP
    private val streamEntrySchemes = setOf("http", "https", "rtsp", "rtsps", "rtmp", "file", "content")

    fun isSecureRemoteUrl(url: String): Boolean = !containsNewlines(url) && hasAllowedScheme(url, secureRemoteSchemes)

    fun isAllowedImportedUrl(url: String): Boolean =
        !containsNewlines(url) && hasAllowedScheme(url, secureRemoteSchemes + localSchemes)

    /** Validates individual stream/asset entries inside an imported playlist. HTTP is allowed here
     *  because the majority of IPTV providers serve streams over plain HTTP. */
    fun isAllowedStreamEntryUrl(url: String): Boolean =
        StreamEntryUrlPolicy.isAllowed(url)

    fun validateXtreamServerUrl(url: String): String? {
        return validateRemoteUrl(
            url = url,
            allowedSchemes = xtreamServerSchemes,
            invalidSchemeMessage = "Xtream server URLs must use HTTP or HTTPS.",
            missingHostMessage = "Xtream server URLs must include a host.",
            userInfoMessage = "Xtream server URLs must not include embedded credentials.",
            queryFragmentMessage = "Xtream server URLs must not include query parameters or fragments.",
            allowQuery = false
        )
    }

    fun validateStalkerPortalUrl(url: String): String? {
        return validateRemoteUrl(
            url = url,
            allowedSchemes = xtreamServerSchemes,
            invalidSchemeMessage = "Portal URLs must use HTTP or HTTPS.",
            missingHostMessage = "Portal URLs must include a host.",
            userInfoMessage = "Portal URLs must not include embedded credentials.",
            queryFragmentMessage = "Portal URLs must not include query parameters or fragments.",
            allowQuery = false
        )
    }

    fun validateXtreamEpgUrl(url: String): String? {
        return validateRemoteUrl(
            url = url,
            allowedSchemes = xtreamServerSchemes,
            invalidSchemeMessage = "Xtream EPG URLs must use HTTP or HTTPS.",
            missingHostMessage = "Xtream EPG URLs must include a host.",
            userInfoMessage = "Xtream EPG URLs must not include embedded credentials.",
            queryFragmentMessage = "Xtream EPG URLs must not include URL fragments.",
            allowQuery = true
        )
    }

    fun validatePlaylistSourceUrl(url: String): String? {
        if (containsNewlines(url)) {
            return "Playlist sources must use HTTP, HTTPS, or point to a local file."
        }
        val withScheme = ensureScheme(url.trim())
        val scheme = parseScheme(withScheme) ?: return "Playlist sources must use HTTP, HTTPS, or point to a local file."
        if (scheme in playlistLocalSchemes) {
            return null
        }
        return validateRemoteUrl(
            url = withScheme,
            allowedSchemes = playlistSourceSchemes,
            invalidSchemeMessage = "Playlist sources must use HTTP, HTTPS, or point to a local file.",
            missingHostMessage = "Playlist sources must include a host.",
            userInfoMessage = "Playlist sources must not include embedded credentials in the URL authority.",
            queryFragmentMessage = "Playlist sources must not include URL fragments.",
            allowQuery = true
        )
    }

    fun validateOptionalEpgUrl(url: String): String? {
        return when {
            url.isBlank() -> null
            url.startsWith("content://") -> null  // SAF local file; validated by OS file picker
            // Allow http:// as well as https:// — many IPTV portals serve their XMLTV
            // EPG endpoint over plain HTTP on non-standard ports (same policy as playlists).
            // Bare hostnames are accepted here too and resolved before persistence.
            !containsNewlines(url) && hasAllowedScheme(ensureScheme(url.trim()), playlistSourceSchemes) -> null
            else -> "EPG URLs must use HTTP, HTTPS, or select a local file."
        }
    }

    fun sanitizeImportedAssetUrl(url: String?): String? {
        val value = url?.trim().orEmpty()
        return value.takeIf { it.isNotEmpty() && isAllowedStreamEntryUrl(it) }
    }

    private fun hasAllowedScheme(url: String, allowedSchemes: Set<String>): Boolean {
        val scheme = parseScheme(url) ?: return false
        return scheme in allowedSchemes
    }

    private fun containsNewlines(url: String): Boolean {
        // Decode up to two percent-encoding layers to catch double-encoded payloads
        // (e.g. %250A → %0A → \n). Also checks %09 (tab) which can split log lines.
        var decoded = url
        repeat(2) {
            decoded = decoded
                .replace("%0A", "\n", ignoreCase = true)
                .replace("%0D", "\r", ignoreCase = true)
                .replace("%09", "\t", ignoreCase = true)
        }
        return decoded.any { it == '\n' || it == '\r' || it == '\t' }
    }

    private fun parseScheme(url: String): String? {
        return runCatching { URI(url.trim()).scheme }
            .getOrNull()
            ?.lowercase(Locale.ROOT)
    }

    // Matches an RFC-3986 scheme prefix (e.g. http://, https://, file://, content://)
    // so schemeless input can be detected reliably rather than via a loose "://" search.
    private val SCHEME_PREFIX_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    // Prepend https:// to schemeless input so it can be parsed and host-checked.
    // resolveUrlProtocol (applied before persistence) later downgrades to http://
    // when the server doesn't actually serve HTTPS.
    private fun ensureScheme(url: String): String =
        if (SCHEME_PREFIX_REGEX.containsMatchIn(url)) url else "https://$url"

    private fun validateRemoteUrl(
        url: String,
        allowedSchemes: Set<String>,
        invalidSchemeMessage: String,
        missingHostMessage: String,
        userInfoMessage: String,
        queryFragmentMessage: String,
        allowQuery: Boolean
    ): String? {
        if (containsNewlines(url)) return invalidSchemeMessage

        val withScheme = ensureScheme(url.trim())
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return invalidSchemeMessage
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return invalidSchemeMessage
        if (scheme !in allowedSchemes) return invalidSchemeMessage
        if (uri.host.isNullOrBlank()) return missingHostMessage
        if (!uri.userInfo.isNullOrBlank()) return userInfoMessage
        if (!allowQuery && !uri.rawQuery.isNullOrBlank()) return queryFragmentMessage
        if (!uri.rawFragment.isNullOrBlank()) return queryFragmentMessage
        return null
    }
}
