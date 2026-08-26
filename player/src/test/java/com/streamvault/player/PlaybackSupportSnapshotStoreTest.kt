package com.streamvault.player

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackSupportSnapshotStoreTest {

    @Test
    fun `queued diagnostics keep only the newest report`() = runTest {
        val writes = mutableListOf<String>()
        val writer = CoalescingSnapshotWriter(backgroundScope, writes::add)

        writer.submit("old")
        writer.submit("new")
        runCurrent()
        writer.close()

        assertThat(writes).containsExactly("new")
    }

    @Test
    fun `snapshot file callback runs away from the caller thread`() = runBlocking {
        val callerThread = Thread.currentThread()
        var writerThread: Thread? = null
        val writer = CoalescingSnapshotWriter(
            CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ) {
            writerThread = Thread.currentThread()
        }

        writer.submit("diagnostics")
        writer.close()

        assertThat(writerThread).isNotNull()
        assertThat(writerThread).isNotEqualTo(callerThread)
    }

    @Test
    fun `snapshot writes a temporary file before one atomic replacement`() {
        val directory = Files.createTempDirectory("playback-support-test").toFile()
        try {
            val target = File(directory, "latest-playback-support.txt")
            val renameCalls = mutableListOf<Pair<String, String>>()

            writeSnapshotAtomically(target, "latest") { temporaryPath, targetPath ->
                renameCalls += temporaryPath to targetPath
                check(File(temporaryPath).renameTo(File(targetPath)))
            }

            assertThat(renameCalls).containsExactly(target.resolveSibling("latest-playback-support.txt.tmp").absolutePath to target.absolutePath)
            assertThat(target.readText()).isEqualTo("latest")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `snapshot write failures are observable but rate limited`() {
        var nowMs = 1_000L
        val failures = mutableListOf<Throwable>()
        val logger = RateLimitedSnapshotFailureLogger(
            nowMs = { nowMs },
            log = failures::add
        )
        val first = IllegalStateException("first")
        val suppressed = IllegalStateException("suppressed")
        val second = IllegalStateException("second")

        logger.log(first)
        nowMs += 1_000L
        logger.log(suppressed)
        nowMs += 60_000L
        logger.log(second)

        assertThat(failures).containsExactly(first, second).inOrder()
    }
}
