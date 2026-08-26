package com.streamvault.player.playback

import com.streamvault.domain.model.PlaybackTransportMode
import com.streamvault.domain.model.PlaybackTransportPolicy
import com.streamvault.domain.model.StalkerTransportOrigin
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient

fun OkHttpClient.Builder.applyUnsafeTlsBypass(): OkHttpClient.Builder {
    val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustAllManager), SecureRandom())
    }
    return sslSocketFactory(sslContext.socketFactory, trustAllManager)
        .hostnameVerifier(HostnameVerifier { _, _ -> true })
}

/**
 * Applies a user-approved Stalker transport decision to one exact playback origin.
 *
 * Unlike [applyUnsafeTlsBypass], this cannot trust another provider, host, port, or public key.
 */
fun OkHttpClient.Builder.applyPlaybackTransportPolicy(
    policy: PlaybackTransportPolicy
): OkHttpClient.Builder {
    followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(PlaybackTransportRedirectInterceptor(policy))
    if (policy.mode != PlaybackTransportMode.USER_ACCEPTED_UNVERIFIED_HTTPS) {
        return this
    }

    val expectedPin = requireNotNull(policy.spkiSha256).trim()
    val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?
        ) = Unit

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?
        ) {
            val leaf = chain?.firstOrNull()
                ?: throw CertificateException("Playback server did not present a certificate.")
            if (leaf.playbackSpkiSha256() != expectedPin) {
                throw CertificateException("Playback server public key changed.")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), SecureRandom())
    }
    return sslSocketFactory(sslContext.socketFactory, trustManager)
        .hostnameVerifier { hostname, session ->
            hostname.equals(policy.origin.host, ignoreCase = true) &&
                runCatching {
                    (session.peerCertificates.firstOrNull() as? X509Certificate)
                        ?.playbackSpkiSha256() == expectedPin
                }.getOrDefault(false)
        }
}

internal fun allowsPlaybackCrossOriginHttpRedirect(
    source: StalkerTransportOrigin,
    target: StalkerTransportOrigin,
    policy: PlaybackTransportPolicy
): Boolean =
    !samePlaybackOrigin(source, target) &&
        policy.allowCrossOriginHttpRedirects &&
        policy.mode == PlaybackTransportMode.USER_ACCEPTED_HTTP &&
        policy.origin.scheme.equals("http", ignoreCase = true) &&
        target.scheme.equals("http", ignoreCase = true)

private class PlaybackTransportRedirectInterceptor(
    private val policy: PlaybackTransportPolicy
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        var request = chain.request()
        var crossOriginRedirectApproved = false
        repeat(MAX_REDIRECTS + 1) { hop ->
            val sourceOrigin = request.url.toPlaybackOrigin()
            if (!samePlaybackOrigin(sourceOrigin, policy.origin) &&
                !(crossOriginRedirectApproved &&
                    isAllowedCrossOriginHttpPlaybackOrigin(sourceOrigin, policy))
            ) {
                throw IOException("Playback transport approval does not cover the redirected origin.")
            }

            val response = chain.proceed(request)
            if (response.code !in REDIRECT_CODES) return response
            if (hop >= MAX_REDIRECTS) {
                response.close()
                throw IOException("Playback redirect limit exceeded.")
            }
            val target = response.header("Location")
                ?.let(request.url::resolve)
                ?: return response
            val targetOrigin = target.toPlaybackOrigin()
            val sameOrigin = samePlaybackOrigin(sourceOrigin, targetOrigin)
            val redirectSourceApproved = samePlaybackOrigin(sourceOrigin, policy.origin) ||
                crossOriginRedirectApproved
            if (!sameOrigin &&
                (!redirectSourceApproved ||
                    !allowsPlaybackCrossOriginHttpRedirect(sourceOrigin, targetOrigin, policy))
            ) {
                response.close()
                throw IOException("Playback transport approval does not cover the redirected origin.")
            }
            if (!sameOrigin) {
                crossOriginRedirectApproved = true
            }
            response.close()
            request = request.newBuilder()
                .url(target)
                .apply {
                    if (!sameOrigin) {
                        SENSITIVE_REDIRECT_HEADERS.forEach(::removeHeader)
                    }
                }
                .build()
        }
        throw IOException("Playback redirect limit exceeded.")
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
        val SENSITIVE_REDIRECT_HEADERS = listOf(
            "Authorization",
            "Cookie",
            "X-User-Agent",
            "X-Device-Id",
            "X-Signature"
        )
    }
}

private fun isAllowedCrossOriginHttpPlaybackOrigin(
    origin: StalkerTransportOrigin,
    policy: PlaybackTransportPolicy
): Boolean =
    policy.allowCrossOriginHttpRedirects &&
        policy.mode == PlaybackTransportMode.USER_ACCEPTED_HTTP &&
        origin.scheme.equals("http", ignoreCase = true)

private fun samePlaybackOrigin(
    first: StalkerTransportOrigin,
    second: StalkerTransportOrigin
): Boolean =
    first.scheme.equals(second.scheme, ignoreCase = true) &&
        first.host.equals(second.host, ignoreCase = true) &&
        first.port == second.port

private fun HttpUrl.toPlaybackOrigin(): StalkerTransportOrigin =
    StalkerTransportOrigin(
        scheme = scheme.lowercase(Locale.ROOT),
        host = host.lowercase(Locale.ROOT),
        port = port
    )

private fun X509Certificate.playbackSpkiSha256(): String =
    "sha256/" + Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
    )
