package com.streamvault.player.playback

import androidx.annotation.VisibleForTesting
import androidx.media3.common.util.UnstableApi
import com.streamvault.domain.model.DecoderMode

internal data class FfmpegExtensionAvailability(
    val available: Boolean,
    val version: String?
)

internal data class FfmpegAudioFallbackRequest(
    val requestedMode: DecoderMode,
    val extensionAvailable: Boolean,
    val supportedMimeTypes: List<String>,
    val fallbackMode: DecoderMode?
)

@Suppress("UNUSED_PARAMETER")
internal fun shouldAttemptFfmpegAudioFallback(request: FfmpegAudioFallbackRequest): Boolean {
    // FFmpeg audio is exposed through Media3's platform-first extension renderer
    // chain. Retrying into software mode would reopen the provider URL.
    return false
}

@UnstableApi
internal class FfmpegExtensionSupport(
    private val reflectiveLibrary: ReflectiveFfmpegLibrary = ReflectiveFfmpegLibrary()
) {
    fun availability(): FfmpegExtensionAvailability = reflectiveLibrary.availability()

    fun supportsFormat(mimeType: String?): Boolean = reflectiveLibrary.supportsFormat(mimeType)

    fun supportedAudioMimeTypes(mimeTypes: Collection<String>): List<String> =
        mimeTypes
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .filter(::supportsFormat)
}

@UnstableApi
internal class ReflectiveFfmpegLibrary(
    @get:VisibleForTesting
    internal val libraryClassProvider: () -> Class<*>? = {
        runCatching { Class.forName(FFMPEG_LIBRARY_CLASS_NAME) }.getOrNull()
    }
) {
    fun availability(): FfmpegExtensionAvailability {
        val libraryClass = libraryClassProvider() ?: return FfmpegExtensionAvailability(false, null)
        val available = invokeBooleanStatic(libraryClass, "isAvailable") ?: return FfmpegExtensionAvailability(false, null)
        val version = if (available) invokeStringStatic(libraryClass, "getVersion") else null
        return FfmpegExtensionAvailability(available = available, version = version)
    }

    fun supportsFormat(mimeType: String?): Boolean {
        if (mimeType.isNullOrBlank()) return false
        val libraryClass = libraryClassProvider() ?: return false
        val normalizedMimeType = mimeType.trim()
        return invokeBooleanStatic(
            clazz = libraryClass,
            methodName = "supportsFormat",
            parameterTypes = arrayOf(String::class.java, String::class.java),
            args = arrayOf(normalizedMimeType, null)
        ) ?: invokeBooleanStatic(libraryClass, "supportsFormat", normalizedMimeType) ?: false
    }

    private fun invokeBooleanStatic(clazz: Class<*>, methodName: String, vararg args: Any): Boolean? =
        runCatching {
            val parameterTypes = args.map { it::class.java }.toTypedArray()
            clazz.getMethod(methodName, *parameterTypes).invoke(null, *args) as? Boolean
        }.getOrNull()

    private fun invokeBooleanStatic(
        clazz: Class<*>,
        methodName: String,
        parameterTypes: Array<Class<*>>,
        args: Array<Any?>
    ): Boolean? =
        runCatching {
            clazz.getMethod(methodName, *parameterTypes).invoke(null, *args) as? Boolean
        }.getOrNull()

    private fun invokeStringStatic(clazz: Class<*>, methodName: String): String? =
        runCatching {
            clazz.getMethod(methodName).invoke(null) as? String
        }.getOrNull()

    private companion object {
        private const val FFMPEG_LIBRARY_CLASS_NAME = "androidx.media3.decoder.ffmpeg.FfmpegLibrary"
    }
}
