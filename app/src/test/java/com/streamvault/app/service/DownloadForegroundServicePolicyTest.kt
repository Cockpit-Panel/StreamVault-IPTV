package com.streamvault.app.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DownloadForegroundServicePolicyTest {

    @Test
    fun `sticky null intent enters interrupted download recovery`() {
        assertThat(resolveDownloadServiceStartMode(null))
            .isEqualTo(DownloadServiceStartMode.RECOVER_INTERRUPTED)
    }

    @Test
    fun `blank sticky download id enters interrupted download recovery`() {
        assertThat(resolveDownloadServiceStartMode(" "))
            .isEqualTo(DownloadServiceStartMode.RECOVER_INTERRUPTED)
    }

    @Test
    fun `explicit download command observes requested download`() {
        assertThat(resolveDownloadServiceStartMode("download-1"))
            .isEqualTo(DownloadServiceStartMode.OBSERVE_REQUESTED)
    }
}
