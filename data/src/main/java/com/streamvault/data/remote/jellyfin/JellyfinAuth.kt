package com.streamvault.data.remote.jellyfin

import java.security.MessageDigest

private const val JELLYFIN_CLIENT_NAME = "StreamVault"
private const val JELLYFIN_CLIENT_VERSION = "1.0.0"

internal fun buildJellyfinAuthorizationHeader(
    serverUrl: String,
    username: String,
    accessToken: String? = null
): String {
    val deviceId = buildJellyfinDeviceId(serverUrl, username)
    return buildString {
        append("MediaBrowser Client=\"$JELLYFIN_CLIENT_NAME\", Device=\"$JELLYFIN_CLIENT_NAME\", DeviceId=\"$deviceId\", Version=\"$JELLYFIN_CLIENT_VERSION\"")
        accessToken?.takeIf { it.isNotBlank() }?.let { token ->
            append(", Token=\"$token\"")
        }
    }
}

internal fun buildJellyfinDeviceId(serverUrl: String, username: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest("$serverUrl|$username".toByteArray(Charsets.UTF_8))
    return digest.take(16).joinToString("") { byte -> "%02x".format(byte) }
}
