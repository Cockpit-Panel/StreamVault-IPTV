package com.streamvault.app.backup

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
class BackupFileBridgeTest {

    private lateinit var cacheDir: java.io.File
    private val context: Context = mock()
    private val resolver: ContentResolver = mock()
    private val sourceUri: Uri = mock()
    private val preferences: SharedPreferences = mock()
    private val preferencesEditor: SharedPreferences.Editor = mock()

    @Before
    fun setUp() {
        cacheDir = Files.createTempDirectory("backup-bridge-test").toFile()
        whenever(context.cacheDir).thenReturn(cacheDir)
        whenever(context.filesDir).thenReturn(cacheDir)
        whenever(context.contentResolver).thenReturn(resolver)
        whenever(context.getSharedPreferences(any(), any())).thenReturn(preferences)
        whenever(preferences.getStringSet(any(), any())).thenReturn(emptySet())
        whenever(preferences.edit()).thenReturn(preferencesEditor)
        whenever(preferencesEditor.putStringSet(any(), any())).thenReturn(preferencesEditor)
    }

    @After
    fun tearDown() {
        cacheDir.deleteRecursively()
    }

    @Test
    fun oversizedExternalImport_isRejectedAndPartialInboxFileIsRemoved() {
        whenever(resolver.openInputStream(sourceUri)).thenReturn(
            GeneratedInputStream(16L * 1024L * 1024L + 1L)
        )

        val result = BackupFileBridge.copyToImportInbox(context, sourceUri)

        assertThat(result).isNull()
        val inbox = java.io.File(cacheDir, "backup_imports")
        assertThat(inbox.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun pickerFreeExport_createsFallbackFileAndRemovesItOnFailure() {
        val uri = BackupFileBridge.createPickerFreeExportUri(context)
        assertThat(uri).isNotNull()
        val file = File(uri!!.path!!)
        assertThat(uri?.scheme).isEqualTo("file")
        assertThat(file.exists()).isTrue()

        assertThat(BackupFileBridge.finishPickerFreeExport(context, uri!!, success = false)).isFalse()
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun pickerFreeImport_listsAppSpecificBackupsWithoutAFilePicker() {
        val backup = BackupFileBridge.createExportFile(context).apply {
            writeText("{}")
        }

        val candidates = BackupFileBridge.listPickerFreeBackups(context)

        assertThat(candidates).hasSize(1)
        assertThat(candidates.single().displayName).isEqualTo(backup.name)
        assertThat(candidates.single().uri.scheme).isEqualTo("file")
    }

    @Test
    fun managedBackups_listsAndDeletesOnlyStreamVaultCreatedFiles() {
        val backup = BackupFileBridge.createExportFile(context).apply {
            writeText("{}")
        }
        File(cacheDir, "Backups/not-created-by-streamvault.json").apply {
            parentFile?.mkdirs()
            writeText("{}")
        }

        val candidates = BackupFileBridge.listManagedBackups(context)

        assertThat(candidates.map { it.displayName }).containsExactly(backup.name)
        assertThat(BackupFileBridge.deleteManagedBackup(context, candidates.single())).isTrue()
        assertThat(backup.exists()).isFalse()
    }

    private class GeneratedInputStream(private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining == 0L) return -1
            remaining--
            return 0
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0L) return -1
            val count = minOf(remaining, length.toLong()).toInt()
            java.util.Arrays.fill(buffer, offset, offset + count, 0.toByte())
            remaining -= count
            return count
        }
    }
}
