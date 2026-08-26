package com.streamvault.data.preferences

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PreferencesCorruptionRecoveryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        recoveryDirectory().deleteRecursively()
        context.preferencesDataStoreFile(DATASTORE_NAME).delete()
    }

    @After
    fun tearDown() {
        recoveryDirectory().deleteRecursively()
        context.preferencesDataStoreFile(DATASTORE_NAME).delete()
    }

    @Test
    fun `corruption handler returns defaults and retains diagnostic snapshot`() {
        val source = context.preferencesDataStoreFile(DATASTORE_NAME).apply {
            parentFile?.mkdirs()
            writeText("not-a-preferences-protobuf")
        }
        val recovery = PreferencesCorruptionRecovery(context)
        assertThat(recovery.recover(CorruptionException("checksum"), DATASTORE_NAME).asMap())
            .isEqualTo(emptyPreferences().asMap())
        assertThat(recovery.lastRecoveryTimestamp()).isGreaterThan(0L)
        assertThat(recoveryDirectory().resolve("$DATASTORE_NAME.recovery").isFile).isTrue()
        assertThat(recoveryDirectory().listFiles { file, name ->
            name.startsWith("$DATASTORE_NAME.corrupt-")
        }).hasLength(1)
    }

    private fun recoveryDirectory(): File = File(context.filesDir, "preference-recovery")

    companion object {
        private const val DATASTORE_NAME = "user_preferences_corruption_test"
    }
}
