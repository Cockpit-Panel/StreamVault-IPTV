package com.streamvault.data.manager

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test

class ActiveCaptureTest {

    @Test
    fun `cancelAndJoin returns only after capture cleanup completes`() = runBlocking {
        val cleanedUp = AtomicBoolean(false)
        val job = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    delay(25)
                    cleanedUp.set(true)
                }
            }
        }
        val capture = ActiveCapture(job)

        capture.cancelAndJoin()
        capture.cancelAndJoin()

        assertThat(cleanedUp.get()).isTrue()
        assertThat(capture.isActive).isFalse()
    }
}
