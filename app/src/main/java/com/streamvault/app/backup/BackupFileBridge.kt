package com.streamvault.app.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.streamvault.app.BuildConfig
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object BackupFileBridge {
    const val MIME_TYPE_JSON = "application/json"
    private const val BACKUP_EXPORTS_DIR = "Backups"
    private const val BACKUP_IMPORTS_DIR = "backup_imports"
    private val exportNameFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    fun createExportFile(context: Context): File {
        val documentsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        return createExportFile(documentsDir)
    }

    /**
     * Creates an empty timestamped backup file under [baseDir]/Backups. Used for app-private folders
     * on a USB drive, which can be written with a plain `file://` URI (no FileProvider needed since
     * FileProvider cannot serve secondary external volumes).
     */
    fun createExportFile(baseDir: File): File {
        val backupsDir = File(baseDir, BACKUP_EXPORTS_DIR).apply { mkdirs() }
        return File(backupsDir, "streamvault_backup_${LocalDateTime.now().format(exportNameFormatter)}.json")
            .also { file ->
                file.parentFile?.mkdirs()
                if (!file.exists()) file.createNewFile()
            }
    }

    /** Lists previously exported `.json` backups under [baseDir]/Backups, newest first. */
    fun listBackupFiles(baseDir: File): List<File> =
        File(baseDir, BACKUP_EXPORTS_DIR).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun copyToImportInbox(context: Context, sourceUri: Uri): Uri? {
        pruneImportInbox(context)
        val inboxDir = File(context.cacheDir, BACKUP_IMPORTS_DIR).apply { mkdirs() }
        val targetFile = File(inboxDir, "streamvault_import_${System.currentTimeMillis()}.json")
        return runCatching {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            providerUriForFile(context, targetFile)
        }.getOrNull()
    }

    fun providerUriForFile(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
    }

    fun buildShareIntent(uri: Uri): Intent {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE_JSON
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(sendIntent, "Share StreamVault backup")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun pruneImportInbox(context: Context) {
        val cutoff = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
        File(context.cacheDir, BACKUP_IMPORTS_DIR)
            .listFiles()
            ?.filter { it.isFile && it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }
}