package com.streamvault.data.sync

import androidx.work.ExistingWorkPolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StalkerIndexWorkerTest {

    @Test
    fun `external scheduling keeps an existing provider chain`() {
        assertThat(stalkerIndexExistingWorkPolicy(force = false, appendSuccessor = false))
            .isEqualTo(ExistingWorkPolicy.KEEP)
    }

    @Test
    fun `worker continuation appends one successor to its provider chain`() {
        assertThat(stalkerIndexExistingWorkPolicy(force = false, appendSuccessor = true))
            .isEqualTo(ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    @Test
    fun `forced scheduling replaces the provider chain`() {
        assertThat(stalkerIndexExistingWorkPolicy(force = true, appendSuccessor = true))
            .isEqualTo(ExistingWorkPolicy.REPLACE)
    }
}
