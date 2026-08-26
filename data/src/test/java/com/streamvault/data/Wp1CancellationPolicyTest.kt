package com.streamvault.data

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

class Wp1CancellationPolicyTest {

    private val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun `WP1 owners do not regress to blocking or cancellation-swallowing calls`() {
        val workers = listOf(
            "SyncWorker.kt",
            "BackgroundEpgSyncWorker.kt",
            "ProviderSyncWorker.kt",
            "StalkerIndexWorker.kt",
            "XtreamIndexWorker.kt"
        ).map { root.resolve("data/src/main/java/com/streamvault/data/sync/$it") }
        workers.forEach { file ->
            val source = file.readText()
            assertWithMessage("${file.name} must rethrow cancellation before broad worker catches")
                .that(source.indexOf("catch (cancelled: kotlinx.coroutines.CancellationException)"))
                .isLessThan(source.indexOf("catch (e: Exception)").takeIf { it >= 0 }
                    ?: source.indexOf("catch (error: Exception)"))
        }

        val stalker = root.resolve(
            "data/src/main/java/com/streamvault/data/remote/stalker/OkHttpStalkerApiService.kt"
        ).readText()
        assertWithMessage("Stalker suspend transport must not use blocking execute()")
            .that(stalker).doesNotContain(".execute()")
        assertWithMessage("Stalker network fallbacks must use runSuspendCatching")
            .that(stalker).doesNotContain("runCatching {\n                            requestJson(")

        val xtream = root.resolve(
            "data/src/main/java/com/streamvault/data/remote/xtream/XtreamProvider.kt"
        ).readText()
        assertWithMessage("Xtream compatibility fallback must preserve cancellation")
            .that(xtream).doesNotContain("runCatching { requestSeriesInfo(")

        val plugins = root.resolve(
            "app/src/main/java/com/streamvault/app/plugins/StreamVaultPluginManager.kt"
        ).readText()
        assertWithMessage("Plugin IPC must not use raw runCatching")
            .that(plugins).doesNotContain("runCatching {\n            messengerClient.send(")
    }
}
