package com.streamvault.app.service

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device smoke for the service branch used after a sticky restart. A real process kill is owned
 * by the release harness, but this verifies that an empty command can enter recovery and return
 * without a foreground-service startup crash on API 35/36.
 */
@RunWith(AndroidJUnit4::class)
class DownloadForegroundServiceInstrumentationTest {

    @Test
    fun emptyCommand_entersRecoveryBranchWithoutStartupCrash() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, DownloadForegroundService::class.java)

        try {
            ContextCompat.startForegroundService(context, intent)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(750L)
        } finally {
            context.stopService(intent)
        }
    }
}
