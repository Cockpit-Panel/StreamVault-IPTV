@file:androidx.media3.common.util.UnstableApi

package com.streamvault.app.tvinput

import android.content.ContentUris
import android.content.Context
import android.media.tv.TvInputManager
import android.media.tv.TvInputService
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.util.BoundedExpiringCache
import com.streamvault.domain.model.StreamType
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.player.playback.applyUnsafeTlsBypass
import com.streamvault.player.playback.applyPlaybackTransportPolicy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.OkHttpClient
import javax.inject.Inject

@AndroidEntryPoint
class StreamVaultTvInputService : TvInputService() {

    @Inject
    lateinit var channelRepository: ChannelRepository

    @Inject
    lateinit var okHttpClient: OkHttpClient

    // TV input sessions can observe many proxy/transport combinations over the service
    // lifetime. Keep only a small, expiring set of derived clients; eviction drops the cache
    // reference and never closes a client that may still be serving an active tune.
    private val playbackClients = BoundedExpiringCache<PlaybackClientKey, OkHttpClient>(
        maxEntries = 8,
        ttlMillis = 15L * 60L * 1_000L
    )

    override fun onCreateSession(inputId: String): Session = StreamVaultSession(this)

    private inner class StreamVaultSession(context: Context) : Session(context) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> notifyVideoAvailable()
                        Player.STATE_BUFFERING -> notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
                        Player.STATE_IDLE, Player.STATE_ENDED -> Unit
                    }
                }
            })
        }

        private val mainHandler = Handler(Looper.getMainLooper())

        override fun onTune(channelUri: Uri): Boolean {
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
            scope.launch {
                tune(channelUri)
            }
            return true
        }

        override fun onSetSurface(surface: Surface?): Boolean {
            player.setVideoSurface(surface)
            return true
        }

        override fun onSetStreamVolume(volume: Float) {
            player.volume = volume
        }

        override fun onSetCaptionEnabled(enabled: Boolean) = Unit

        override fun onRelease() {
            scope.cancel()
            player.release()
        }

        private suspend fun tune(channelUri: Uri) {
            val channelRef = withContext(Dispatchers.IO) { loadChannelRef(channelUri) }
            if (channelRef == null) {
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                return
            }

            val channel = channelRepository.getChannel(channelRef.channelId)
            if (channel == null) {
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                return
            }

            val streamInfo = channelRepository.getStreamInfo(channel).getOrNull()
            if (streamInfo == null) {
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                return
            }

            notifyContentAllowed()
            notifyChannelRetuned(channelUri)
            val mediaSource = buildMediaSource(streamInfo)
            player.setMediaSource(mediaSource)
            player.prepare()
            player.playWhenReady = true
        }

        private fun buildMediaSource(streamInfo: StreamInfo): MediaSource {
            val streamType = inferStreamType(streamInfo)
            val mediaItem = MediaItem.fromUri(streamInfo.url)
            return when (streamType) {
                StreamType.HLS -> HlsMediaSource.Factory(buildHttpDataSource(streamInfo)).createMediaSource(mediaItem)
                StreamType.DASH -> DashMediaSource.Factory(buildHttpDataSource(streamInfo)).createMediaSource(mediaItem)
                StreamType.SMOOTH_STREAMING -> SsMediaSource.Factory(buildHttpDataSource(streamInfo)).createMediaSource(mediaItem)
                StreamType.RTSP -> RtspMediaSource.Factory().createMediaSource(mediaItem)
                StreamType.MPEG_TS,
                StreamType.PROGRESSIVE,
                StreamType.UNKNOWN -> ProgressiveMediaSource.Factory(buildHttpDataSource(streamInfo)).createMediaSource(mediaItem)
            }
        }

        private fun buildHttpDataSource(streamInfo: StreamInfo): DataSource.Factory {
            return OkHttpDataSource.Factory(okHttpClient.forPlayback(streamInfo)).apply {
                setUserAgent(streamInfo.userAgent ?: DEFAULT_USER_AGENT)
                setDefaultRequestProperties(streamInfo.headers)
            }
        }

        private fun inferStreamType(streamInfo: StreamInfo): StreamType {
            if (streamInfo.streamType != StreamType.UNKNOWN) return streamInfo.streamType
            val url = streamInfo.url.lowercase()
            return when {
                url.endsWith(".m3u8") -> StreamType.HLS
                url.endsWith(".mpd") -> StreamType.DASH
                url.contains(".isml/manifest") || url.contains(".ism/manifest") || url.endsWith(".ism") || url.endsWith(".isml") ->
                    StreamType.SMOOTH_STREAMING
                url.startsWith("rtsp") -> StreamType.RTSP
                url.endsWith(".ts") -> StreamType.MPEG_TS
                else -> StreamType.PROGRESSIVE
            }
        }

        private fun loadChannelRef(channelUri: Uri): ChannelRef? {
            val resolvedUri = if (ContentUris.parseId(channelUri) > 0) channelUri else return null
            return contentResolver.query(
                resolvedUri,
                arrayOf(CHANNEL_COLUMN_INTERNAL_PROVIDER_DATA),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val rawData = cursor.getString(cursor.getColumnIndexOrThrow(CHANNEL_COLUMN_INTERNAL_PROVIDER_DATA))
                decodeChannelRef(rawData)
            }
        }

        private fun decodeChannelRef(rawData: String?): ChannelRef? {
            val parts = rawData?.split(ENTRY_SEPARATOR).orEmpty()
            if (parts.size < 2) return null
            val providerId = parts[0].toLongOrNull() ?: return null
            val channelId = parts[1].toLongOrNull() ?: return null
            return ChannelRef(providerId = providerId, channelId = channelId)
        }
    }

    private data class ChannelRef(
        val providerId: Long,
        val channelId: Long
    )

    private fun OkHttpClient.forPlayback(streamInfo: StreamInfo): OkHttpClient {
        val proxy = streamInfo.httpProxy()
        if (streamInfo.playbackTransportPolicy == null &&
            !streamInfo.allowInvalidSsl &&
            proxy == null
        ) {
            return this
        }
        val key = PlaybackClientKey(
            transportPolicy = streamInfo.playbackTransportPolicy,
            allowInvalidSsl = streamInfo.allowInvalidSsl,
            proxyHost = streamInfo.proxyHost.trim(),
            proxyPort = streamInfo.proxyPort
        )
        return playbackClients.getOrPut(key) {
            newBuilder()
                .apply {
                    val transportPolicy = streamInfo.playbackTransportPolicy
                    if (transportPolicy != null) {
                        applyPlaybackTransportPolicy(transportPolicy)
                    } else if (streamInfo.allowInvalidSsl) {
                        applyUnsafeTlsBypass()
                    }
                    proxy?.let { proxy(it) }
                }
                .build()
        }
    }

    private fun StreamInfo.httpProxy(): Proxy? {
        val host = proxyHost.trim().takeIf { it.isNotBlank() } ?: return null
        val port = proxyPort ?: return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
    }

    private data class PlaybackClientKey(
        val transportPolicy: com.streamvault.domain.model.PlaybackTransportPolicy?,
        val allowInvalidSsl: Boolean,
        val proxyHost: String,
        val proxyPort: Int?
    )

    private companion object {
        const val TAG = "StreamVaultTvInput"
        const val DEFAULT_USER_AGENT = "StreamVaultTvInput"
        const val CHANNEL_COLUMN_INTERNAL_PROVIDER_DATA = "internal_provider_data"
        const val ENTRY_SEPARATOR = ":"
    }
}
