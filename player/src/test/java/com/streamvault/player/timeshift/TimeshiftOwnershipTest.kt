package com.streamvault.player.timeshift

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class TimeshiftOwnershipTest {

    @Test
    fun `session files are deleted only after capture cleanup joins`() = runBlocking {
        val events = mutableListOf<String>()
        val captureJob = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    events += "writer-closed"
                }
            }
        }

        stopOwnedTimeshiftCapture(activeCall = AtomicReference(null), captureJob = captureJob) {
            events += "files-deleted"
        }

        assertThat(events).containsExactly("writer-closed", "files-deleted").inOrder()
    }
}
