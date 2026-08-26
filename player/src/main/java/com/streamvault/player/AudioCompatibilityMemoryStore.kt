package com.streamvault.player

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class LearnedAudioCompatibility(
    val mediaId: String,
    val streamType: String,
    val audioMimeTypes: List<String>,
    val decision: String,
    val detail: String?,
    val updatedAtMs: Long
)

@Singleton
class AudioCompatibilityMemoryStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun lookup(mediaId: String, streamType: String): LearnedAudioCompatibility? {
        val encoded = preferences.getString(key(mediaId, streamType), null) ?: return null
        val parts = encoded.split(FIELD_SEPARATOR)
        if (parts.size < 5 || parts[0] != FORMAT_VERSION) {
            preferences.edit().remove(key(mediaId, streamType)).apply()
            return null
        }
        return LearnedAudioCompatibility(
            mediaId = mediaId,
            streamType = streamType,
            audioMimeTypes = parts[2]
                .split(LIST_SEPARATOR)
                .map(String::trim)
                .filter(String::isNotEmpty),
            decision = parts[3],
            detail = parts[4].takeIf(String::isNotBlank),
            updatedAtMs = parts[1].toLongOrNull() ?: 0L
        )
    }

    fun rememberSoftwareAudioFallback(
        mediaId: String,
        streamType: String,
        audioMimeTypes: List<String>,
        detail: String?
    ) {
        val normalizedMimeTypes = audioMimeTypes
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val now = System.currentTimeMillis()
        preferences.edit()
            .putString(
                key(mediaId, streamType),
                listOf(
                    FORMAT_VERSION,
                    now.toString(),
                    normalizedMimeTypes.joinToString(LIST_SEPARATOR.toString()),
                    DECISION_SOFTWARE_FFMPEG,
                    detail.orEmpty().replace(FIELD_SEPARATOR.toString(), " ")
                ).joinToString(FIELD_SEPARATOR.toString())
            )
            .apply()
        trimToBound()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun key(mediaId: String, streamType: String): String {
        return "${Build.FINGERPRINT}|${Build.MODEL}|$streamType|$mediaId"
    }

    private fun trimToBound() {
        val excess = preferences.all.size - MAX_ENTRIES
        if (excess <= 0) return
        val oldestKeys = preferences.all.entries
            .sortedBy { (_, value) ->
                (value as? String)?.split(FIELD_SEPARATOR)?.getOrNull(1)?.toLongOrNull() ?: Long.MIN_VALUE
            }
            .take(excess)
            .map(Map.Entry<String, *>::key)
        preferences.edit().also { editor -> oldestKeys.forEach(editor::remove) }.apply()
    }

    internal fun sizeForTests(): Int = preferences.all.size

    companion object {
        private const val PREFS_NAME = "audio_compatibility_memory"
        private const val FORMAT_VERSION = "v2"
        private const val MAX_ENTRIES = 512
        private const val FIELD_SEPARATOR = '|'
        private const val LIST_SEPARATOR = ','
        const val DECISION_SOFTWARE_FFMPEG = "software-ffmpeg"
    }
}
