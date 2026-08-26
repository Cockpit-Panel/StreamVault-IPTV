package com.streamvault.data.remote.stalker

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

@Serializable
enum class StalkerSeriesDetailDialect {
    UNKNOWN,
    NATIVE_SERIES,
    VOD_SEASON_SHELLS,
    INLINE_EPISODES
}

@Serializable
enum class StalkerEpisodeSelectorDialect {
    UNKNOWN,
    EPISODE_COMMAND,
    PARENT_COMMAND_WITH_SERIES_NUMBER
}

@Serializable
enum class StalkerVodPlaybackDialect {
    UNKNOWN,
    DIRECT,
    CREATE_LINK,
    PORTAL_MOVIE_ENDPOINT
}

@Serializable
data class StalkerContentCapabilities(
    val version: Int = CURRENT_VERSION,
    val revision: Int = CURRENT_REVISION,
    val configurationGeneration: Int = 0,
    val seriesDetailDialect: StalkerSeriesDetailDialect = StalkerSeriesDetailDialect.UNKNOWN,
    val episodeSelectorDialect: StalkerEpisodeSelectorDialect = StalkerEpisodeSelectorDialect.UNKNOWN,
    val vodPlaybackDialect: StalkerVodPlaybackDialect = StalkerVodPlaybackDialect.UNKNOWN,
    val validationEvidence: List<String> = emptyList()
) {
    fun validFor(generation: Int): Boolean =
        version == CURRENT_VERSION && configurationGeneration == generation

    companion object {
        const val CURRENT_VERSION = 1
        const val CURRENT_REVISION = 1
    }
}

object StalkerContentCapabilitiesCodec {
    private const val CONTENT_KEY = "contentCapabilities"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decode(raw: String?, configurationGeneration: Int): StalkerContentCapabilities {
        if (raw.isNullOrBlank()) return StalkerContentCapabilities(configurationGeneration = configurationGeneration)
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return StalkerContentCapabilities(configurationGeneration = configurationGeneration)
        val content = root[CONTENT_KEY] ?: return StalkerContentCapabilities(
            configurationGeneration = configurationGeneration
        )
        val decoded = runCatching {
            json.decodeFromJsonElement<StalkerContentCapabilities>(content)
        }.getOrNull() ?: return StalkerContentCapabilities(configurationGeneration = configurationGeneration)
        return decoded.takeIf { it.validFor(configurationGeneration) }
            ?: StalkerContentCapabilities(configurationGeneration = configurationGeneration)
    }

    /** Keeps the legacy flat LIVE/VOD/SERIES keys intact for diagnostics and older builds. */
    fun encode(existingRaw: String?, capabilities: StalkerContentCapabilities): String {
        val existing = existingRaw
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: JsonObject(emptyMap())
        return buildJsonObject {
            existing.forEach { (key, value) -> if (key != CONTENT_KEY) put(key, value) }
            put(CONTENT_KEY, json.encodeToJsonElement(capabilities))
        }.toString()
    }
}
