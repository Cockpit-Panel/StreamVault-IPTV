package com.streamvault.player.playback

internal data class PlaybackBufferPolicy(
    val label: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val playbackBufferMs: Int,
    val rebufferMs: Int,
    val targetBufferBytes: Int,
    val prioritizeTimeOverSizeThresholds: Boolean
)

internal object PlaybackBufferPolicies {
    private const val DEFAULT_TARGET_BUFFER_BYTES = -1
    private const val MPEG_TS_LIVE_TARGET_BUFFER_BYTES = 16 * 1024 * 1024
    private const val MPEG_TS_LIVE_MIN_BUFFER_MS = 5_000
    private const val MPEG_TS_LIVE_MAX_BUFFER_MS = 10_000

    private const val LOW_MEMORY_LIVE_MIN_BUFFER_MS = 4_000
    private const val LOW_MEMORY_LIVE_MAX_BUFFER_MS = 12_000
    private const val LOW_MEMORY_COMPAT_LIVE_MIN_BUFFER_MS = 6_000
    private const val LOW_MEMORY_COMPAT_LIVE_MAX_BUFFER_MS = 15_000
    private const val LOW_MEMORY_VOD_MIN_BUFFER_MS = 15_000
    private const val LOW_MEMORY_VOD_MAX_BUFFER_MS = 45_000
    private const val LOW_MEMORY_PLAYBACK_BUFFER_MS = 1_000
    private const val LOW_MEMORY_REBUFFER_MS = 3_000

    private const val LIVE_MIN_BUFFER_MS = 8_000
    private const val LIVE_MAX_BUFFER_MS = 30_000
    private const val COMPAT_LIVE_MIN_BUFFER_MS = 15_000
    private const val COMPAT_LIVE_MAX_BUFFER_MS = 45_000
    private const val VOD_MIN_BUFFER_MS = 90_000
    private const val VOD_MAX_BUFFER_MS = 240_000
    private const val PLAYBACK_BUFFER_MS = 1_500
    private const val REBUFFER_MS = 5_000
    private const val VOD_PLAYBACK_BUFFER_MS = 8_000
    private const val VOD_REBUFFER_MS = 18_000

    fun forPlayback(
        isLive: Boolean,
        compatibilityMode: Boolean,
        lowMemoryDevice: Boolean
    ): PlaybackBufferPolicy = forPlayback(
        resolvedStreamType = if (isLive) ResolvedStreamType.HLS else ResolvedStreamType.PROGRESSIVE,
        compatibilityMode = compatibilityMode,
        lowMemoryDevice = lowMemoryDevice
    )

    fun forPlayback(
        isLive: Boolean,
        compatibilityMode: Boolean
    ): PlaybackBufferPolicy = forPlayback(
        isLive = isLive,
        compatibilityMode = compatibilityMode,
        lowMemoryDevice = false
    )

    fun forPlayback(
        resolvedStreamType: ResolvedStreamType,
        compatibilityMode: Boolean
    ): PlaybackBufferPolicy = forPlayback(
        resolvedStreamType = resolvedStreamType,
        compatibilityMode = compatibilityMode,
        lowMemoryDevice = false
    )

    fun forPlayback(
        resolvedStreamType: ResolvedStreamType,
        compatibilityMode: Boolean,
        lowMemoryDevice: Boolean
    ): PlaybackBufferPolicy = when {
        lowMemoryDevice && resolvedStreamType == ResolvedStreamType.MPEG_TS_LIVE ->
            PlaybackBufferPolicy(
                label = "lowmem-mpeg-ts-live",
                minBufferMs = LOW_MEMORY_LIVE_MIN_BUFFER_MS,
                maxBufferMs = LOW_MEMORY_LIVE_MAX_BUFFER_MS,
                playbackBufferMs = LOW_MEMORY_PLAYBACK_BUFFER_MS,
                rebufferMs = LOW_MEMORY_REBUFFER_MS,
                targetBufferBytes = MPEG_TS_LIVE_TARGET_BUFFER_BYTES,
                prioritizeTimeOverSizeThresholds = true
            )
        lowMemoryDevice && compatibilityMode && resolvedStreamType.isLive ->
            PlaybackBufferPolicy(
                label = "lowmem-compat-live",
                minBufferMs = LOW_MEMORY_COMPAT_LIVE_MIN_BUFFER_MS,
                maxBufferMs = LOW_MEMORY_COMPAT_LIVE_MAX_BUFFER_MS,
                playbackBufferMs = LOW_MEMORY_PLAYBACK_BUFFER_MS,
                rebufferMs = LOW_MEMORY_REBUFFER_MS,
                targetBufferBytes = DEFAULT_TARGET_BUFFER_BYTES,
                prioritizeTimeOverSizeThresholds = true
            )
        lowMemoryDevice && resolvedStreamType.isLive ->
            PlaybackBufferPolicy(
                label = "lowmem-live",
                minBufferMs = LOW_MEMORY_LIVE_MIN_BUFFER_MS,
                maxBufferMs = LOW_MEMORY_LIVE_MAX_BUFFER_MS,
                playbackBufferMs = LOW_MEMORY_PLAYBACK_BUFFER_MS,
                rebufferMs = LOW_MEMORY_REBUFFER_MS,
                targetBufferBytes = DEFAULT_TARGET_BUFFER_BYTES,
                prioritizeTimeOverSizeThresholds = true
            )
        lowMemoryDevice ->
            PlaybackBufferPolicy(
                label = "lowmem-vod",
                minBufferMs = LOW_MEMORY_VOD_MIN_BUFFER_MS,
                maxBufferMs = LOW_MEMORY_VOD_MAX_BUFFER_MS,
                playbackBufferMs = LOW_MEMORY_PLAYBACK_BUFFER_MS,
                rebufferMs = LOW_MEMORY_REBUFFER_MS,
                targetBufferBytes = DEFAULT_TARGET_BUFFER_BYTES,
                prioritizeTimeOverSizeThresholds = true
            )
        resolvedStreamType == ResolvedStreamType.MPEG_TS_LIVE ->
            PlaybackBufferPolicy(
                label = "mpeg-ts-live",
                minBufferMs = MPEG_TS_LIVE_MIN_BUFFER_MS,
                maxBufferMs = MPEG_TS_LIVE_MAX_BUFFER_MS,
                playbackBufferMs = PLAYBACK_BUFFER_MS,
                rebufferMs = REBUFFER_MS,
                targetBufferBytes = MPEG_TS_LIVE_TARGET_BUFFER_BYTES,
                prioritizeTimeOverSizeThresholds = true
            )
        compatibilityMode && resolvedStreamType.isLive ->
            PlaybackBufferPolicy(
                label = "compat-live",
                minBufferMs = COMPAT_LIVE_MIN_BUFFER_MS,
                maxBufferMs = COMPAT_LIVE_MAX_BUFFER_MS,
                playbackBufferMs = PLAYBACK_BUFFER_MS,
                rebufferMs = REBUFFER_MS,
                targetBufferBytes = DEFAULT_TARGET_BUFFER_BYTES,
                prioritizeTimeOverSizeThresholds = true
            )
        resolvedStreamType.isLive ->
            PlaybackBufferPolicy(
                label = "stable-live",
                minBufferMs = LIVE_MIN_BUFFER_MS,
                maxBufferMs = LIVE_MAX_BUFFER_MS,
                playbackBufferMs = PLAYBACK_BUFFER_MS,
                rebufferMs = REBUFFER_MS,
                targetBufferBytes = DEFAULT_TARGET_BUFFER_BYTES,
                prioritizeTimeOverSizeThresholds = true
            )
        else ->
            PlaybackBufferPolicy(
                label = "stable-vod",
                minBufferMs = VOD_MIN_BUFFER_MS,
                maxBufferMs = VOD_MAX_BUFFER_MS,
                playbackBufferMs = VOD_PLAYBACK_BUFFER_MS,
                rebufferMs = VOD_REBUFFER_MS,
                targetBufferBytes = DEFAULT_TARGET_BUFFER_BYTES,
                prioritizeTimeOverSizeThresholds = true
            )
    }

    private val ResolvedStreamType.isLive: Boolean
        get() = this == ResolvedStreamType.HLS ||
            this == ResolvedStreamType.SMOOTH_STREAMING ||
            this == ResolvedStreamType.MPEG_TS_LIVE ||
            this == ResolvedStreamType.RTSP
}
