package com.streamvault.domain.util

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BoundedKeySetTest {
    @Test
    fun oneHundredThousandAdmissionsStayBounded() {
        val set = BoundedKeySet<Int>(256)
        repeat(100_000) { set.tryAdd(it) }
        assertThat(set.size()).isEqualTo(256)
    }

    @Test
    fun awaitAddWaitsForCapacityAndDoesNotDropWork() = runTest {
        val set = BoundedKeySet<Int>(1)
        assertThat(set.tryAdd(1)).isTrue()
        val waiting = async { set.awaitAdd(2, retryDelayMs = 1L) }

        testScheduler.runCurrent()
        assertThat(waiting.isCompleted).isFalse()

        set.remove(1)
        testScheduler.advanceUntilIdle()

        assertThat(waiting.await()).isTrue()
        assertThat(set.contains(2)).isTrue()
    }
}
