package com.streamvault.player

data class LiveAudioPcmBuffer(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val encoding: Int
)

fun interface LiveAudioTap {
    fun onPcmAudio(buffer: LiveAudioPcmBuffer)
}
