package com.streamvault.data.remote.stalker

import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.StalkerTransportGrant
import com.streamvault.domain.model.StalkerTransportMode
import com.streamvault.domain.model.StalkerTransportOrigin
import java.net.URI

/** Rebuilds the persisted user-approved transport policy for lazy Stalker requests. */
internal fun Provider.stalkerTransportGrantOrNull(): StalkerTransportGrant? {
    if (stalkerTransportMode != StalkerTransportMode.USER_ACCEPTED_HTTP &&
        stalkerTransportMode != StalkerTransportMode.USER_ACCEPTED_UNVERIFIED_HTTPS &&
        stalkerTransportMode != StalkerTransportMode.VERIFIED_HTTPS
    ) {
        return null
    }
    val origin = stalkerTransportOrigin.toStalkerTransportOrigin()
        ?: serverUrl.toStalkerTransportOrigin()
        ?: return null
    val pin = stalkerTlsSpkiSha256.takeIf(String::isNotBlank)
    if (stalkerTransportMode == StalkerTransportMode.USER_ACCEPTED_UNVERIFIED_HTTPS && pin == null) {
        return null
    }
    return StalkerTransportGrant(
        mode = stalkerTransportMode,
        origin = origin,
        spkiSha256 = pin,
        consentedAt = stalkerTransportConsentAt
    )
}

private fun String.toStalkerTransportOrigin(): StalkerTransportOrigin? {
    val uri = runCatching { URI(trim()) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
    val host = uri.host?.lowercase()?.takeIf(String::isNotBlank) ?: return null
    val port = when {
        uri.port != -1 -> uri.port
        scheme == "https" -> 443
        else -> 80
    }
    return StalkerTransportOrigin(scheme, host, port)
}
