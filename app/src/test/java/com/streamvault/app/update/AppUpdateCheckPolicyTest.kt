package com.streamvault.app.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppUpdateCheckPolicyTest {

    @Test
    fun `success suppresses automatic checks for one day`() {
        assertThat(AppUpdateCheckPolicy.shouldAutoCheck(1_000L, 1_000L, null)).isFalse()
        assertThat(AppUpdateCheckPolicy.shouldAutoCheck(86_401_000L, 1_000L, null)).isTrue()
    }

    @Test
    fun `failure retries after bounded backoff instead of one day`() {
        assertThat(AppUpdateCheckPolicy.shouldAutoCheck(1_000L, null, 1_000L)).isFalse()
        assertThat(AppUpdateCheckPolicy.shouldAutoCheck(901_000L, null, 1_000L)).isTrue()
    }

    @Test
    fun `future persisted checks do not suppress retry after backward clock jump`() {
        assertThat(AppUpdateCheckPolicy.shouldAutoCheck(1_000L, 1_001L, null)).isTrue()
        assertThat(AppUpdateCheckPolicy.shouldAutoCheck(1_000L, null, 1_001L)).isTrue()
    }
}
