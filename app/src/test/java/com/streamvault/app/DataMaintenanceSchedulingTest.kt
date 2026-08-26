package com.streamvault.app

import androidx.work.NetworkType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DataMaintenanceSchedulingTest {

    @Test
    fun `daily local maintenance does not require a network connection`() {
        val constraints = dataMaintenanceConstraints()

        assertThat(constraints.requiredNetworkType).isEqualTo(NetworkType.NOT_REQUIRED)
        assertThat(constraints.requiresBatteryNotLow()).isTrue()
        assertThat(constraints.requiresDeviceIdle()).isTrue()
    }
}
