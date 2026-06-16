package com.streamvault.app.update

import com.streamvault.app.BuildConfig
import com.streamvault.domain.model.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

private val GITHUB_RELEASES_LATEST_URL = com.PanelURL.URL + "update.php"
private val GITHUB_RELEASES_LIST_URL = com.PanelURL.URL + "update.php"

data class GitHubReleaseInfo(
    val versionName: String,
    val versionCode: Int?,
    val releaseUrl: String,
    val downloadUrl: String?,
    val releaseNotes: String,
    val publishedAt: String?
)

@Singleton
class GitHubReleaseChecker @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private companion object {
        private const val MAX_RESPONSE_BYTES = 512 * 1024L
        private val STRUCTURED_TAG_REGEX = Regex("""^v?(.+?)\+(\d+)$""", RegexOption.IGNORE_CASE)
    }

    suspend fun fetchLatestRelease(): Result<GitHubReleaseInfo> = withContext(Dispatchers.IO) {
        try {
            val updateChannel = AppUpdateChannel.fromCurrentBuild()
            val request = Request.Builder()
                .url(updateChannel.releaseApiUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "StreamVault-Update-Checker")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.error("Update check failed: HTTP ${response.code}")
                }

                val body = when (val bodyResult = response.body?.let(::readResponseBodyCapped)) {
                    is Result.Success -> bodyResult.data
                    is Result.Error -> return@withContext Result.error(bodyResult.message, bodyResult.exception)
                    null,
                    Result.Loading -> ""
                }
                if (body.isBlank()) {
                    return@withContext Result.error("Update check failed: empty GitHub release response")
                }

                val json = selectReleaseJson(body, updateChannel)
                    ?: return@withContext Result.error(
                        if (updateChannel == AppUpdateChannel.Beta) {
                            "Update check failed: no beta release found"
                        } else {
                            "Update check failed: latest release response was invalid"
                        }
                    )
                val parsedTag = parseTagVersionInfo(json.optString("tag_name"))
                if (parsedTag.versionName.isBlank()) {
                    return@withContext Result.error("Update check failed: latest release tag is missing")
                }

                val notes = json.optString("body").trim()
                val assets = json.optJSONArray("assets")
                val releaseUrl = json.optString("html_url").takeIf(::isHttpsUrl).orEmpty()
                if (releaseUrl.isBlank()) {
                    return@withContext Result.error("Update check failed: latest release URL is not HTTPS")
                }
                val downloadUrl = findApkAssetUrl(assets, updateChannel)

                return@withContext Result.success(
                    GitHubReleaseInfo(
                        versionName = parsedTag.versionName,
                        versionCode = parsedTag.versionCode,
                        releaseUrl = releaseUrl,
                        downloadUrl = downloadUrl,
                        releaseNotes = notes,
                        publishedAt = json.optString("published_at").takeIf { it.isNotBlank() }
                    )
                )
            }
        } catch (error: IOException) {
            Result.error("Update check failed: network error", error)
        } catch (error: Exception) {
            Result.error("Update check failed: ${error.message}", error)
        }
    }

    private fun selectReleaseJson(body: String, updateChannel: AppUpdateChannel): JSONObject? {
        val trimmed = body.trim()
        if (trimmed.startsWith("[")) {
            val releases = org.json.JSONArray(trimmed)
            if (updateChannel == AppUpdateChannel.Stable) {
                return if (releases.length() > 0) releases.optJSONObject(0) else null
            }
            for (index in 0 until releases.length()) {
                val release = releases.optJSONObject(index) ?: continue
                if (release.optBoolean("draft")) continue
                if (!release.optBoolean("prerelease")) continue
                val tagName = release.optString("tag_name")
                if (!tagName.contains("-beta", ignoreCase = true)) continue
                val downloadUrl = findApkAssetUrl(release.optJSONArray("assets"), updateChannel)
                if (downloadUrl != null) {
                    return release
                }
            }
            return if (releases.length() > 0) releases.optJSONObject(0) else null
        } else {
            return JSONObject(trimmed)
        }
    }

    private fun readResponseBodyCapped(body: ResponseBody): Result<String> {
        val contentLength = body.contentLength()
        if (contentLength > MAX_RESPONSE_BYTES) {
            return Result.error("Update check failed: GitHub release response exceeded 512 KB")
        }

        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var totalBytesRead = 0L

        body.byteStream().use { input ->
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break

                totalBytesRead += bytesRead
                if (totalBytesRead > MAX_RESPONSE_BYTES) {
                    return Result.error("Update check failed: GitHub release response exceeded 512 KB")
                }

                output.write(buffer, 0, bytesRead)
            }
        }

        return Result.success(output.toString(charset.name()))
    }

    private fun findApkAssetUrl(assets: org.json.JSONArray?, updateChannel: AppUpdateChannel): String? {
        if (assets == null) return null
        var fallback: String? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url").takeIf { it.isNotBlank() } ?: continue
            if (!isHttpsUrl(url)) continue
            when (updateChannel) {
                AppUpdateChannel.Stable -> {
                    if (name.equals("StreamVault.apk", ignoreCase = true)) {
                        return url
                    }
                    if (fallback == null &&
                        name.endsWith(".apk", ignoreCase = true) &&
                        !name.contains("beta", ignoreCase = true)
                    ) {
                        fallback = url
                    }
                }
                AppUpdateChannel.Beta -> {
                    if (name.equals("StreamVault-beta.apk", ignoreCase = true)) {
                        return url
                    }
                    if (fallback == null &&
                        name.endsWith(".apk", ignoreCase = true) &&
                        name.contains("beta", ignoreCase = true)
                    ) {
                        fallback = url
                    }
                }
            }
        }
        return fallback
    }

    private fun parseTagVersionInfo(rawTagName: String): ParsedTagVersion {
        val normalizedTag = rawTagName.trim()
        val structuredMatch = STRUCTURED_TAG_REGEX.matchEntire(normalizedTag)
        if (structuredMatch != null) {
            return ParsedTagVersion(
                versionName = structuredMatch.groupValues[1].trim(),
                versionCode = structuredMatch.groupValues[2].toIntOrNull()
            )
        }

        return ParsedTagVersion(
            versionName = normalizedTag.removePrefix("v").trim(),
            versionCode = null
        )
    }

    private fun isHttpsUrl(url: String): Boolean {
        val normalized = url.trim()
        if (normalized.isBlank()) return false
        return runCatching {
            val parsed = URI(normalized)
            parsed.scheme.equals("https", ignoreCase = true) && !parsed.host.isNullOrBlank()
        }.getOrDefault(false)
    }
}

enum class AppUpdateChannel(val id: String, val releaseApiUrl: String) {
    Stable(id = "stable", releaseApiUrl = GITHUB_RELEASES_LATEST_URL),
    Beta(id = "beta", releaseApiUrl = GITHUB_RELEASES_LIST_URL);

    companion object {
        fun fromCurrentBuild(): AppUpdateChannel {
            return fromBuildConfig(BuildConfig.APP_UPDATE_CHANNEL, BuildConfig.VERSION_NAME)
        }

        fun fromBuildConfig(channelId: String?, versionName: String): AppUpdateChannel {
            return when {
                channelId.equals(Beta.id, ignoreCase = true) -> Beta
                versionName.contains("-beta", ignoreCase = true) -> Beta
                else -> Stable
            }
        }
    }
}

private data class ParsedTagVersion(
    val versionName: String,
    val versionCode: Int?
)
