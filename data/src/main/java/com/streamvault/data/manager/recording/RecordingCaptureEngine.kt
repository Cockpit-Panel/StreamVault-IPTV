package com.streamvault.data.manager.recording

import android.content.ContentResolver
import com.streamvault.data.remote.http.useCancellableResponse
import com.streamvault.domain.model.RecordingFailureCategory
import com.streamvault.domain.model.RecordingSourceType
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request

data class CaptureProgress(
    val bytesWritten: Long,
    val averageThroughputBytesPerSecond: Long,
    val lastProgressAtMs: Long,
    val retryCount: Int = 0
)

private const val MAX_TRANSIENT_RETRIES = 3
private const val RETRY_BACKOFF_BASE_MS = 15_000L
private const val STALL_TIMEOUT_MS = 60_000L

private fun isTransientFailure(error: Throwable): Boolean {
    val msg = error.message.orEmpty().lowercase(Locale.ROOT)
    return error is java.net.SocketTimeoutException ||
        error is java.net.SocketException ||
        error is java.net.ConnectException ||
        error is java.net.UnknownHostException ||
        (error is IOException && ("timeout" in msg || "reset" in msg || "broken pipe" in msg))
}

interface RecordingCaptureEngine {
    val sourceType: RecordingSourceType

    suspend fun capture(
        source: ResolvedRecordingSource,
        outputTarget: RecordingOutputTarget,
        contentResolver: ContentResolver,
        scheduledEndMs: Long,
        onProgress: suspend (CaptureProgress) -> Unit,
        maxVideoHeight: Int? = null
    )
}

@Singleton
class TsPassThroughCaptureEngine @Inject constructor(
    private val okHttpClient: OkHttpClient
) : RecordingCaptureEngine {
    override val sourceType: RecordingSourceType = RecordingSourceType.TS

    override suspend fun capture(
        source: ResolvedRecordingSource,
        outputTarget: RecordingOutputTarget,
        contentResolver: ContentResolver,
        scheduledEndMs: Long,
        onProgress: suspend (CaptureProgress) -> Unit,
        maxVideoHeight: Int?
    ) {
        val startMs = System.currentTimeMillis()
        var bytesWritten = 0L
        var retryCount = 0
        val output = outputTarget.openOutputStream(contentResolver, append = false)
            ?: throw IOException("Could not open recording output target")
        output.use { sink ->
            while (kotlinx.coroutines.currentCoroutineContext().isActive && System.currentTimeMillis() < scheduledEndMs) {
                try {
                    val request = Request.Builder().url(source.url).apply {
                        source.userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
                        source.headers.forEach { (key, value) -> header(key, value) }
                    }.build()
                    okHttpClient.newCall(request).useCancellableResponse { response ->
                        if (!response.isSuccessful) throw IOException("Recording stream failed with HTTP ${response.code}")
                        val body = response.body ?: throw IOException("Recording stream returned an empty body")
                        body.byteStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var lastDataAt = System.currentTimeMillis()
                            while (true) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                if (scheduledEndMs <= System.currentTimeMillis()) break
                                if (System.currentTimeMillis() - lastDataAt > STALL_TIMEOUT_MS) {
                                    throw IOException("Recording stream stalled — no data received for ${STALL_TIMEOUT_MS / 1000}s")
                                }
                                val read = input.read(buffer)
                                if (read <= 0) break
                                lastDataAt = System.currentTimeMillis()
                                sink.write(buffer, 0, read)
                                bytesWritten += read
                                val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                                onProgress(
                                    CaptureProgress(
                                        bytesWritten = bytesWritten,
                                        averageThroughputBytesPerSecond = (bytesWritten * 1000L) / elapsedMs,
                                        lastProgressAtMs = System.currentTimeMillis(),
                                        retryCount = retryCount
                                    )
                                )
                            }
                            sink.flush()
                        }
                    }
                    break // Normal completion
                } catch (e: Throwable) {
                    if (!isTransientFailure(e) || retryCount >= MAX_TRANSIENT_RETRIES) throw e
                    retryCount++
                    val backoff = RETRY_BACKOFF_BASE_MS * (1L shl (retryCount - 1).coerceAtMost(3))
                    delay(backoff.coerceAtMost(scheduledEndMs - System.currentTimeMillis()).coerceAtLeast(0))
                }
            }
        }
    }
}

@Singleton
class HlsLiveCaptureEngine @Inject constructor(
    private val okHttpClient: OkHttpClient
) : RecordingCaptureEngine {
    override val sourceType: RecordingSourceType = RecordingSourceType.HLS

    override suspend fun capture(
        source: ResolvedRecordingSource,
        outputTarget: RecordingOutputTarget,
        contentResolver: ContentResolver,
        scheduledEndMs: Long,
        onProgress: suspend (CaptureProgress) -> Unit,
        maxVideoHeight: Int?
    ) {
        val startMs = System.currentTimeMillis()
        var bytesWritten = 0L
        var retryCount = 0
        val headers = buildMap {
            putAll(source.headers)
            source.userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        }
        val output = outputTarget.openOutputStream(contentResolver)
            ?: throw IOException("Could not open recording output target")
        output.use { sink ->
            var currentPlaylistUrl = source.url
            val maxSeenSegments = 10_000
            val seenSegments = object : LinkedHashSet<String>() {
                override fun add(element: String): Boolean {
                    val added = super.add(element)
                    if (size > maxSeenSegments) remove(first())
                    return added
                }
            }
            var lastDataAt = System.currentTimeMillis()
            var consecutiveEmptyPlaylists = 0
            while (kotlinx.coroutines.currentCoroutineContext().isActive && System.currentTimeMillis() < scheduledEndMs) {
                if (System.currentTimeMillis() - lastDataAt > STALL_TIMEOUT_MS) {
                    throw IOException("HLS recording stalled — no new segments for ${STALL_TIMEOUT_MS / 1000}s")
                }
                val playlistText = try {
                    fetchText(currentPlaylistUrl, headers)
                } catch (e: Throwable) {
                    if (!isTransientFailure(e) || retryCount >= MAX_TRANSIENT_RETRIES) throw e
                    retryCount++
                    delay(RETRY_BACKOFF_BASE_MS * (1L shl (retryCount - 1).coerceAtMost(3)))
                    continue
                }
                val playlist = parsePlaylist(currentPlaylistUrl, playlistText, maxVideoHeight)
                when (playlist) {
                    is ParsedHlsPlaylist.Master -> {
                        currentPlaylistUrl = playlist.bestVariantUrl
                    }
                    is ParsedHlsPlaylist.Media -> {
                        // Validate that all encryption methods are supported
                        playlist.segments.mapNotNull { it.key }
                            .distinctBy { it.uri }
                            .forEach { key ->
                                if (!key.method.equals("NONE", ignoreCase = true) &&
                                    !key.method.equals("AES-128", ignoreCase = true)
                                ) {
                                    throw UnsupportedRecordingException(
                                        "This HLS stream uses unsupported DRM or encryption.",
                                        RecordingFailureCategory.DRM_UNSUPPORTED
                                    )
                                }
                            }
                        // Cache key bytes by URI so rotated keys are fetched once each
                        val keyCache = mutableMapOf<String, ByteArray>()
                        var newSegmentsThisRound = false
                        playlist.segments.forEach { segment ->
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            if (segment.uri in seenSegments) return@forEach
                            val bytes = try {
                                fetchBytes(segment.uri, headers)
                            } catch (e: Throwable) {
                                if (!isTransientFailure(e) || retryCount >= MAX_TRANSIENT_RETRIES) throw e
                                retryCount++
                                return@forEach // Skip this segment, retry on next playlist refresh
                            }
                            val segKey = segment.key?.takeIf { it.method.equals("AES-128", ignoreCase = true) }
                            val payload = if (segKey != null) {
                                val keyBytes = keyCache[segKey.uri] ?: fetchBytes(segKey.uri, headers).also {
                                    keyCache[segKey.uri] = it
                                }
                                decryptAes128(bytes, keyBytes, segKey.iv, segment.mediaSequenceNumber)
                            } else {
                                bytes
                            }
                            sink.write(payload)
                            seenSegments += segment.uri
                            bytesWritten += payload.size
                            newSegmentsThisRound = true
                            lastDataAt = System.currentTimeMillis()
                            val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                            onProgress(
                                CaptureProgress(
                                    bytesWritten = bytesWritten,
                                    averageThroughputBytesPerSecond = (bytesWritten * 1000L) / elapsedMs,
                                    lastProgressAtMs = System.currentTimeMillis(),
                                    retryCount = retryCount
                                )
                            )
                            if (System.currentTimeMillis() >= scheduledEndMs) return@forEach
                        }
                        sink.flush()
                        if (newSegmentsThisRound) retryCount = 0
                        if (playlist.endList) break
                        delay((playlist.targetDurationSeconds.coerceAtLeast(2) * 1000L) / 2L)
                    }
                }
            }
        }
    }

    private suspend fun fetchText(url: String, headers: Map<String, String>): String {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (key, value) -> header(key, value) }
        }.build()
        okHttpClient.newCall(request).useCancellableResponse { response ->
            if (!response.isSuccessful) throw IOException("Recording stream failed with HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private suspend fun fetchBytes(url: String, headers: Map<String, String>): ByteArray {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (key, value) -> header(key, value) }
        }.build()
        okHttpClient.newCall(request).useCancellableResponse { response ->
            if (!response.isSuccessful) throw IOException("Recording stream failed with HTTP ${response.code}")
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun decryptAes128(payload: ByteArray, keyBytes: ByteArray, ivHex: String?, mediaSequenceNumber: Long): ByteArray {
        val iv = if (ivHex != null) {
            ivHex.removePrefix("0x")
                .chunked(2)
                .mapNotNull { it.toIntOrNull(16)?.toByte() }
                .toByteArray()
                .takeIf { it.size == 16 }
                ?: throw IOException("Malformed AES-128 IV in HLS key: $ivHex")
        } else {
            // Per RFC 8216 §5.2: use media sequence number as big-endian 128-bit integer
            java.nio.ByteBuffer.allocate(16).apply {
                position(8)
                putLong(mediaSequenceNumber)
            }.array()
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(payload)
    }

    private fun parsePlaylist(baseUrl: String, rawText: String, maxVideoHeight: Int? = null): ParsedHlsPlaylist {
        val lines = rawText.lineSequence().map(String::trim).filter { it.isNotEmpty() }.toList()
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }) {
            data class HlsVariant(val bandwidth: Int, val height: Int?, val url: String)
            val variants = mutableListOf<HlsVariant>()
            lines.forEachIndexed { index, line ->
                if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                    val bandwidth = Regex("""BANDWIDTH=(\d+)""")
                        .find(line)
                        ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    val height = Regex("""RESOLUTION=\d+x(\d+)""")
                        .find(line)
                        ?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val next = lines.getOrNull(index + 1)?.takeIf { !it.startsWith("#") } ?: return@forEachIndexed
                    variants += HlsVariant(bandwidth, height, resolveRelativeUrl(baseUrl, next))
                }
            }
            val eligible = if (maxVideoHeight != null) {
                variants.filter { it.height == null || it.height <= maxVideoHeight }.ifEmpty { variants }
            } else {
                variants
            }
            val bestVariantUrl = eligible.maxByOrNull { it.bandwidth }?.url
                ?: throw IOException("No playable HLS variants were available.")
            return ParsedHlsPlaylist.Master(bestVariantUrl)
        }

        var targetDuration = 6
        var endList = false
        var currentKey: HlsKey? = null
        var mediaSequence = 0L
        val segments = mutableListOf<HlsSegment>()
        var segmentIndex = 0L
        lines.forEach { line ->
            when {
                line.startsWith("#EXT-X-TARGETDURATION", ignoreCase = true) -> {
                    targetDuration = line.substringAfter(':', "6").toIntOrNull() ?: 6
                }
                line.startsWith("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true) -> {
                    mediaSequence = line.substringAfter(':', "0").toLongOrNull() ?: 0L
                    segmentIndex = 0L
                }
                line.startsWith("#EXT-X-ENDLIST", ignoreCase = true) -> {
                    endList = true
                }
                line.startsWith("#EXT-X-KEY", ignoreCase = true) -> {
                    val attrs = parseHlsAttributes(line.substringAfter(':'))
                    currentKey = HlsKey(
                        method = attrs["METHOD"].orEmpty(),
                        uri = attrs["URI"]?.let { resolveRelativeUrl(baseUrl, it) }.orEmpty(),
                        iv = attrs["IV"]
                    )
                }
                line.startsWith("#") -> Unit
                else -> {
                    segments += HlsSegment(
                        uri = resolveRelativeUrl(baseUrl, line),
                        key = currentKey?.copy(),
                        mediaSequenceNumber = mediaSequence + segmentIndex
                    )
                    segmentIndex++
                }
            }
        }
        return ParsedHlsPlaylist.Media(
            targetDurationSeconds = targetDuration,
            endList = endList,
            segments = segments
        )
    }

    private fun parseHlsAttributes(raw: String): Map<String, String> {
        val attrs = mutableMapOf<String, String>()
        val parts = raw.split(',')
        parts.forEach { part ->
            val key = part.substringBefore('=').trim()
            val value = part.substringAfter('=', "").trim().removeSurrounding("\"")
            if (key.isNotBlank()) {
                attrs[key.uppercase(Locale.ROOT)] = value
            }
        }
        return attrs
    }

    private fun resolveRelativeUrl(baseUrl: String, value: String): String {
        return runCatching { URI(baseUrl).resolve(value).toString() }.getOrDefault(value)
    }
}

private sealed interface ParsedHlsPlaylist {
    data class Master(val bestVariantUrl: String) : ParsedHlsPlaylist
    data class Media(
        val targetDurationSeconds: Int,
        val endList: Boolean,
        val segments: List<HlsSegment>
    ) : ParsedHlsPlaylist
}

private data class HlsSegment(
    val uri: String,
    val key: HlsKey? = null,
    val mediaSequenceNumber: Long = 0
)

private data class HlsKey(
    val method: String,
    val uri: String,
    val iv: String? = null
)

class UnsupportedRecordingException(
    message: String,
    val category: RecordingFailureCategory
) : IOException(message)
