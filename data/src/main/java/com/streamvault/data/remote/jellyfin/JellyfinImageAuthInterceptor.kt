package com.streamvault.data.remote.jellyfin

import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.ProviderSnapshotDao
import com.streamvault.data.provider.ProviderConfigurationCodec
import com.streamvault.domain.model.JellyfinConfig
import com.streamvault.domain.model.ProviderType
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor

@Singleton
class JellyfinImageAuthInterceptor @Inject constructor(
    private val providerDao: ProviderDao,
    private val providerSnapshotDao: ProviderSnapshotDao,
    private val providerConfigurationCodec: ProviderConfigurationCodec
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        // Image credentials are account-scoped.  A URL without this marker is
        // deliberately not guessed from its host: two accounts may share it.
        val marker = request.url.queryParameter("streamvault_provider_id")
            ?: return chain.proceed(request)
        // The marker is app-internal routing metadata and must never reach Jellyfin or any
        // mismatched host, even when it is malformed or the caller supplied its own auth.
        val sanitizedRequest = request.newBuilder()
            .url(request.url.newBuilder().removeAllQueryParameters("streamvault_provider_id").build())
            .build()
        if (!request.header("Authorization").isNullOrBlank()) {
            return chain.proceed(sanitizedRequest)
        }

        val providerId = marker.toLongOrNull() ?: return chain.proceed(sanitizedRequest)
        // Query the exact account on every request. Provider edits and deletes must take effect
        // immediately; a short-lived list cache can otherwise attach a revoked account token.
        val identity = runCatching { providerDao.getByIdSync(providerId) }
            .getOrNull()?.takeIf { it.type == ProviderType.JELLYFIN }
            ?: return chain.proceed(sanitizedRequest)
        val stored = providerSnapshotDao.getConfigSync(identity.id)
            ?: return chain.proceed(sanitizedRequest)
        val provider = runCatching {
            providerConfigurationCodec.decode(stored.type, stored.encryptedConfigJson) as? JellyfinConfig
        }.getOrNull() ?: return chain.proceed(sanitizedRequest)
        if (!provider.matches(request.url)) return chain.proceed(sanitizedRequest)
        val accessToken = provider.credential.takeIf { it.isNotBlank() }
            ?: return chain.proceed(sanitizedRequest)

        return chain.proceed(
            sanitizedRequest.newBuilder()
                .header(
                    "Authorization",
                    buildJellyfinAuthorizationHeader(provider.serverUrl, provider.username, accessToken)
                )
            .build()
        )
    }

    private fun JellyfinConfig.matches(url: HttpUrl): Boolean {
        val baseUrl = serverUrl.toHttpUrlOrNull() ?: return false
        if (url.scheme != baseUrl.scheme || url.host != baseUrl.host || url.port != baseUrl.port) {
            return false
        }

        val baseSegments = baseUrl.pathSegments.filter { it.isNotEmpty() }
        val requestSegments = url.pathSegments.filter { it.isNotEmpty() }
        if (requestSegments.size < baseSegments.size) {
            return false
        }

        return baseSegments.indices.all { index -> requestSegments[index] == baseSegments[index] }
    }
}
