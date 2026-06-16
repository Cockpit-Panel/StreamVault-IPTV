package com.streamvault.player.audio

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlayerAudioFocusController(
    context: Context,
    private val applyVolume: (Float) -> Unit,
    private val setPlayWhenReady: (Boolean) -> Unit,
    private val onAudioFocusDenied: (() -> Unit)? = null
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: Any? = null
    private var hasAudioFocus = false
    private var shouldResumeOnAudioFocusGain = false
    private var isDucked = false
    private var currentVolume = 1f
    private var volumeBeforeMute = 1f
    private var currentOsContentType = android.media.AudioAttributes.CONTENT_TYPE_MOVIE

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    // Volatile for visibility: set from the engine constructor (Main) and read by
    // audioFocusChangeListener (also delivered on Main). @Volatile is defensive; the
    // current call-site guarantees Main-thread-only access via Hilt constructor sequencing.
    @Volatile
    var bypassAudioFocus: Boolean = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                isDucked = false
                dispatchVolume()
                if (shouldResumeOnAudioFocusGain) {
                    shouldResumeOnAudioFocusGain = false
                    setPlayWhenReady(true)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                isDucked = true
                dispatchVolume()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                isDucked = false
                shouldResumeOnAudioFocusGain = true
                setPlayWhenReady(false)
                dispatchVolume()
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                isDucked = false
                shouldResumeOnAudioFocusGain = false
                setPlayWhenReady(false)
                dispatchVolume()
            }
        }
    }

    fun requestAudioFocusIfNeeded(
        osContentType: Int = currentOsContentType
    ): Boolean {
        if (bypassAudioFocus || hasAudioFocus) {
            isDucked = false
            dispatchVolume()
            return true
        }
        if (osContentType != currentOsContentType) {
            currentOsContentType = osContentType
            abandonAudioFocusRequestObject()
            audioFocusRequest = null
            hasAudioFocus = false
        }
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = (audioFocusRequest as? AudioFocusRequest)
                ?: buildAudioFocusRequest().also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        hasAudioFocus = granted
        if (!granted) {
            Log.w(TAG, "audio-focus denied")
            return false
        }
        isDucked = false
        dispatchVolume()
        return true
    }

    fun onPauseOrStop() {
        shouldResumeOnAudioFocusGain = false
        abandonAudioFocusIfHeld()
    }

    fun onPlaybackStarted() {
        shouldResumeOnAudioFocusGain = false
    }

    fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        if (currentVolume > 0f) {
            volumeBeforeMute = currentVolume
            _isMuted.value = false
        }
        dispatchVolume()
    }

    fun setMuted(muted: Boolean) {
        if (muted == _isMuted.value) {
            dispatchVolume()
            return
        }
        if (muted) {
            if (currentVolume > 0f) {
                volumeBeforeMute = currentVolume.coerceAtLeast(0.1f)
            }
            _isMuted.value = true
        } else {
            _isMuted.value = false
            if (currentVolume <= 0f) {
                currentVolume = volumeBeforeMute.coerceIn(0.1f, 1f)
            }
            // Re-request audio focus on unmute to unblock playback if focus was previously denied
            if (!hasAudioFocus && !bypassAudioFocus) {
                if (requestAudioFocusIfNeeded()) {
                    setPlayWhenReady(true)
                } else {
                    onAudioFocusDenied?.invoke()
                }
            }
        }
        dispatchVolume()
    }

    fun toggleMute() {
        setMuted(!_isMuted.value)
    }

    fun reapplyVolume() {
        dispatchVolume()
    }

    fun release() {
        shouldResumeOnAudioFocusGain = false
        abandonAudioFocusIfHeld()
    }

    private fun dispatchVolume() {
        applyVolume(
            when {
                _isMuted.value -> 0f
                isDucked -> (currentVolume * 0.2f).coerceAtLeast(0.05f)
                else -> currentVolume
            }
        )
    }

    private fun abandonAudioFocusIfHeld() {
        if (!hasAudioFocus) return
        abandonAudioFocusRequestObject()
        hasAudioFocus = false
        isDucked = false
    }

    private fun abandonAudioFocusRequestObject() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (audioFocusRequest as? AudioFocusRequest)?.let(audioManager::abandonAudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private fun buildAudioFocusRequest(): AudioFocusRequest {
        val attributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(currentOsContentType)
            .build()
        return AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()
    }

    companion object {
        private const val TAG = "PlayerAudioFocus"
    }
}
