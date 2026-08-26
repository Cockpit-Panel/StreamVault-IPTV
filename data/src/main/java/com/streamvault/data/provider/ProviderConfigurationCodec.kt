package com.streamvault.data.provider

import com.google.gson.Gson
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.model.JellyfinConfig
import com.streamvault.domain.model.M3uConfig
import com.streamvault.domain.model.ProviderConfiguration
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.StalkerConfig
import com.streamvault.domain.model.XtreamConfig
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps secret handling at the data boundary; plaintext credentials never enter Room JSON. */
@Singleton
class ProviderConfigurationCodec @Inject constructor(
    private val gson: Gson,
    private val credentialCrypto: CredentialCrypto
) {
    fun encode(configuration: ProviderConfiguration): String = gson.toJson(configuration.encryptSecrets())

    fun decode(type: ProviderType, payload: String): ProviderConfiguration {
        val encrypted = when (type) {
            ProviderType.XTREAM_CODES -> gson.fromJson(payload, XtreamConfig::class.java)
            ProviderType.M3U -> gson.fromJson(payload, M3uConfig::class.java)
            ProviderType.STALKER_PORTAL -> gson.fromJson(payload, StalkerConfig::class.java)
            ProviderType.JELLYFIN -> gson.fromJson(payload, JellyfinConfig::class.java)
        } ?: throw IllegalArgumentException("Provider configuration payload is empty")
        require(encrypted.type == type) { "Configuration payload does not match stored provider type" }
        return encrypted.decryptSecrets()
    }

    fun identityKey(configuration: ProviderConfiguration): String {
        val canonical = when (configuration) {
            is XtreamConfig -> listOf(configuration.type.name, normalizeOrigin(configuration.serverUrl), configuration.username.trim())
            is M3uConfig -> listOf(configuration.type.name, normalizeUrl(configuration.playlistUrl))
            is StalkerConfig -> listOf(
                configuration.type.name,
                normalizeOrigin(configuration.portalUrl),
                configuration.device.macAddress.trim().uppercase(),
                configuration.username.trim()
            )
            is JellyfinConfig -> listOf(configuration.type.name, normalizeOrigin(configuration.serverUrl), configuration.username.trim())
        }.joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun ProviderConfiguration.encryptSecrets(): ProviderConfiguration = when (this) {
        is XtreamConfig -> copy(password = credentialCrypto.encryptIfNeeded(password))
        is M3uConfig -> this
        is StalkerConfig -> copy(password = credentialCrypto.encryptIfNeeded(password))
        is JellyfinConfig -> copy(credential = credentialCrypto.encryptIfNeeded(credential))
    }

    private fun ProviderConfiguration.decryptSecrets(): ProviderConfiguration = when (this) {
        is XtreamConfig -> copy(password = credentialCrypto.decryptIfNeeded(password))
        is M3uConfig -> this
        is StalkerConfig -> copy(password = credentialCrypto.decryptIfNeeded(password))
        is JellyfinConfig -> copy(credential = credentialCrypto.decryptIfNeeded(credential))
    }

    private fun normalizeOrigin(value: String): String = runCatching {
        val uri = URI(value.trim())
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        val port = when {
            uri.port >= 0 -> uri.port
            scheme == "https" -> 443
            scheme == "http" -> 80
            else -> -1
        }
        "$scheme://$host:$port${uri.path.orEmpty().trimEnd('/')}"
    }.getOrElse { value.trim().trimEnd('/').lowercase() }

    private fun normalizeUrl(value: String): String = value.trim()
}
