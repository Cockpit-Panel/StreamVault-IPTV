package com.streamvault.player.timeshift

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import com.streamvault.domain.model.TimeshiftBackendPreference
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.player.playback.applyUnsafeTlsBypass
import com.streamvault.player.playback.applyPlaybackTransportPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal interface LiveTimeshiftManager {
    val state: StateFlow<LiveTimeshiftState>
    suspend fun startSession(streamInfo: StreamInfo, channelKey: String, config: TimeshiftConfig)
    suspend fun stopSession()
    suspend fun createSnapshot(): LiveTimeshiftSnapshot?
    suspend fun releaseRetiredSnapshots()
    fun detachComponentCallbacks()
    suspend fun close()
}

internal data class DashSnapshotPlaylistSegment(
    val fileName: String,
    val durationMs: Long,
    val isInit: Boolean
)

internal suspend fun stopOwnedTimeshiftCapture(
    activeCall: AtomicReference<okhttp3.Call?>,
    captureJob: Job?,
    deleteSessionFiles: () -> Unit
) {
    activeCall.get()?.cancel()
    captureJob?.cancel()
    activeCall.getAndSet(null)?.cancel()
    captureJob?.join()
    deleteSessionFiles()
}

internal fun buildDashSnapshotPlaylist(
    targetDurationSeconds: Int,
    segments: List<DashSnapshotPlaylistSegment>
): String = buildString {
    appendLine("#EXTM3U")
    appendLine("#EXT-X-VERSION:7")
    appendLine("#EXT-X-TARGETDURATION:$targetDurationSeconds")
    appendLine("#EXT-X-MEDIA-SEQUENCE:0")
    segments.firstOrNull { it.isInit }?.let { initSegment ->
        appendLine("#EXT-X-MAP:URI=\"${initSegment.fileName}\"")
    }
    segments.filterNot(DashSnapshotPlaylistSegment::isInit).forEach { segment ->
        appendLine("#EXTINF:${"%.3f".format(Locale.US, segment.durationMs / 1000.0)},")
        appendLine(segment.fileName)
    }
    appendLine("#EXT-X-ENDLIST")
}

internal class DefaultLiveTimeshiftManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) : LiveTimeshiftManager, ComponentCallbacks2 {
    private val unsafeOkHttpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .applyUnsafeTlsBypass()
            .build()
    }
    private val proxiedClients = ConcurrentHashMap<String, OkHttpClient>()

    init {
        context.registerComponentCallbacks(this)
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            scope.launch {
                mutex.withLock {
                    val session = activeSession ?: return@withLock
                    if (session.backend == LiveTimeshiftBackend.MEMORY) {
                        stopSessionLocked()
                        _state.value = _state.value.copy(
                            status = LiveTimeshiftStatus.FAILED,
                            message = "Local rewind stopped: low memory."
                        )
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit
    override fun onLowMemory() = Unit

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(LiveTimeshiftState())
    override val state: StateFlow<LiveTimeshiftState> = _state.asStateFlow()
    private val diskManager = TimeshiftDiskManager(context)

    private var activeSession: Session? = null
    private val retiredSnapshotDirs = ArrayDeque<File>()
    private var closed = false
    private val callbacksRegistered = AtomicBoolean(true)

    override suspend fun startSession(streamInfo: StreamInfo, channelKey: String, config: TimeshiftConfig) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (closed) return@withLock
                stopSessionLocked()
                if (!config.enabled) {
                    _state.value = LiveTimeshiftState(enabled = false, status = LiveTimeshiftStatus.DISABLED)
                    return@withLock
                }
                val support = determineSupport(streamInfo)
                if (!support.supported) {
                    _state.value = LiveTimeshiftState(
                        enabled = true,
                        supported = false,
                        status = LiveTimeshiftStatus.UNSUPPORTED,
                        message = support.reason
                    )
                    return@withLock
                }
                val sessionRoot = context.cacheDir?.takeIf {
                    (it.exists() || it.mkdirs()) && it.canWrite()
                } ?: run {
                    _state.value = LiveTimeshiftState(
                        enabled = true,
                        supported = false,
                        status = LiveTimeshiftStatus.FAILED,
                        message = "App storage is unavailable for local live rewind."
                    )
                    return@withLock
                }
                val sessionDir = File(
                    sessionRoot,
                    "timeshift/${channelKey.hashCode()}-${System.currentTimeMillis()}"
                ).apply { mkdirs() }

                // Crash-safe cleanup: delete all stale timeshift dirs from previous crashes/exits.
                diskManager.cleanupStaleDirectories(activeSessionDir = sessionDir)

                val backend = chooseBackend(config)
                if (backend == null) {
                    sessionDir.deleteRecursively()
                    _state.value = LiveTimeshiftState(
                        enabled = true,
                        supported = false,
                        status = LiveTimeshiftStatus.FAILED,
                        message = backendUnavailableMessage(config.backendPreference)
                    )
                    return@withLock
                }

                // Global budget guard: evict LRU stale dirs if needed, then hard-fail if still over.
                if (backend == LiveTimeshiftBackend.DISK && !diskManager.isWithinBudget()) {
                    diskManager.evictLruUntilWithinBudget(activeSessionDir = sessionDir)
                    if (!diskManager.isWithinBudget()) {
                        sessionDir.deleteRecursively()
                        _state.value = LiveTimeshiftState(
                            enabled = true,
                            supported = false,
                            status = LiveTimeshiftStatus.FAILED,
                            message = "Not enough storage for local live rewind (limit: ${diskManager.maxBudgetBytes / (1024 * 1024 * 1024)} GB)."
                        )
                        return@withLock
                    }
                }
                val session = when (support.streamType) {
                    StreamType.HLS -> HlsSession(streamInfo, config, backend, sessionDir)
                    StreamType.DASH -> DashSession(streamInfo, config, backend, sessionDir)
                    StreamType.SMOOTH_STREAMING,
                    StreamType.MPEG_TS,
                    StreamType.PROGRESSIVE,
                    StreamType.UNKNOWN -> ProgressiveSession(streamInfo, config, backend, sessionDir)
                    StreamType.RTSP -> null
                }
                if (session == null) {
                    _state.value = LiveTimeshiftState(
                        enabled = true,
                        supported = false,
                        status = LiveTimeshiftStatus.UNSUPPORTED,
                        message = "This live stream type cannot use local rewind yet."
                    )
                    return@withLock
                }
                activeSession = session
                _state.value = LiveTimeshiftState(
                    enabled = true,
                    supported = true,
                    backend = backend,
                    status = LiveTimeshiftStatus.PREPARING,
                    message = "Preparing local live rewind…"
                )
                session.job = scope.launch {
                    try {
                        session.capture()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        _state.value = _state.value.copy(
                            enabled = true,
                            supported = true,
                            backend = session.backend,
                            status = LiveTimeshiftStatus.FAILED,
                            message = t.message ?: "Local live rewind failed."
                        )
                    }
                }
            }
        }
    }

    override suspend fun stopSession() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                stopSessionLocked()
                _state.value = LiveTimeshiftState(enabled = false, status = LiveTimeshiftStatus.DISABLED)
            }
        }
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (closed) return@withLock
                closed = true
                stopSessionLocked()
                _state.value = LiveTimeshiftState(enabled = false, status = LiveTimeshiftStatus.DISABLED)
            }
            detachComponentCallbacks()
            scope.coroutineContext[Job]?.cancel()
        }
    }

    override fun detachComponentCallbacks() {
        if (callbacksRegistered.compareAndSet(true, false)) {
            context.unregisterComponentCallbacks(this)
        }
    }

    override suspend fun createSnapshot(): LiveTimeshiftSnapshot? {
        return withContext(Dispatchers.IO) {
            // Hold the outer mutex only long enough to register the old snapshot as retired
            // and grab a reference to the active session. File I/O runs outside the lock
            // so startSession/stopSession are not blocked for hundreds of milliseconds.
            val session = mutex.withLock {
                val s = activeSession ?: return@withLock null
                s.activeSnapshotDir?.let { retiredSnapshotDirs.addLast(it) }
                s
            } ?: return@withContext null
            session.createSnapshot()
        }
    }

    override suspend fun releaseRetiredSnapshots() {
        withContext(Dispatchers.IO) {
            // Grab the list under the mutex, then release it before the potentially slow
            // deleteRecursively() calls so startSession/stopSession are not blocked.
            val dirs = mutex.withLock {
                val copy = retiredSnapshotDirs.toList()
                retiredSnapshotDirs.clear()
                copy
            }
            dirs.forEach { it.deleteRecursively() }
        }
    }

    private suspend fun stopSessionLocked() {
        retiredSnapshotDirs.forEach { it.deleteRecursively() }
        retiredSnapshotDirs.clear()
        activeSession?.stop()
        activeSession = null
    }

    private fun chooseBackend(config: TimeshiftConfig): LiveTimeshiftBackend? =
        resolveLiveTimeshiftBackend(
            preference = config.backendPreference,
            snapshotStorageAvailable = isSnapshotStorageAvailableForTimeshift(),
            diskStorageAvailable = isDiskStorageAvailableForTimeshift()
        )

    private fun backendUnavailableMessage(preference: TimeshiftBackendPreference): String = when (preference) {
        TimeshiftBackendPreference.STORAGE ->
            "The storage backend is unavailable for local live rewind right now."

        TimeshiftBackendPreference.AUTOMATIC,
        TimeshiftBackendPreference.MEMORY ->
            "App storage is unavailable for local live rewind."
    }

    private fun isSnapshotStorageAvailableForTimeshift(): Boolean {
        return try {
            context.cacheDir?.takeIf { (it.exists() || it.mkdirs()) && it.canWrite() } != null
        } catch (_: Throwable) {
            false
        }
    }

    private fun isDiskStorageAvailableForTimeshift(): Boolean {
        if (!isSnapshotStorageAvailableForTimeshift()) return false
        return try {
            context.cacheDir.usableSpace >= MIN_FREE_DISK_BYTES &&
                diskManager.isWithinBudget()
        } catch (_: Throwable) {
            false
        }
    }

    private fun determineSupport(streamInfo: StreamInfo): SupportResult {
        if (streamInfo.drmInfo != null) {
            return SupportResult(false, "DRM-protected streams cannot use local rewind yet.", streamInfo.streamType)
        }
        val type = inferType(streamInfo)
        return when (type) {
            StreamType.RTSP -> SupportResult(false, "RTSP streams cannot use local rewind yet.", type)
            StreamType.SMOOTH_STREAMING -> SupportResult(false, "SmoothStreaming streams cannot use local rewind yet.", type)
            else -> SupportResult(true, null, type)
        }
    }

    private fun inferType(streamInfo: StreamInfo): StreamType {
        if (streamInfo.streamType != StreamType.UNKNOWN) return streamInfo.streamType
        val url = streamInfo.url.lowercase(Locale.ROOT)
        return when {
            url.endsWith(".m3u8") -> StreamType.HLS
            url.endsWith(".mpd") -> StreamType.DASH
            url.contains(".isml/manifest") || url.contains(".ism/manifest") || url.endsWith(".ism") || url.endsWith(".isml") ->
                StreamType.SMOOTH_STREAMING
            url.endsWith(".ts") -> StreamType.MPEG_TS
            url.startsWith("rtsp") -> StreamType.RTSP
            else -> StreamType.PROGRESSIVE
        }
    }

    private inner class SupportResult(
        val supported: Boolean,
        val reason: String?,
        val streamType: StreamType
    )

    private abstract inner class Session(
        val streamInfo: StreamInfo,
        val config: TimeshiftConfig,
        val backend: LiveTimeshiftBackend,
        val sessionDir: File
    ) {
        var job: Job? = null
        var activeSnapshotDir: File? = null
        val effectiveDepthMs: Long = config.effectiveDepthMs(backend)
        protected val sequence = AtomicLong(0L)
        protected val stateStartMs = System.currentTimeMillis()
        private val activeCall = AtomicReference<okhttp3.Call?>()

        abstract suspend fun capture()
        abstract suspend fun createSnapshot(): LiveTimeshiftSnapshot?

        open suspend fun stop() {
            stopOwnedTimeshiftCapture(activeCall, job) {
                sessionDir.deleteRecursively()
            }
        }

        protected fun makeRequest(url: String) = Request.Builder().url(url).apply {
            streamInfo.userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
            streamInfo.headers.forEach { (key, value) -> header(key, value) }
        }.build()

        protected fun trackCall(request: Request): okhttp3.Call {
            val call = httpClientFor(streamInfo).newCall(request)
            activeCall.set(call)
            if (job?.isActive != true) {
                activeCall.compareAndSet(call, null)
                call.cancel()
            }
            return call
        }

        private fun httpClientFor(streamInfo: StreamInfo): OkHttpClient {
            val proxy = streamInfo.httpProxy()
            if (proxy == null && streamInfo.playbackTransportPolicy == null) {
                return if (streamInfo.allowInvalidSsl) unsafeOkHttpClient else okHttpClient
            }
            val key = buildString {
                append(streamInfo.playbackTransportPolicy)
                append(':')
                append(streamInfo.allowInvalidSsl)
                append(':')
                append(streamInfo.proxyHost.trim())
                append(':')
                append(streamInfo.proxyPort)
            }
            return proxiedClients.computeIfAbsent(key) {
                val transportPolicy = streamInfo.playbackTransportPolicy
                val builder = when {
                    transportPolicy != null ->
                        okHttpClient.newBuilder()
                            .applyPlaybackTransportPolicy(transportPolicy)
                    streamInfo.allowInvalidSsl ->
                        okHttpClient.newBuilder().applyUnsafeTlsBypass()
                    else -> okHttpClient.newBuilder()
                }
                proxy?.let(builder::proxy)
                builder.build()
            }
        }

        private fun StreamInfo.httpProxy(): Proxy? {
            val host = proxyHost.trim().takeIf { it.isNotBlank() } ?: return null
            val port = proxyPort ?: return null
            return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
        }

        protected fun clearTrackedCall(call: okhttp3.Call) {
            activeCall.compareAndSet(call, null)
        }

        protected fun <T> executeRequest(request: Request, block: (Response) -> T): T {
            val call = trackCall(request)
            return try {
                call.execute().use(block)
            } finally {
                clearTrackedCall(call)
            }
        }

        protected fun updateWindow(windowDurationMs: Long, message: String? = null) {
            val now = System.currentTimeMillis()
            _state.value = _state.value.copy(
                enabled = true,
                supported = true,
                backend = backend,
                status = if (windowDurationMs > 0L) LiveTimeshiftStatus.LIVE else LiveTimeshiftStatus.PREPARING,
                bufferStartMs = (now - windowDurationMs).coerceAtLeast(0L),
                bufferEndMs = now,
                liveEdgePositionMs = windowDurationMs,
                bufferedDurationMs = windowDurationMs,
                currentOffsetFromLiveMs = 0L,
                message = message ?: if (windowDurationMs > 0L) "Local live rewind ready." else "Preparing local live rewind…"
            )
        }

        protected fun resolveRelativeUrl(baseUrl: String, value: String): String {
            return runCatching { URI(baseUrl).resolve(value).toString() }.getOrDefault(value)
        }

        protected fun checkDiskAndBudget() {
            val freeSpace = context.cacheDir.usableSpace
            if (freeSpace < MIN_FREE_DISK_BYTES) {
                throw IOException("Insufficient disk space for live rewind (${freeSpace / (1024 * 1024)} MB free, need 200 MB).")
            }
            if (!diskManager.isWithinBudget()) {
                throw IOException("Live rewind storage limit reached (${diskManager.maxBudgetBytes / (1024 * 1024 * 1024)} GB max).")
            }
        }
    }

    private inner class ProgressiveSession(
        streamInfo: StreamInfo,
        config: TimeshiftConfig,
        backend: LiveTimeshiftBackend,
        sessionDir: File
    ) : Session(streamInfo, config, backend, sessionDir) {

        private val chunks = ArrayDeque<ProgressiveChunk>()
        private val chunkMutex = Mutex()
        private var runningChunkDurationMs = 0L

        override suspend fun capture() {
            var retryDelay = 1_000L
            var retryCount = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                try {
                    val request = makeRequest(streamInfo.url)
                    val call = trackCall(request)
                    try {
                        call.execute().use { response ->
                            if (!response.isSuccessful) throw IOException("Timeshift stream failed with HTTP ${response.code}")
                            val input = response.body?.byteStream() ?: throw IOException("Timeshift stream returned an empty body")
                            retryDelay = 1_000L
                            retryCount = 0
                            input.use { source ->
                                var current = createChunk()
                                val buffer = ByteArray(PROGRESSIVE_READ_BUFFER_SIZE)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = source.read(buffer)
                                    if (read <= 0) break
                                    current.write(buffer, read)
                                    if (System.currentTimeMillis() - current.startedAtMs >= PROGRESSIVE_CHUNK_MS) {
                                        finalizeChunk(current)
                                        current = createChunk()
                                    }
                                }
                                if (current.bytesWritten > 0L) {
                                    finalizeChunk(current)
                                }
                            }
                        }
                    } finally {
                        clearTrackedCall(call)
                    }
                    break  // stream ended normally
                } catch (t: Throwable) {
                    currentCoroutineContext().ensureActive()
                    retryCount++
                    if (retryCount > MAX_PROGRESSIVE_RETRIES) throw t
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                }
            }
        }

        override suspend fun createSnapshot(): LiveTimeshiftSnapshot? {
            val snapshotId = sequence.incrementAndGet()
            val snapshotDir = File(sessionDir, "snapshot-$snapshotId").apply { mkdirs() }
            activeSnapshotDir = snapshotDir
            val snapshotFile = File(snapshotDir, "buffer.ts")
            val orderedChunks = chunkMutex.withLock { chunks.toList() }
            if (orderedChunks.isEmpty()) return null
            snapshotFile.outputStream().use { output ->
                orderedChunks.forEach { chunk ->
                    when {
                        chunk.file != null && chunk.file.exists() -> chunk.file.inputStream().use { it.copyTo(output) }
                        chunk.payload != null -> output.write(chunk.payload)
                    }
                }
            }
            val durationMs = orderedChunks.sumOf { it.durationMs }
            return LiveTimeshiftSnapshot(
                url = snapshotFile.toURI().toString(),
                durationMs = durationMs,
                backend = backend
            )
        }

        private fun createChunk(): ActiveProgressiveChunk {
            val id = sequence.incrementAndGet()
            val targetFile = if (backend == LiveTimeshiftBackend.DISK) {
                File(sessionDir, "chunk-$id.ts")
            } else {
                null
            }
            return ActiveProgressiveChunk(
                id = id,
                startedAtMs = System.currentTimeMillis(),
                file = targetFile
            )
        }

        private suspend fun finalizeChunk(active: ActiveProgressiveChunk) {
            val output = active.output ?: return  // never written — nothing to finalize
            if (backend == LiveTimeshiftBackend.DISK) checkDiskAndBudget()
            output.flush()
            output.close()
            val endedAtMs = System.currentTimeMillis()
            val chunk = ProgressiveChunk(
                id = active.id,
                startedAtMs = active.startedAtMs,
                endedAtMs = endedAtMs,
                durationMs = (endedAtMs - active.startedAtMs).coerceAtLeast(1L),
                file = active.file,
                payload = if (backend == LiveTimeshiftBackend.MEMORY) (output as ByteArrayOutputStream).toByteArray() else null
            )
            val windowDuration = chunkMutex.withLock {
                runningChunkDurationMs += chunk.durationMs
                chunks += chunk
                pruneProgressiveChunksLocked()
                runningChunkDurationMs
            }
            updateWindow(windowDuration)
        }

        private fun ActiveProgressiveChunk.write(buffer: ByteArray, read: Int) {
            val out = output ?: run {
                val created = if (file != null) file.outputStream().buffered() else ByteArrayOutputStream()
                output = created
                created
            }
            out.write(buffer, 0, read)
            bytesWritten += read
        }

        private fun pruneProgressiveChunksLocked() {
            while (runningChunkDurationMs > effectiveDepthMs && chunks.isNotEmpty()) {
                val removed = chunks.removeFirst()
                removed.file?.delete()
                runningChunkDurationMs -= removed.durationMs
            }
        }
    }

    private inner class HlsSession(
        streamInfo: StreamInfo,
        config: TimeshiftConfig,
        backend: LiveTimeshiftBackend,
        sessionDir: File
    ) : Session(streamInfo, config, backend, sessionDir) {

        private val segments = ArrayDeque<HlsSegmentSnapshot>()
        private val segmentMutex = Mutex()
        private var runningSegmentDurationMs = 0L
        // Track the highest media sequence number we have processed so far.
        // Segments with sequence <= lastProcessedSequence are skipped (already captured or expired).
        private var lastProcessedSequence = -1L
        // Track discontinuity sequence so ad-break / stream-restart boundaries don't
        // cause us to re-download segments whose sequence numbers reset after a discontinuity.
        private var lastDiscontinuitySequence = -1L

        override suspend fun capture() {
            var currentPlaylistUrl = streamInfo.url
            var consecutiveErrors = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                try {
                    val playlistText = fetchText(currentPlaylistUrl)
                    consecutiveErrors = 0
                    val parsed = parsePlaylist(currentPlaylistUrl, playlistText)
                    when (parsed) {
                        is ParsedHlsPlaylist.Master -> currentPlaylistUrl = parsed.bestVariantUrl
                        is ParsedHlsPlaylist.Media -> {
                            // On a discontinuity-sequence change, reset media-sequence tracking
                            // so segments from the new period are captured fresh.
                            if (parsed.discontinuitySequence != lastDiscontinuitySequence && lastDiscontinuitySequence >= 0L) {
                                lastProcessedSequence = -1L
                            }
                            lastDiscontinuitySequence = parsed.discontinuitySequence

                            parsed.segments.forEach { remoteSegment ->
                                currentCoroutineContext().ensureActive()
                                if (remoteSegment.mediaSequence <= lastProcessedSequence) return@forEach
                                lastProcessedSequence = remoteSegment.mediaSequence
                                if (backend == LiveTimeshiftBackend.DISK) checkDiskAndBudget()
                                val retained = retainHlsSegment(remoteSegment)
                                val windowDuration = segmentMutex.withLock {
                                    runningSegmentDurationMs += retained.durationMs
                                    segments += retained
                                    pruneHlsSegmentsLocked()
                                    runningSegmentDurationMs
                                }
                                updateWindow(windowDuration)
                            }
                            if (parsed.endList) break
                            delay((parsed.targetDurationSeconds.coerceAtLeast(2) * 1000L) / 2L)
                        }
                    }
                } catch (t: Throwable) {
                    currentCoroutineContext().ensureActive()
                    consecutiveErrors++
                    if (consecutiveErrors > MAX_HLS_CONSECUTIVE_ERRORS) throw t
                    delay(consecutiveErrors.coerceAtMost(5) * HLS_ERROR_RETRY_DELAY_MS)
                }
            }
        }

        override suspend fun createSnapshot(): LiveTimeshiftSnapshot? {
            val snapshotId = sequence.incrementAndGet()
            val snapshotDir = File(sessionDir, "snapshot-$snapshotId").apply { mkdirs() }
            activeSnapshotDir = snapshotDir
            val snapshotSegments = segmentMutex.withLock { segments.toList() }
            if (snapshotSegments.isEmpty()) return null
            val playlist = File(snapshotDir, "index.m3u8")
            val targetDurationSeconds = snapshotSegments.maxOf { ((it.durationMs + 999L) / 1000L).toInt().coerceAtLeast(1) }
            val body = buildString {
                appendLine("#EXTM3U")
                appendLine("#EXT-X-VERSION:3")
                appendLine("#EXT-X-TARGETDURATION:$targetDurationSeconds")
                appendLine("#EXT-X-MEDIA-SEQUENCE:0")
                snapshotSegments.forEachIndexed { index, segment ->
                    val fileName = "segment-$index.ts"
                    val outputFile = File(snapshotDir, fileName)
                    when {
                        segment.file != null && segment.file.exists() -> segment.file.copyTo(outputFile, overwrite = true)
                        segment.payload != null -> outputFile.writeBytes(segment.payload)
                    }
                    appendLine("#EXTINF:${"%.3f".format(Locale.US, segment.durationMs / 1000.0)},")
                    appendLine(fileName)
                }
                appendLine("#EXT-X-ENDLIST")
            }
            playlist.writeText(body)
            return LiveTimeshiftSnapshot(
                url = playlist.toURI().toString(),
                durationMs = snapshotSegments.sumOf { it.durationMs },
                backend = backend
            )
        }

        private fun retainHlsSegment(remote: RemoteHlsSegment): HlsSegmentSnapshot {
            val id = sequence.incrementAndGet()
            return if (backend == LiveTimeshiftBackend.DISK) {
                val target = File(sessionDir, "segment-$id.ts")
                streamSegmentToDisk(remote.uri, target)
                HlsSegmentSnapshot(remote.uri, remote.durationMs, target, null)
            } else {
                val bytes = fetchBytes(remote.uri)
                HlsSegmentSnapshot(remote.uri, remote.durationMs, null, bytes)
            }
        }

        private fun streamSegmentToDisk(url: String, target: File) {
            executeRequest(makeRequest(url)) { response ->
                if (!response.isSuccessful) throw IOException("Timeshift segment failed with HTTP ${response.code}")
                val body = response.body ?: throw IOException("Timeshift segment returned an empty body")
                body.byteStream().use { input -> target.outputStream().use { output -> input.copyTo(output, bufferSize = PROGRESSIVE_READ_BUFFER_SIZE) } }
            }
        }

        private fun pruneHlsSegmentsLocked() {
            while (runningSegmentDurationMs > effectiveDepthMs && segments.isNotEmpty()) {
                val removed = segments.removeFirst()
                removed.file?.delete()
                runningSegmentDurationMs -= removed.durationMs
            }
        }

        private fun fetchText(url: String): String {
            return executeRequest(makeRequest(url)) { response ->
                if (!response.isSuccessful) throw IOException("Timeshift playlist failed with HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
        }

        private fun fetchBytes(url: String): ByteArray {
            return executeRequest(makeRequest(url)) { response ->
                if (!response.isSuccessful) throw IOException("Timeshift segment failed with HTTP ${response.code}")
                response.body?.bytes() ?: ByteArray(0)
            }
        }

        private fun parsePlaylist(baseUrl: String, rawText: String): ParsedHlsPlaylist {
            val lines = rawText.lineSequence().map(String::trim).filter { it.isNotEmpty() }.toList()
            if (lines.any { it.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }) {
                val variants = mutableListOf<Pair<Int, String>>()
                var pendingBandwidth = 0
                lines.forEachIndexed { index, line ->
                    if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                        pendingBandwidth = Regex("""BANDWIDTH=(\d+)""")
                            .find(line)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                            ?: 0
                        val next = lines.getOrNull(index + 1)?.takeIf { !it.startsWith("#") } ?: return@forEachIndexed
                        variants += pendingBandwidth to resolveRelativeUrl(baseUrl, next)
                    }
                }
                val bestVariantUrl = variants.sortedBy { it.first }.getOrNull(variants.size / 2)?.second
                    ?: throw IOException("No playable HLS variants were available.")
                return ParsedHlsPlaylist.Master(bestVariantUrl)
            }

            var targetDurationSeconds = 6
            var endList = false
            var mediaSequence = 0L
            var discontinuitySequence = 0L
            val segments = mutableListOf<RemoteHlsSegment>()
            var currentDurationMs = 6_000L
            var nextSequence = 0L  // assigned after we see EXT-X-MEDIA-SEQUENCE
            var sequenceInitialized = false
            lines.forEach { line ->
                when {
                    line.startsWith("#EXT-X-TARGETDURATION", ignoreCase = true) -> {
                        targetDurationSeconds = line.substringAfter(':', "6").toIntOrNull() ?: 6
                    }
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true) -> {
                        mediaSequence = line.substringAfter(':', "0").trim().toLongOrNull() ?: 0L
                        nextSequence = mediaSequence
                        sequenceInitialized = true
                    }
                    line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE", ignoreCase = true) -> {
                        discontinuitySequence = line.substringAfter(':', "0").trim().toLongOrNull() ?: 0L
                    }
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        currentDurationMs = ((line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 6.0) * 1000.0).toLong()
                    }
                    line.startsWith("#EXT-X-ENDLIST", ignoreCase = true) -> endList = true
                    line.startsWith("#") -> Unit
                    else -> {
                        if (!sequenceInitialized) { nextSequence = 0L; sequenceInitialized = true }
                        segments += RemoteHlsSegment(
                            uri = resolveRelativeUrl(baseUrl, line),
                            durationMs = currentDurationMs.coerceAtLeast(1L),
                            mediaSequence = nextSequence
                        )
                        nextSequence++
                    }
                }
            }
            return ParsedHlsPlaylist.Media(
                targetDurationSeconds = targetDurationSeconds,
                endList = endList,
                mediaSequence = mediaSequence,
                discontinuitySequence = discontinuitySequence,
                segments = segments
            )
        }
    }

    private class ProgressiveChunk(
        val id: Long,
        val startedAtMs: Long,
        val endedAtMs: Long,
        val durationMs: Long,
        val file: File?,
        val payload: ByteArray?
    )

    private class ActiveProgressiveChunk(
        val id: Long,
        val startedAtMs: Long,
        val file: File?,
        var output: java.io.OutputStream? = null,
        var bytesWritten: Long = 0L
    )

    private class HlsSegmentSnapshot(
        val remoteUrl: String,
        val durationMs: Long,
        val file: File?,
        val payload: ByteArray?
    )

    private data class RemoteHlsSegment(
        val uri: String,
        val durationMs: Long,
        val mediaSequence: Long = 0L
    )

    private sealed interface ParsedHlsPlaylist {
        data class Master(val bestVariantUrl: String) : ParsedHlsPlaylist
        data class Media(
            val targetDurationSeconds: Int,
            val endList: Boolean,
            val mediaSequence: Long,
            val discontinuitySequence: Long,
            val segments: List<RemoteHlsSegment>
        ) : ParsedHlsPlaylist
    }

    /**
     * Captures a DASH live stream by polling the MPD manifest, resolving segments via
     * SegmentTemplate+SegmentTimeline, and writing them to disk/memory. On snapshot,
     * the captured segments are re-packaged as a static HLS playlist so ExoPlayer can
     * play them the same way as HLS snapshots.
     */
    private inner class DashSession(
        streamInfo: StreamInfo,
        config: TimeshiftConfig,
        backend: LiveTimeshiftBackend,
        sessionDir: File
    ) : Session(streamInfo, config, backend, sessionDir) {

        private val segments = ArrayDeque<HlsSegmentSnapshot>()
        private val segmentMutex = Mutex()
        private val seenSegments = linkedSetOf<String>()
        private var runningSegmentDurationMs = 0L

        override suspend fun capture() {
            var consecutiveErrors = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                try {
                    val mpdText = fetchText(streamInfo.url)
                    consecutiveErrors = 0
                    val parsed = parseMpd(streamInfo.url, mpdText)

                    // Download init segment once (identified by URL; seenSegments deduplicates it).
                    parsed.initSegmentUrl?.let { initUrl ->
                        if (seenSegments.add("__init__:$initUrl")) {
                            if (backend == LiveTimeshiftBackend.DISK) checkDiskAndBudget()
                            val retained = retainSegment(RemoteHlsSegment(initUrl, 0L), isInit = true)
                            segmentMutex.withLock { segments += retained }
                        }
                    }

                    parsed.mediaSegments.forEach { remote ->
                        currentCoroutineContext().ensureActive()
                        if (!seenSegments.add(remote.uri)) return@forEach
                        if (backend == LiveTimeshiftBackend.DISK) checkDiskAndBudget()
                        val retained = retainSegment(remote, isInit = false)
                        val windowDuration = segmentMutex.withLock {
                            runningSegmentDurationMs += retained.durationMs
                            segments += retained
                            pruneSegmentsLocked()
                            runningSegmentDurationMs
                        }
                        updateWindow(windowDuration)
                    }

                    if (!parsed.isDynamic) break  // VOD / static — no need to re-poll
                    delay(parsed.minimumUpdatePeriodMs.coerceAtLeast(1_000L))
                } catch (t: Throwable) {
                    currentCoroutineContext().ensureActive()
                    consecutiveErrors++
                    if (consecutiveErrors > MAX_DASH_CONSECUTIVE_ERRORS) throw t
                    delay(consecutiveErrors.coerceAtMost(5) * DASH_ERROR_RETRY_DELAY_MS)
                }
            }
        }

        override suspend fun createSnapshot(): LiveTimeshiftSnapshot? {
            val snapshotId = sequence.incrementAndGet()
            val snapshotDir = File(sessionDir, "snapshot-$snapshotId").apply { mkdirs() }
            activeSnapshotDir = snapshotDir
            val snapshotSegments = segmentMutex.withLock { segments.toList() }
                .filter { it.durationMs > 0L }  // exclude init segment from HLS timing
            if (snapshotSegments.isEmpty()) return null

            // Re-package captured DASH segments as a static HLS playlist.
            // ExoPlayer already handles both HLS and DASH, and our snapshot infrastructure
            // is built around HLS output, so DASH snapshots follow the same pattern.
            val playlist = File(snapshotDir, "index.m3u8")
            val targetDurationSeconds = snapshotSegments.maxOf { ((it.durationMs + 999L) / 1000L).toInt().coerceAtLeast(1) }
            val all = segmentMutex.withLock { segments.toList() }  // includes init
            var mediaIndex = 0
            val playlistSegments = all.mapIndexed { index, segment ->
                val isInit = segment.durationMs == 0L
                val fileName = if (isInit) "init-$index.mp4" else "segment-${mediaIndex++}.mp4"
                val outputFile = File(snapshotDir, fileName)
                when {
                    segment.file != null && segment.file.exists() -> segment.file.copyTo(outputFile, overwrite = true)
                    segment.payload != null -> outputFile.writeBytes(segment.payload)
                }
                DashSnapshotPlaylistSegment(
                    fileName = fileName,
                    durationMs = segment.durationMs,
                    isInit = isInit
                )
            }
            val body = buildDashSnapshotPlaylist(
                targetDurationSeconds = targetDurationSeconds,
                segments = playlistSegments
            )
            playlist.writeText(body)
            return LiveTimeshiftSnapshot(
                url = playlist.toURI().toString(),
                durationMs = snapshotSegments.sumOf { it.durationMs },
                backend = backend
            )
        }

        private fun retainSegment(remote: RemoteHlsSegment, isInit: Boolean): HlsSegmentSnapshot {
            val id = sequence.incrementAndGet()
            val ext = if (isInit) "init-$id.mp4" else "segment-$id.mp4"
            return if (backend == LiveTimeshiftBackend.DISK) {
                val target = File(sessionDir, ext)
                streamSegmentToDisk(remote.uri, target)
                HlsSegmentSnapshot(remote.uri, remote.durationMs, target, null)
            } else {
                val bytes = fetchBytes(remote.uri)
                HlsSegmentSnapshot(remote.uri, remote.durationMs, null, bytes)
            }
        }

        private fun pruneSegmentsLocked() {
            while (runningSegmentDurationMs > effectiveDepthMs && segments.isNotEmpty()) {
                val candidate = segments.first()
                if (candidate.durationMs == 0L) break  // never prune the init segment
                val removed = segments.removeFirst()
                seenSegments.remove(removed.remoteUrl)
                removed.file?.delete()
                runningSegmentDurationMs -= removed.durationMs
            }
        }

        private fun streamSegmentToDisk(url: String, target: File) {
            executeRequest(makeRequest(url)) { response ->
                if (!response.isSuccessful) throw IOException("DASH segment fetch failed: HTTP ${response.code}")
                val body = response.body ?: throw IOException("DASH segment returned empty body")
                body.byteStream().use { input -> target.outputStream().use { out -> input.copyTo(out, bufferSize = PROGRESSIVE_READ_BUFFER_SIZE) } }
            }
        }

        private fun fetchText(url: String): String {
            return executeRequest(makeRequest(url)) { response ->
                if (!response.isSuccessful) throw IOException("MPD fetch failed: HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
        }

        private fun fetchBytes(url: String): ByteArray {
            return executeRequest(makeRequest(url)) { response ->
                if (!response.isSuccessful) throw IOException("DASH segment fetch failed: HTTP ${response.code}")
                response.body?.bytes() ?: ByteArray(0)
            }
        }

        /**
         * Parses a DASH MPD and extracts:
         * - Whether it is a live (dynamic) stream
         * - The minimumUpdatePeriod in ms (for live re-poll timing)
         * - The init segment URL for the selected representation
         * - The list of new media segments via SegmentTemplate + SegmentTimeline
         *
         * Selects the middle-bandwidth video Adaptation Set to balance quality vs storage,
         * consistent with the HLS variant selection policy.
         */
        private fun parseMpd(baseUrl: String, rawXml: String): ParsedDashManifest {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            val xpp = factory.newPullParser()
            xpp.setInput(rawXml.reader())

            var isDynamic = false
            var minimumUpdatePeriodMs = 5_000L
            var availabilityStartTimeMs = 0L
            var timescale = 1L
            var segmentDuration = 0L
            var startNumber = 1L
            var mediaTemplate = ""
            var initTemplate = ""
            var representationBandwidth = 0
            var representationId = ""

            // Collect all Representations from the first video AdaptationSet.
            // Structure: MPD > Period > AdaptationSet (contentType=video) > Representation
            data class RepresentationInfo(val id: String, val bandwidth: Int, val initTemplate: String, val mediaTemplate: String, val timescale: Long, val segmentDuration: Long, val startNumber: Long)
            val representations = mutableListOf<RepresentationInfo>()
            val timelineSegments = mutableListOf<Pair<Long, Long>>()  // (t, d) pairs

            var inAdaptationSet = false
            var inVideoAdaptationSet = false
            var inRepresentation = false
            var inSegmentTemplate = false
            var inSegmentTimeline = false
            var currentRepBandwidth = 0
            var currentRepId = ""
            var currentInitTemplate = ""
            var currentMediaTemplate = ""
            var currentTimescale = 1L
            var currentSegDuration = 0L
            var currentStartNumber = 1L
            var currentTimelineSegments = mutableListOf<Pair<Long, Long>>()

            var eventType = xpp.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (xpp.name) {
                        "MPD" -> {
                            isDynamic = xpp.getAttributeValue(null, "type")?.equals("dynamic", ignoreCase = true) == true
                            val mup = xpp.getAttributeValue(null, "minimumUpdatePeriod")
                            if (mup != null) minimumUpdatePeriodMs = parsePtDuration(mup)
                            val ast = xpp.getAttributeValue(null, "availabilityStartTime")
                            if (ast != null) availabilityStartTimeMs = parseIso8601ToMs(ast)
                        }
                        "AdaptationSet" -> {
                            inAdaptationSet = true
                            val contentType = xpp.getAttributeValue(null, "contentType")
                                ?: xpp.getAttributeValue(null, "mimeType") ?: ""
                            inVideoAdaptationSet = contentType.contains("video", ignoreCase = true)
                        }
                        "Representation" -> if (inVideoAdaptationSet) {
                            inRepresentation = true
                            currentRepBandwidth = xpp.getAttributeValue(null, "bandwidth")?.toIntOrNull() ?: 0
                            currentRepId = xpp.getAttributeValue(null, "id") ?: ""
                            currentInitTemplate = initTemplate
                            currentMediaTemplate = mediaTemplate
                            currentTimescale = timescale
                            currentSegDuration = segmentDuration
                            currentStartNumber = startNumber
                            currentTimelineSegments = mutableListOf()
                        }
                        "SegmentTemplate" -> {
                            inSegmentTemplate = true
                            val ts = xpp.getAttributeValue(null, "timescale")?.toLongOrNull() ?: 1L
                            val sd = xpp.getAttributeValue(null, "duration")?.toLongOrNull() ?: 0L
                            val sn = xpp.getAttributeValue(null, "startNumber")?.toLongOrNull() ?: 1L
                            val it = xpp.getAttributeValue(null, "initialization") ?: ""
                            val mt = xpp.getAttributeValue(null, "media") ?: ""
                            if (inRepresentation) {
                                currentTimescale = ts; currentSegDuration = sd; currentStartNumber = sn
                                currentInitTemplate = it; currentMediaTemplate = mt
                            } else {
                                timescale = ts; segmentDuration = sd; startNumber = sn
                                initTemplate = it; mediaTemplate = mt
                            }
                        }
                        "SegmentTimeline" -> inSegmentTimeline = true
                        "S" -> if (inSegmentTimeline) {
                            val t = xpp.getAttributeValue(null, "t")?.toLongOrNull()
                                ?: (currentTimelineSegments.lastOrNull()?.let { it.first + it.second } ?: 0L)
                            val d = xpp.getAttributeValue(null, "d")?.toLongOrNull() ?: 0L
                            val r = xpp.getAttributeValue(null, "r")?.toIntOrNull() ?: 0
                            var tCurrent = t
                            repeat(r + 1) {
                                currentTimelineSegments += tCurrent to d
                                tCurrent += d
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> when (xpp.name) {
                        "Representation" -> if (inVideoAdaptationSet && inRepresentation) {
                            representations += RepresentationInfo(
                                id = currentRepId,
                                bandwidth = currentRepBandwidth,
                                initTemplate = currentInitTemplate,
                                mediaTemplate = currentMediaTemplate,
                                timescale = currentTimescale,
                                segmentDuration = currentSegDuration,
                                startNumber = currentStartNumber
                            )
                            if (currentTimelineSegments.isNotEmpty()) {
                                timelineSegments.clear()
                                timelineSegments += currentTimelineSegments
                            }
                            inRepresentation = false
                        }
                        "AdaptationSet" -> { inAdaptationSet = false; inVideoAdaptationSet = false }
                        "SegmentTemplate" -> inSegmentTemplate = false
                        "SegmentTimeline" -> inSegmentTimeline = false
                    }
                }
                eventType = xpp.next()
            }

            // Pick the median-bandwidth representation (same policy as HLS variant selection).
            val rep = representations.sortedBy { it.bandwidth }.getOrNull(representations.size / 2)
                ?: throw IOException("No video representations found in MPD.")

            val resolvedInit: String? = rep.initTemplate.takeIf { it.isNotEmpty() }
                ?.replace("\$RepresentationID\$", rep.id)
                ?.replace("\$Bandwidth\$", rep.bandwidth.toString())
                ?.let { resolveRelativeUrl(baseUrl, it) }

            val ts = rep.timescale.takeIf { it > 0L } ?: 1L
            val mediaSegments: List<RemoteHlsSegment> = if (timelineSegments.isNotEmpty()) {
                // SegmentTimeline mode: each (t, d) pair is one segment.
                timelineSegments.mapIndexed { index, (t, d) ->
                    val number = rep.startNumber + index
                    val uri = rep.mediaTemplate
                        .replace("\$RepresentationID\$", rep.id)
                        .replace("\$Bandwidth\$", rep.bandwidth.toString())
                        .replace("\$Number\$", number.toString())
                        .replace("\$Time\$", t.toString())
                        .let { resolveRelativeUrl(baseUrl, it) }
                    RemoteHlsSegment(uri = uri, durationMs = if (ts > 0L) d * 1000L / ts else d)
                }
            } else if (rep.segmentDuration > 0L) {
                // Fixed-duration mode: compute number from wall clock and availabilityStartTime.
                val nowMs = System.currentTimeMillis()
                val elapsedSecs = ((nowMs - availabilityStartTimeMs) / 1000L).coerceAtLeast(0L)
                val totalSegments = elapsedSecs * ts / rep.segmentDuration
                val windowSegments = (effectiveDepthMs / 1000L * ts / rep.segmentDuration + 2L).toInt()
                val firstNumber = (totalSegments - windowSegments).coerceAtLeast(rep.startNumber)
                (firstNumber..totalSegments).map { number ->
                    val uri = rep.mediaTemplate
                        .replace("\$RepresentationID\$", rep.id)
                        .replace("\$Bandwidth\$", rep.bandwidth.toString())
                        .replace("\$Number\$", number.toString())
                        .let { resolveRelativeUrl(baseUrl, it) }
                    RemoteHlsSegment(uri = uri, durationMs = rep.segmentDuration * 1000L / ts)
                }
            } else {
                emptyList()
            }

            return ParsedDashManifest(
                isDynamic = isDynamic,
                minimumUpdatePeriodMs = minimumUpdatePeriodMs,
                initSegmentUrl = resolvedInit,
                mediaSegments = mediaSegments
            )
        }

        /** Parses ISO 8601 duration strings like `PT2.5S`, `PT1M30S`, `P1DT2H`. */
        private fun parsePtDuration(value: String): Long {
            val upper = value.uppercase(Locale.ROOT)
            if (!upper.startsWith("P")) return 5_000L
            var ms = 0L
            val afterP = upper.removePrefix("P")
            val (datePart, timePart) = if (afterP.contains('T')) {
                afterP.substringBefore('T') to afterP.substringAfter('T')
            } else {
                afterP to ""
            }
            Regex("""(\d+(?:\.\d+)?)D""").find(datePart)?.groupValues?.get(1)?.toDoubleOrNull()?.let { ms += (it * 86_400_000).toLong() }
            Regex("""(\d+(?:\.\d+)?)H""").find(timePart)?.groupValues?.get(1)?.toDoubleOrNull()?.let { ms += (it * 3_600_000).toLong() }
            Regex("""(\d+(?:\.\d+)?)M""").find(timePart)?.groupValues?.get(1)?.toDoubleOrNull()?.let { ms += (it * 60_000).toLong() }
            Regex("""(\d+(?:\.\d+)?)S""").find(timePart)?.groupValues?.get(1)?.toDoubleOrNull()?.let { ms += (it * 1_000).toLong() }
            return ms.takeIf { it > 0L } ?: 5_000L
        }

        /** Parses an ISO 8601 datetime string to milliseconds since epoch, best-effort. */
        private fun parseIso8601ToMs(value: String): Long = runCatching {
            java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
        }.getOrDefault(0L)
    }

    private data class ParsedDashManifest(
        val isDynamic: Boolean,
        val minimumUpdatePeriodMs: Long,
        val initSegmentUrl: String?,
        val mediaSegments: List<RemoteHlsSegment>
    )

    private companion object {
        private const val PROGRESSIVE_CHUNK_MS = 2_000L
        private const val PROGRESSIVE_READ_BUFFER_SIZE = 65_536
        private const val MAX_PROGRESSIVE_RETRIES = 10
        private const val MAX_HLS_CONSECUTIVE_ERRORS = 10
        private const val MAX_DASH_CONSECUTIVE_ERRORS = 10
        private const val MAX_RETRY_DELAY_MS = 30_000L
        private const val HLS_ERROR_RETRY_DELAY_MS = 2_000L
        private const val DASH_ERROR_RETRY_DELAY_MS = 2_000L
        private const val MIN_FREE_DISK_BYTES = 200L * 1024 * 1024  // 200 MB
    }
}
