package com.streamvault.data.manager.recording

import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.remote.http.useCancellableResponse
import com.streamvault.data.remote.xtream.ResolvedStreamUrl
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.RecordingFailureCategory
import com.streamvault.domain.model.RecordingSourceType
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request

data class ResolvedRecordingSource(
    val url: String,
    val sourceType: RecordingSourceType,
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val expirationTime: Long? = null,
    val providerLabel: String? = null,
    val failureCategory: RecordingFailureCategory = RecordingFailureCategory.NONE
)

@Singleton
class RecordingSourceResolver @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val providerDao: ProviderDao,
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver
) {

    suspend fun resolveLiveSource(
        providerId: Long,
        channelId: Long,
        logicalUrl: String
    ): ResolvedRecordingSource {
        val resolved = xtreamStreamUrlResolver.resolveAndCommitMetadata(
            url = logicalUrl,
            fallbackProviderId = providerId,
            fallbackStreamId = channelId,
            fallbackContentType = ContentType.LIVE,
            // Use the direct-source CDN URL when available — many Xtream servers
            // only serve streams via their CDN URLs and return 404 on the
            // credential-based portal path.  If no direct-source is present the
            // resolver already falls back to the portal URL automatically.
            preferStableUrl = false
        ) ?: throw IOException("Recording stream URL could not be resolved.")

        val providerLabel = providerDao.getById(providerId)?.let { provider ->
            when {
                provider.type == com.streamvault.domain.model.ProviderType.XTREAM_CODES -> "${provider.name} • Xtream"
                provider.type == com.streamvault.domain.model.ProviderType.M3U -> "${provider.name} • M3U"
                provider.type == com.streamvault.domain.model.ProviderType.STALKER_PORTAL -> "${provider.name} • Stalker"
                else -> provider.name
            }
        }
        val inferred = sniffSourceType(resolved)
        return ResolvedRecordingSource(
            url = resolved.url,
            sourceType = inferred,
            headers = resolved.headers,
            userAgent = resolved.userAgent,
            expirationTime = resolved.expirationTime,
            providerLabel = providerLabel
        )
    }

    private suspend fun sniffSourceType(resolved: ResolvedStreamUrl): RecordingSourceType {
        // Prefer the container extension reported by the URL resolver (e.g. from the
        // Xtream internal token) — this avoids a network probe that many IPTV servers
        // reject with 404/416 when a Range header is present.
        resolved.containerExtension?.lowercase(Locale.ROOT)?.let { ext ->
            return when (ext) {
                "m3u8" -> RecordingSourceType.HLS
                "ts" -> RecordingSourceType.TS
                "mpd" -> RecordingSourceType.DASH
                else -> RecordingSourceType.TS
            }
        }
        val url = resolved.url.lowercase(Locale.ROOT)
        return when {
            url.contains(".mpd") || url.contains("ext=mpd") -> RecordingSourceType.DASH
            url.endsWith(".ts") || url.contains(".ts?") || url.contains("ext=ts") -> RecordingSourceType.TS
            url.endsWith(".m3u8") || url.contains(".m3u8?") || url.contains("ext=m3u8") -> RecordingSourceType.HLS
            else -> probeAdaptiveType(resolved)
        }
    }

    private suspend fun probeAdaptiveType(resolved: ResolvedStreamUrl): RecordingSourceType {
        // Try HEAD first — lighter and avoids Range-header rejections.
        val headResult = try {
            val headRequest = buildRequest(resolved, isHead = true)
            okHttpClient.newCall(headRequest).useCancellableResponse { response ->
                if (!response.isSuccessful) return@useCancellableResponse null
                val contentType = response.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
                when {
                    "application/vnd.apple.mpegurl" in contentType || "application/x-mpegurl" in contentType -> RecordingSourceType.HLS
                    "application/dash+xml" in contentType -> RecordingSourceType.DASH
                    "video/mp2t" in contentType || "video/mpeg" in contentType -> RecordingSourceType.TS
                    else -> null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            null
        }
        if (headResult != null) return headResult

        // Fall back to a small GET to inspect the body prefix.
        val request = buildRequest(resolved, isHead = false)

        val bodyPrefix = try {
            okHttpClient.newCall(request).useCancellableResponse { response ->
                if (!response.isSuccessful) return@useCancellableResponse ""
                val contentType = response.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
                when {
                    "application/vnd.apple.mpegurl" in contentType || "application/x-mpegurl" in contentType -> return RecordingSourceType.HLS
                    "application/dash+xml" in contentType -> return RecordingSourceType.DASH
                }
                response.body?.string().orEmpty().take(1024)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            ""
        }

        return when {
            bodyPrefix.contains("#EXTM3U", ignoreCase = true) -> RecordingSourceType.HLS
            bodyPrefix.contains("<MPD", ignoreCase = true) -> RecordingSourceType.DASH
            bodyPrefix.isEmpty() -> RecordingSourceType.UNKNOWN
            else -> RecordingSourceType.TS
        }
    }

    private fun buildRequest(resolved: ResolvedStreamUrl, isHead: Boolean): Request {
        return Request.Builder()
            .url(resolved.url)
            .apply {
                resolved.userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
                resolved.headers.forEach { (name, value) ->
                    header(name, value)
                }
                if (isHead) {
                    head()
                } else {
                    get()
                }
            }
            .build()
    }
}
