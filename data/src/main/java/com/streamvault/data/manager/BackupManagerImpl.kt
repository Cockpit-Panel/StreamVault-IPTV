package com.streamvault.data.manager

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonWriter
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.EpisodeDao
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.RecordingScheduleDao
import com.streamvault.data.local.dao.VirtualGroupDao
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.RecordingScheduleEntity
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.preferences.ParentalPinBackupData
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.manager.BackupData
import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupImportResult
import com.streamvault.domain.manager.BackupManager
import com.streamvault.domain.manager.BackupPreview
import com.streamvault.domain.manager.RecordingScheduleImportDisposition
import com.streamvault.domain.manager.RecordingScheduleImportOutcome
import com.streamvault.domain.manager.RecordingScheduleImportSummary
import com.streamvault.domain.manager.ProtectedCategoryBackup
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.manager.ScheduledRecordingBackup
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.RecordingRecurrence
import com.streamvault.domain.model.RecordingRequest
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.CategoryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.lang.reflect.Type
import java.security.MessageDigest
import java.util.zip.CRC32
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesRepository: PreferencesRepository,
    private val credentialCrypto: CredentialCrypto,
    private val providerDao: ProviderDao,
    private val favoriteDao: FavoriteDao,
    private val virtualGroupDao: VirtualGroupDao,
    private val playbackHistoryDao: PlaybackHistoryDao,
    private val movieDao: MovieDao,
    private val episodeDao: EpisodeDao,
    private val categoryRepository: CategoryRepository,
    private val recordingScheduleDao: RecordingScheduleDao,
    private val recordingManager: RecordingManager,
    private val transactionRunner: DatabaseTransactionRunner,
    private val gson: Gson
) : BackupManager {

    override suspend fun exportConfig(uriString: String): com.streamvault.domain.model.Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val parentalPinBackup = preferencesRepository.exportParentalPinBackup()
            val providerEntities = providerDao.getAll().first()
            
            // 1. Gather Data
            val prefs = buildMap<String, String> {
                put("parentalControlLevel", preferencesRepository.parentalControlLevel.first().toString())
                put("parentalPinHash", parentalPinBackup?.hash ?: "")
                put("parentalPinSalt", parentalPinBackup?.saltBase64 ?: "")
                put("appLanguage", preferencesRepository.appLanguage.first())
                put("appLandingDestination", preferencesRepository.appLandingDestination.first().storageValue)
                put("liveTvCategoryFilters", preferencesRepository.liveTvCategoryFilters.first().joinToString("\n"))
                put("liveTvQuickFilterVisibility", preferencesRepository.liveTvQuickFilterVisibility.first() ?: "always")
                put("playerMediaSessionEnabled", preferencesRepository.playerMediaSessionEnabled.first().toString())
                put("playerDecoderMode", preferencesRepository.playerDecoderMode.first().name)
                put("playerAudioOutputPreference", preferencesRepository.playerAudioOutputPreference.first().name)
                put("playerCompatibilityMemoryEnabled", preferencesRepository.playerCompatibilityMemoryEnabled.first().toString())
                put("playerSurfaceMode", preferencesRepository.playerSurfaceMode.first().name)
                put("playerLiveStreamFormatMode", preferencesRepository.playerLiveStreamFormatMode.first().name)
                put("playerVodHttpProtocolMode", preferencesRepository.playerVodHttpProtocolMode.first().name)
                put("playerPlaybackSpeed", preferencesRepository.playerPlaybackSpeed.first().toString())
                put("playerAudioVideoSyncEnabled", preferencesRepository.playerAudioVideoSyncEnabled.first().toString())
                put("playerAudioVideoOffsetMs", preferencesRepository.playerAudioVideoOffsetMs.first().toString())
                put("multiViewRespectProviderConnectionLimit", preferencesRepository.multiViewRespectProviderConnectionLimit.first().toString())
                put("preferredAudioLanguage", preferencesRepository.preferredAudioLanguage.first() ?: "auto")
                put("playerSubtitleTextScale", preferencesRepository.playerSubtitleTextScale.first().toString())
                put("playerSubtitleTextColor", preferencesRepository.playerSubtitleTextColor.first().toString())
                put("playerSubtitleBackgroundColor", preferencesRepository.playerSubtitleBackgroundColor.first().toString())
                put("playerWifiMaxVideoHeight", (preferencesRepository.playerWifiMaxVideoHeight.first() ?: 0).toString())
                put("playerEthernetMaxVideoHeight", (preferencesRepository.playerEthernetMaxVideoHeight.first() ?: 0).toString())
                put("guideDensity", preferencesRepository.guideDensity.first() ?: "")
                put("guideChannelMode", preferencesRepository.guideChannelMode.first() ?: "")
                put("guideDefaultCategoryId", (preferencesRepository.guideDefaultCategoryId.first() ?: 0L).toString())
                put("guideFavoritesOnly", preferencesRepository.guideFavoritesOnly.first().toString())
                put("guideAnchorTime", (preferencesRepository.guideAnchorTime.first() ?: 0L).toString())
                put("lastActiveProviderId", (preferencesRepository.lastActiveProviderId.first() ?: -1L).toString())
                put("promotedLiveGroupIds", preferencesRepository.promotedLiveGroupIds.first().sorted().joinToString(","))
                // D13 — hidden channels + hidden categories per provider (per ContentType for cats)
                providerEntities.forEach { provider ->
                    val hiddenChan = preferencesRepository.getHiddenChannelIds(provider.id).first()
                    if (hiddenChan.isNotEmpty()) {
                        put("hiddenChannels_${provider.id}", hiddenChan.sorted().joinToString(","))
                    }
                    ContentType.entries.forEach { type ->
                        val hiddenCat = preferencesRepository.getHiddenCategoryIds(provider.id, type).first()
                        if (hiddenCat.isNotEmpty()) {
                            put("hiddenCategories_${provider.id}_${type.name}", hiddenCat.sorted().joinToString(","))
                        }
                    }
                }
            }

            val providers = providerEntities.map { entity ->
                entity.toDomain().copy(
                    password = "",  // Strip credentials from backup export
                    username = entity.toDomain().username // Keep username for provider identification
                )
            }
            val providerIds = providerEntities.map { it.id }
            val providersById = providers.associateBy { it.id }

            // Gather all favorites across all types
            val liveFavs = providerIds.flatMap { providerId ->
                favoriteDao.getAllByType(providerId, "LIVE").first().map { it.toDomain() }
            }
            val movieFavs = providerIds.flatMap { providerId ->
                favoriteDao.getAllByType(providerId, "MOVIE").first().map { it.toDomain() }
            }
            val seriesFavs = providerIds.flatMap { providerId ->
                favoriteDao.getAllByType(providerId, "SERIES").first().map { it.toDomain() }
            }
            val allFavorites = liveFavs + movieFavs + seriesFavs

            // Gather all custom groups
            val liveGroups = providerIds.flatMap { providerId ->
                virtualGroupDao.getByType(providerId, "LIVE").first().map { it.toDomain() }
            }
            val movieGroups = providerIds.flatMap { providerId ->
                virtualGroupDao.getByType(providerId, "MOVIE").first().map { it.toDomain() }
            }
            val seriesGroups = providerIds.flatMap { providerId ->
                virtualGroupDao.getByType(providerId, "SERIES").first().map { it.toDomain() }
            }
            val allGroups = liveGroups + movieGroups + seriesGroups

            val playbackHistory = playbackHistoryDao.getAllSync().map { it.toDomain() }
            val multiViewPresets = mapOf(
                "preset_1" to preferencesRepository.getMultiViewPreset(0).first(),
                "preset_2" to preferencesRepository.getMultiViewPreset(1).first(),
                "preset_3" to preferencesRepository.getMultiViewPreset(2).first()
            )
            val protectedCategories = providers.flatMap { provider ->
                categoryRepository.getCategories(provider.id).first()
                    .filter { it.isUserProtected }
                    .map { category ->
                        ProtectedCategoryBackup(
                            providerServerUrl = provider.serverUrl,
                            providerUsername = provider.username,
                            providerStalkerMacAddress = provider.stalkerMacAddress.takeIf { it.isNotBlank() },
                            categoryId = category.id,
                            categoryName = category.name,
                            type = category.type
                        )
                    }
            }
            val scheduledRecordings = recordingManager.observeRecordingItems().first()
                .filter { it.status == RecordingStatus.SCHEDULED && it.scheduledEndMs > System.currentTimeMillis() }
                .mapNotNull { item ->
                    val provider = providersById[item.providerId] ?: return@mapNotNull null
                    item.toScheduledRecordingBackup(
                        provider = provider,
                        schedule = item.scheduleId?.let { scheduleId -> recordingScheduleDao.getById(scheduleId) }
                    )
                }
                .normalizedRecurringBackups()

            val backupData = BackupData(
                version = CURRENT_BACKUP_VERSION,
                preferences = prefs,
                providers = providers,
                favorites = allFavorites,
                virtualGroups = allGroups,
                playbackHistory = playbackHistory,
                multiViewPresets = multiViewPresets,
                protectedCategories = protectedCategories,
                scheduledRecordings = scheduledRecordings
            )

            // Compute checksum over the data without checksum field
            val backupWithChecksum = backupData.copy(checksum = buildSha256Checksum(backupData))

            // 2. Serialize and write to URI
            openBackupOutputStream(uriString)?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writeBackupDataJson(writer, backupWithChecksum)
                }
            } ?: return@withContext com.streamvault.domain.model.Result.error("Failed to open output stream")

            com.streamvault.domain.model.Result.success(Unit)
        } catch (e: Exception) {
            com.streamvault.domain.model.Result.error("Failed to export backup: ${e.message}", e)
        }
    }

    override suspend fun inspectBackup(uriString: String): Result<BackupPreview> = withContext(Dispatchers.IO) {
        try {
            val backupData = readBackupData(uriString)
                ?: return@withContext Result.error("Failed to open input stream")
            if (backupData.version > CURRENT_BACKUP_VERSION) {
                return@withContext Result.error("Unsupported backup version")
            }
            if (backupData.isStructurallyEmpty()) {
                return@withContext Result.error("Backup file does not contain any importable data")
            }
            if (!verifyChecksum(backupData)) {
                return@withContext Result.error("Backup file is corrupted (checksum mismatch)")
            }

            val existingProviders = providerDao.getAll().first()
            val existingProviderIds = existingProviders.map { it.id }
            val existingGroups = buildList {
                existingProviderIds.forEach { providerId ->
                    addAll(virtualGroupDao.getByType(providerId, "LIVE").first())
                    addAll(virtualGroupDao.getByType(providerId, "MOVIE").first())
                    addAll(virtualGroupDao.getByType(providerId, "SERIES").first())
                }
            }
            val existingFavorites = buildList {
                existingProviderIds.forEach { providerId ->
                    addAll(favoriteDao.getAllByType(providerId, "LIVE").first())
                    addAll(favoriteDao.getAllByType(providerId, "MOVIE").first())
                    addAll(favoriteDao.getAllByType(providerId, "SERIES").first())
                }
            }
            val existingHistory = playbackHistoryDao.getAllSync()
            val existingProtectedCategories = existingProviders.flatMap { provider ->
                categoryRepository.getCategories(provider.id).first()
                    .filter { it.isUserProtected }
                    .map { category -> Triple(provider, category.name.lowercase(), category.type) }
            }
            val existingScheduledRecordings = recordingManager.observeRecordingItems().first()
                .filter { it.status == RecordingStatus.SCHEDULED }

            val providerConflicts = backupData.providers.orEmpty().count { incoming ->
                existingProviders.findMatchingProvider(
                    serverUrl = incoming.serverUrl,
                    username = incoming.username,
                    stalkerMacAddress = incoming.stalkerMacAddress
                ) != null
            }
            val groupConflicts = backupData.virtualGroups.orEmpty().count { incoming ->
                existingGroups.any { it.name.equals(incoming.name, ignoreCase = true) && it.contentType == incoming.contentType }
            }
            val favoriteConflicts = backupData.favorites.orEmpty().count { incoming ->
                existingFavorites.any {
                    it.contentId == incoming.contentId &&
                        it.contentType == incoming.contentType &&
                        it.groupId == incoming.groupId
                }
            }
            val historyConflicts = backupData.playbackHistory.orEmpty().count { incoming ->
                existingHistory.any {
                    it.contentId == incoming.contentId &&
                        it.contentType == incoming.contentType &&
                        it.providerId == incoming.providerId
                }
            }
            val protectedCategoryConflicts = backupData.protectedCategories.orEmpty().count { incoming ->
                val provider = existingProviders.findMatchingProvider(
                    serverUrl = incoming.providerServerUrl,
                    username = incoming.providerUsername,
                    stalkerMacAddress = incoming.providerStalkerMacAddress
                ) ?: return@count false
                existingProtectedCategories.any { (existingProvider, categoryName, type) ->
                    existingProvider.id == provider.id &&
                        categoryName == incoming.categoryName.lowercase() &&
                        type == incoming.type
                }
            }
            val recordingConflicts = backupData.scheduledRecordings.orEmpty().normalizedRecurringBackups().count { incoming ->
                val provider = existingProviders.findMatchingProvider(
                    serverUrl = incoming.providerServerUrl,
                    username = incoming.providerUsername,
                    stalkerMacAddress = incoming.providerStalkerMacAddress
                ) ?: return@count false
                existingScheduledRecordings.any {
                    it.providerId == provider.id &&
                        it.scheduledStartMs == incoming.scheduledStartMs &&
                        (it.channelId == incoming.channelId || it.streamUrl == incoming.streamUrl)
                }
            }

            Result.success(
                BackupPreview(
                    version = backupData.version,
                    providerCount = backupData.providers.orEmpty().size,
                    favoriteCount = backupData.favorites.orEmpty().size,
                    groupCount = backupData.virtualGroups.orEmpty().size,
                    playbackHistoryCount = backupData.playbackHistory.orEmpty().size,
                    multiViewPresetCount = backupData.multiViewPresets.orEmpty().count { it.value.isNotEmpty() },
                    preferenceCount = backupData.preferences.orEmpty().size,
                    protectedCategoryCount = backupData.protectedCategories.orEmpty().size,
                    scheduledRecordingCount = backupData.scheduledRecordings.orEmpty().normalizedRecurringBackups().size,
                    providerConflicts = providerConflicts,
                    favoriteConflicts = favoriteConflicts,
                    groupConflicts = groupConflicts,
                    historyConflicts = historyConflicts,
                    protectedCategoryConflicts = protectedCategoryConflicts,
                    recordingConflicts = recordingConflicts
                )
            )
        } catch (e: Exception) {
            Result.error("Failed to inspect backup: ${e.message}", e)
        }
    }

    override suspend fun importConfig(
        uriString: String,
        plan: BackupImportPlan
    ): com.streamvault.domain.model.Result<BackupImportResult> = withContext(Dispatchers.IO) {
        try {
            val backupData = readBackupData(uriString)
                ?: return@withContext com.streamvault.domain.model.Result.error("Failed to open input stream")

            if (backupData.version > CURRENT_BACKUP_VERSION) {
                return@withContext com.streamvault.domain.model.Result.error("Unsupported backup version")
            }
            if (backupData.isStructurallyEmpty()) {
                return@withContext com.streamvault.domain.model.Result.error("Backup file does not contain any importable data")
            }
            if (!verifyChecksum(backupData)) {
                return@withContext com.streamvault.domain.model.Result.error("Backup file is corrupted (checksum mismatch)")
            }

            var storedProviders = providerDao.getAllSync()

            val importedSections = mutableListOf<String>()
            val skippedSections = mutableListOf<String>()

            val importedPreferences = if (plan.importPreferences) backupData.preferences else null
            if (!plan.importPreferences) {
                skippedSections += "Preferences"
            }

            val roomRestoreResult = restoreRoomBackedSections(
                backupData = backupData,
                plan = plan,
                initialStoredProviders = storedProviders
            )
            storedProviders = roomRestoreResult.storedProviders
            importedSections += roomRestoreResult.importedSections
            skippedSections += roomRestoreResult.skippedSections

            if (plan.importPreferences) {
                importedPreferences?.let { prefs ->
                    restorePreferences(prefs)
                    importedSections += "Preferences"
                } ?: run { skippedSections += "Preferences" }
            }

            if (plan.importMultiViewPresets) {
                backupData.multiViewPresets?.let { presets ->
                preferencesRepository.setMultiViewPreset(0, presets["preset_1"].orEmpty())
                preferencesRepository.setMultiViewPreset(1, presets["preset_2"].orEmpty())
                preferencesRepository.setMultiViewPreset(2, presets["preset_3"].orEmpty())
                    importedSections += "Split Screen Presets"
                } ?: run { skippedSections += "Split Screen Presets" }
            } else {
                skippedSections += "Split Screen Presets"
            }

            val recordingScheduleImport = if (plan.importRecordingSchedules) {
                backupData.scheduledRecordings?.let { recordings ->
                    importScheduledRecordingBackups(
                        recordings = recordings.normalizedRecurringBackups(),
                        storedProviders = storedProviders,
                        existingSchedules = recordingManager.observeRecordingItems().first()
                            .filter { it.status == RecordingStatus.SCHEDULED }
                            .toMutableList(),
                        conflictStrategy = plan.conflictStrategy,
                        recordingManager = recordingManager
                    )
                }
            } else {
                null
            }

            if (plan.importRecordingSchedules) {
                when {
                    recordingScheduleImport == null -> skippedSections += "Recording Schedules"
                    recordingScheduleImport.importedCount > 0 -> importedSections += "Recording Schedules"
                    else -> skippedSections += "Recording Schedules"
                }
            } else {
                skippedSections += "Recording Schedules"
            }

            com.streamvault.domain.model.Result.success(
                BackupImportResult(
                    importedSections = importedSections.distinct(),
                    skippedSections = skippedSections.distinct(),
                    recordingScheduleImport = recordingScheduleImport
                )
            )
        } catch (e: Exception) {
            com.streamvault.domain.model.Result.error("Failed to import backup: ${e.message}", e)
        }
    }

    private fun verifyChecksum(backupData: BackupData): Boolean {
        val storedChecksum = backupData.checksum ?: return true // no checksum = legacy backup, skip verification
        val dataWithoutChecksum = backupData.copy(checksum = null)

        return if (storedChecksum.startsWith(SHA256_PREFIX)) {
            buildSha256Checksum(dataWithoutChecksum) == storedChecksum
        } else {
            verifyLegacyCrc32Checksum(dataWithoutChecksum, storedChecksum)
        }
    }

    private fun buildSha256Checksum(backupData: BackupData): String {
        val digest = MessageDigest.getInstance("SHA-256")
        OutputStreamWriter(MessageDigestOutputStream(digest), Charsets.UTF_8).use { writer ->
            writeBackupDataJson(writer, backupData.copy(checksum = null))
        }
        return SHA256_PREFIX + digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    private fun verifyLegacyCrc32Checksum(backupData: BackupData, checksum: String): Boolean {
        val crc = CRC32()
        OutputStreamWriter(Crc32OutputStream(crc), Charsets.UTF_8).use { writer ->
            writeBackupDataJson(writer, backupData.copy(checksum = null))
        }
        return crc.value.toString(16) == checksum
    }

    private fun writeBackupDataJson(writer: Writer, backupData: BackupData) {
        JsonWriter(writer).use { jsonWriter ->
            jsonWriter.beginObject()
            jsonWriter.name("version").value(backupData.version.toLong())
            backupData.checksum?.let { checksum ->
                jsonWriter.name("checksum").value(checksum)
            }
            writeNamedJsonField(jsonWriter, "preferences", backupData.preferences, MAP_STRING_STRING_TYPE)
            writeNamedJsonField(jsonWriter, "providers", backupData.providers, PROVIDER_LIST_TYPE)
            writeNamedJsonField(jsonWriter, "favorites", backupData.favorites, FAVORITE_LIST_TYPE)
            writeNamedJsonField(jsonWriter, "virtualGroups", backupData.virtualGroups, VIRTUAL_GROUP_LIST_TYPE)
            writeNamedJsonField(jsonWriter, "playbackHistory", backupData.playbackHistory, PLAYBACK_HISTORY_LIST_TYPE)
            writeNamedJsonField(jsonWriter, "multiViewPresets", backupData.multiViewPresets, MULTIVIEW_PRESETS_TYPE)
            writeNamedJsonField(jsonWriter, "protectedCategories", backupData.protectedCategories, PROTECTED_CATEGORY_LIST_TYPE)
            writeNamedJsonField(jsonWriter, "scheduledRecordings", backupData.scheduledRecordings, SCHEDULED_RECORDING_LIST_TYPE)
            jsonWriter.endObject()
        }
    }

    private fun <T> writeNamedJsonField(jsonWriter: JsonWriter, name: String, value: T?, type: Type) {
        if (value == null) return
        jsonWriter.name(name)
        gson.toJson(value, type, jsonWriter)
    }

    private class MessageDigestOutputStream(
        private val digest: MessageDigest
    ) : OutputStream() {
        override fun write(b: Int) {
            digest.update(b.toByte())
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            digest.update(b, off, len)
        }
    }

    private class Crc32OutputStream(
        private val crc32: CRC32
    ) : OutputStream() {
        override fun write(b: Int) {
            crc32.update(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            crc32.update(b, off, len)
        }
    }

    private fun readBackupData(uriString: String): BackupData? {
        return openBackupInputStream(uriString)?.use { inputStream ->
            InputStreamReader(inputStream).use { reader ->
                gson.fromJson(reader, BackupData::class.java)
            }
        }
    }

    private fun openBackupOutputStream(uriString: String): OutputStream? {
        return if (uriString.isFileUriString()) {
            val target = uriString.toFileUriTarget() ?: return null
            target.parentFile?.mkdirs()
            FileOutputStream(target, false)
        } else {
            val uri = Uri.parse(uriString)
            context.contentResolver.openOutputStream(uri, "wt")
                ?: context.contentResolver.openOutputStream(uri)
        }
    }

    private fun openBackupInputStream(uriString: String) =
        if (uriString.isFileUriString()) {
            uriString.toFileUriTarget()?.takeIf { it.isFile }?.let(::FileInputStream)
        } else {
            val uri = Uri.parse(uriString)
            context.contentResolver.openInputStream(uri)
        }

    private fun String.isFileUriString(): Boolean =
        startsWith("$FILE_URI_SCHEME:", ignoreCase = true)

    private fun String.toFileUriTarget(): File? =
        runCatching { File(java.net.URI(this)) }.getOrNull()
            ?: Uri.parse(this).path?.let(::File)

    private fun BackupData.isStructurallyEmpty(): Boolean =
        preferences.isNullOrEmpty() &&
            providers.isNullOrEmpty() &&
            favorites.isNullOrEmpty() &&
            virtualGroups.isNullOrEmpty() &&
            playbackHistory.isNullOrEmpty() &&
            multiViewPresets.orEmpty().all { it.value.isEmpty() } &&
            protectedCategories.isNullOrEmpty() &&
            scheduledRecordings.isNullOrEmpty()

    private suspend fun restorePreferences(prefs: Map<String, String>) {
        prefs["parentalControlLevel"]?.toIntOrNull()?.let {
            preferencesRepository.setParentalControlLevel(it)
        }
        preferencesRepository.restoreParentalPinBackup(
            ParentalPinBackupData(
                hash = prefs["parentalPinHash"].orEmpty(),
                saltBase64 = prefs["parentalPinSalt"].orEmpty()
            ).takeIf { it.hash.isNotBlank() && it.saltBase64.isNotBlank() }
        )
        prefs["appLanguage"]?.takeIf { it.isNotBlank() }?.let { preferencesRepository.setAppLanguage(it) }
        prefs["appLandingDestination"]?.takeIf { it.isNotBlank() }?.let { savedDestination ->
            preferencesRepository.setAppLandingDestination(
                com.streamvault.domain.model.AppLandingDestination.fromStorage(savedDestination)
            )
        }
        prefs["liveTvCategoryFilters"]?.let { preferencesRepository.setLiveTvCategoryFilters(it.split('\n')) }
        prefs["liveTvQuickFilterVisibility"]?.takeIf { it.isNotBlank() }
            ?.let { preferencesRepository.setLiveTvQuickFilterVisibility(it) }
        prefs["playerMediaSessionEnabled"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setPlayerMediaSessionEnabled(it) }
        prefs["playerDecoderMode"]?.takeIf { it.isNotBlank() }?.let { savedMode ->
            val decoderMode = com.streamvault.domain.model.DecoderMode.entries
                .firstOrNull { entry -> entry.name == savedMode }
            if (decoderMode != null) {
                preferencesRepository.setPlayerDecoderMode(decoderMode)
            }
        }
        prefs["playerAudioOutputPreference"]?.takeIf { it.isNotBlank() }?.let { savedPreference ->
            val preference = com.streamvault.domain.model.AudioOutputPreference.entries
                .firstOrNull { entry -> entry.name == savedPreference }
            if (preference != null) {
                preferencesRepository.setPlayerAudioOutputPreference(preference)
            }
        }
        prefs["playerCompatibilityMemoryEnabled"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setPlayerCompatibilityMemoryEnabled(it) }
        prefs["playerSurfaceMode"]?.takeIf { it.isNotBlank() }?.let { savedMode ->
            val surfaceMode = com.streamvault.domain.model.PlayerSurfaceMode.entries
                .firstOrNull { entry -> entry.name == savedMode }
            if (surfaceMode != null) {
                preferencesRepository.setPlayerSurfaceMode(surfaceMode)
            }
        }
        prefs["playerLiveStreamFormatMode"]?.takeIf { it.isNotBlank() }?.let { savedMode ->
            val formatMode = com.streamvault.domain.model.LiveStreamFormatMode.entries
                .firstOrNull { entry -> entry.name == savedMode }
            if (formatMode != null) {
                preferencesRepository.setPlayerLiveStreamFormatMode(formatMode)
            }
        }
        (prefs["playerVodHttpProtocolMode"] ?: prefs["playerMovieHttpProtocolMode"])?.takeIf { it.isNotBlank() }?.let { savedMode ->
            val protocolMode = com.streamvault.domain.model.VodHttpProtocolMode.entries
                .firstOrNull { entry -> entry.name == savedMode }
            if (protocolMode != null) {
                preferencesRepository.setPlayerVodHttpProtocolMode(protocolMode)
            }
        }
        prefs["playerPlaybackSpeed"]?.toFloatOrNull()?.let { preferencesRepository.setPlayerPlaybackSpeed(it) }
        prefs["playerAudioVideoSyncEnabled"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setPlayerAudioVideoSyncEnabled(it) }
        prefs["playerAudioVideoOffsetMs"]?.toIntOrNull()?.let {
            preferencesRepository.setPlayerAudioVideoOffsetMs(it)
        }
        prefs["multiViewRespectProviderConnectionLimit"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setMultiViewRespectProviderConnectionLimit(it) }
        preferencesRepository.setPreferredAudioLanguage(prefs["preferredAudioLanguage"])
        prefs["playerSubtitleTextScale"]?.toFloatOrNull()?.let { preferencesRepository.setPlayerSubtitleTextScale(it) }
        prefs["playerSubtitleTextColor"]?.toIntOrNull()?.let { preferencesRepository.setPlayerSubtitleTextColor(it) }
        prefs["playerSubtitleBackgroundColor"]?.toIntOrNull()?.let { preferencesRepository.setPlayerSubtitleBackgroundColor(it) }
        preferencesRepository.setPlayerWifiMaxVideoHeight(prefs["playerWifiMaxVideoHeight"]?.toIntOrNull()?.takeIf { it > 0 })
        preferencesRepository.setPlayerEthernetMaxVideoHeight(prefs["playerEthernetMaxVideoHeight"]?.toIntOrNull()?.takeIf { it > 0 })
        prefs["guideDensity"]?.takeIf { it.isNotBlank() }?.let { preferencesRepository.setGuideDensity(it) }
        prefs["guideChannelMode"]?.takeIf { it.isNotBlank() }?.let { preferencesRepository.setGuideChannelMode(it) }
        prefs["guideDefaultCategoryId"]?.toLongOrNull()?.takeIf { it != 0L }?.let {
            preferencesRepository.setGuideDefaultCategoryId(it)
        }
        prefs["guideFavoritesOnly"]?.toBooleanStrictOrNull()?.let { preferencesRepository.setGuideFavoritesOnly(it) }
        prefs["guideAnchorTime"]?.toLongOrNull()?.takeIf { it > 0L }?.let { preferencesRepository.setGuideAnchorTime(it) }
        prefs["promotedLiveGroupIds"]?.let { token ->
            preferencesRepository.setPromotedLiveGroupIds(
                token.split(",").mapNotNull { it.toLongOrNull() }.toSet()
            )
        }
        // D13 — restore hidden channels per provider
        prefs.entries
            .filter { it.key.startsWith("hiddenChannels_") }
            .forEach { (key, value) ->
                val providerId = key.removePrefix("hiddenChannels_").toLongOrNull() ?: return@forEach
                val ids = value.split(",").mapNotNull { it.toLongOrNull() }.toSet()
                preferencesRepository.setHiddenChannelIds(providerId, ids)
            }
        // D13 — restore hidden categories per provider per content type
        prefs.entries
            .filter { it.key.startsWith("hiddenCategories_") }
            .forEach { (key, value) ->
                val rest = key.removePrefix("hiddenCategories_").split("_")
                if (rest.size < 2) return@forEach
                val providerId = rest[0].toLongOrNull() ?: return@forEach
                val type = ContentType.entries.firstOrNull { it.name == rest[1] } ?: return@forEach
                val ids = value.split(",").mapNotNull { it.toLongOrNull() }.toSet()
                preferencesRepository.setHiddenCategoryIds(providerId, type, ids)
            }
    }

    private suspend fun restoreRoomBackedSections(
        backupData: BackupData,
        plan: BackupImportPlan,
        initialStoredProviders: List<ProviderEntity>
    ): RoomRestoreResult {
        if (!plan.importProviders && !plan.importSavedLibrary && !plan.importPlaybackHistory) {
            return RoomRestoreResult(
                storedProviders = initialStoredProviders,
                importedSections = emptyList(),
                skippedSections = buildList {
                    add("Providers")
                    add("Saved Library")
                    add("Playback History")
                }
            )
        }

        var storedProviders = initialStoredProviders
        val importedSections = mutableListOf<String>()
        val skippedSections = mutableListOf<String>()

        transactionRunner.inTransaction {
            if (plan.importProviders) {
                backupData.providers?.let { providers ->
                    providers.forEach { provider ->
                        val existing = storedProviders.findMatchingProvider(
                            serverUrl = provider.serverUrl,
                            username = provider.username,
                            stalkerMacAddress = provider.stalkerMacAddress
                        )
                        if (existing != null && plan.conflictStrategy == BackupConflictStrategy.KEEP_EXISTING) {
                            return@forEach
                        }
                        val entity = provider.copy(
                            id = existing?.id ?: 0L
                        ).toSecureEntityForBackup(credentialCrypto)
                        providerDao.insert(entity)
                    }
                    storedProviders = providerDao.getAllSync()
                    backupData.preferences
                        ?.get("lastActiveProviderId")
                        ?.toLongOrNull()
                        ?.let { backupProviderId ->
                            resolveProviderIdMap(storedProviders, providers)[backupProviderId]
                        }
                        ?.let { resolvedProviderId ->
                            providerDao.setActive(resolvedProviderId)
                        }
                    importedSections += "Providers"
                } ?: run { skippedSections += "Providers" }
            } else {
                skippedSections += "Providers"
            }

            if (plan.importSavedLibrary) {
                restoreSavedLibrary(
                    backupData = backupData,
                    storedProviders = storedProviders,
                    conflictStrategy = plan.conflictStrategy
                )
                importedSections += "Saved Library"
            } else {
                skippedSections += "Saved Library"
            }

            if (plan.importPlaybackHistory) {
                backupData.playbackHistory?.let { history ->
                    restorePlaybackHistory(
                        history = history,
                        storedProviders = storedProviders,
                        backupProviders = backupData.providers.orEmpty(),
                        conflictStrategy = plan.conflictStrategy
                    )
                    importedSections += "Playback History"
                } ?: run { skippedSections += "Playback History" }
            } else {
                skippedSections += "Playback History"
            }
        }

        return RoomRestoreResult(
            storedProviders = storedProviders,
            importedSections = importedSections,
            skippedSections = skippedSections
        )
    }

    private suspend fun restoreSavedLibrary(
        backupData: BackupData,
        storedProviders: List<ProviderEntity>,
        conflictStrategy: BackupConflictStrategy
    ) {
        val providerIdMap = resolveProviderIdMap(storedProviders, backupData.providers.orEmpty())
        val groupIdMap = mutableMapOf<Long, Long>()
        backupData.virtualGroups?.let { groups ->
            val existingGroups = buildList {
                providerIdMap.values.distinct().forEach { providerId ->
                    addAll(virtualGroupDao.getByType(providerId, "LIVE").first())
                    addAll(virtualGroupDao.getByType(providerId, "MOVIE").first())
                    addAll(virtualGroupDao.getByType(providerId, "SERIES").first())
                }
            }
            groups.forEach { group ->
                val resolvedProviderId = providerIdMap[group.providerId] ?: return@forEach
                val conflict = existingGroups.firstOrNull {
                    it.providerId == resolvedProviderId &&
                        it.name.equals(group.name, ignoreCase = true) &&
                        it.contentType == group.contentType
                }
                if (conflict != null && conflictStrategy == BackupConflictStrategy.KEEP_EXISTING) {
                    groupIdMap[group.id] = conflict.id
                    return@forEach
                }
                val insertedId = virtualGroupDao.insert(
                    group.copy(id = 0L, providerId = resolvedProviderId).toEntity()
                )
                groupIdMap[group.id] = insertedId
            }
        }

        backupData.favorites?.let { favorites ->
            favorites.forEach { favorite ->
                val resolvedProviderId = providerIdMap[favorite.providerId] ?: return@forEach
                val resolvedGroupId = favorite.groupId?.let { groupIdMap[it] }
                val existing = favoriteDao.get(
                    providerId = resolvedProviderId,
                    contentId = favorite.contentId,
                    contentType = favorite.contentType.name,
                    groupId = resolvedGroupId
                )
                if (existing != null && conflictStrategy == BackupConflictStrategy.KEEP_EXISTING) {
                    return@forEach
                }
                favoriteDao.insert(
                    favorite.copy(
                        id = 0L,
                        providerId = resolvedProviderId,
                        groupId = resolvedGroupId
                    ).toEntity()
                )
            }
        }

        val categoriesByProviderId = mutableMapOf<Long, List<com.streamvault.domain.model.Category>>()
        backupData.protectedCategories?.forEach { protectedCategory ->
            val provider = storedProviders.findMatchingProvider(
                serverUrl = protectedCategory.providerServerUrl,
                username = protectedCategory.providerUsername,
                stalkerMacAddress = protectedCategory.providerStalkerMacAddress
            ) ?: return@forEach
            val categories = categoriesByProviderId.getOrPut(provider.id) {
                categoryRepository.getCategories(provider.id).first()
            }
            val resolvedCategory = categories.firstOrNull {
                it.type == protectedCategory.type && it.id == protectedCategory.categoryId
            } ?: categories.firstOrNull {
                it.type == protectedCategory.type &&
                    it.name.equals(protectedCategory.categoryName, ignoreCase = true)
            }
            if (resolvedCategory == null) {
                return@forEach
            }
            if (conflictStrategy == BackupConflictStrategy.KEEP_EXISTING && resolvedCategory.isUserProtected) {
                return@forEach
            }
            categoryRepository.setCategoryProtection(
                providerId = provider.id,
                categoryId = resolvedCategory.id,
                type = resolvedCategory.type,
                isProtected = true
            )
        }
    }

    private fun resolveProviderIdMap(
        storedProviders: List<ProviderEntity>,
        backupProviders: List<com.streamvault.domain.model.Provider>
    ): Map<Long, Long> = backupProviders.mapNotNull { provider ->
        storedProviders.findMatchingProvider(
            serverUrl = provider.serverUrl,
            username = provider.username,
            stalkerMacAddress = provider.stalkerMacAddress
        )?.let { stored ->
            provider.id to stored.id
        }
    }.toMap()

    private suspend fun restorePlaybackHistory(
        history: List<com.streamvault.domain.model.PlaybackHistory>,
        storedProviders: List<ProviderEntity>,
        backupProviders: List<com.streamvault.domain.model.Provider>,
        conflictStrategy: BackupConflictStrategy
    ) {
        val providerIdMap = resolveProviderIdMap(storedProviders, backupProviders)

        if (providerIdMap.isEmpty()) {
            return
        }

        val providersToResync = when (conflictStrategy) {
            BackupConflictStrategy.REPLACE_EXISTING -> providerIdMap.values.toMutableSet()
            BackupConflictStrategy.KEEP_EXISTING -> linkedSetOf()
        }

        if (conflictStrategy == BackupConflictStrategy.REPLACE_EXISTING) {
            providerIdMap.values.distinct().forEach { providerId ->
                playbackHistoryDao.deleteByProvider(providerId)
            }
        }

        history.forEach { item ->
            val resolvedProviderId = providerIdMap[item.providerId] ?: return@forEach
            if (conflictStrategy == BackupConflictStrategy.KEEP_EXISTING) {
                val existing = playbackHistoryDao.get(
                    contentId = item.contentId,
                    contentType = item.contentType.name,
                    providerId = resolvedProviderId
                )
                if (existing != null) return@forEach
            }
            playbackHistoryDao.insertOrUpdate(item.copy(providerId = resolvedProviderId).toEntity())
            providersToResync += resolvedProviderId
        }

        providersToResync.forEach { providerId ->
            movieDao.syncWatchProgressFromHistoryByProvider(providerId)
            episodeDao.syncWatchProgressFromHistoryByProvider(providerId)
        }
    }

    private data class RoomRestoreResult(
        val storedProviders: List<ProviderEntity>,
        val importedSections: List<String>,
        val skippedSections: List<String>
    )
}

internal fun com.streamvault.domain.model.RecordingItem.toScheduledRecordingBackup(
    provider: com.streamvault.domain.model.Provider,
    schedule: RecordingScheduleEntity?
): ScheduledRecordingBackup {
    val requestedStart = schedule?.requestedStartMs ?: scheduledStartMs
    val requestedEnd = schedule?.requestedEndMs ?: scheduledEndMs
    val paddingBeforeMs = (requestedStart - scheduledStartMs).coerceAtLeast(0L)
    val paddingAfterMs = (scheduledEndMs - requestedEnd).coerceAtLeast(0L)
    return ScheduledRecordingBackup(
        providerServerUrl = provider.serverUrl,
        providerUsername = provider.username,
        providerStalkerMacAddress = provider.stalkerMacAddress.takeIf { it.isNotBlank() },
        channelId = channelId,
        channelName = channelName,
        streamUrl = streamUrl,
        scheduledStartMs = scheduledStartMs,
        scheduledEndMs = scheduledEndMs,
        requestedStartMs = requestedStart,
        requestedEndMs = requestedEnd,
        paddingBeforeMs = paddingBeforeMs,
        paddingAfterMs = paddingAfterMs,
        programTitle = programTitle,
        recurrence = recurrence,
        recurringRuleId = schedule?.recurringRuleId ?: recurringRuleId
    )
}

internal fun ScheduledRecordingBackup.toRecordingRequest(providerId: Long): RecordingRequest {
    val restoredRequestedStartMs = requestedStartMs ?: scheduledStartMs
    val restoredRequestedEndMs = requestedEndMs ?: scheduledEndMs
    val restoredPaddingBeforeMs = paddingBeforeMs
        ?: requestedStartMs?.let { (it - scheduledStartMs).coerceAtLeast(0L) }
        ?: 0L
    val restoredPaddingAfterMs = paddingAfterMs
        ?: requestedEndMs?.let { (scheduledEndMs - it).coerceAtLeast(0L) }
        ?: 0L
    return RecordingRequest(
        providerId = providerId,
        channelId = channelId,
        channelName = channelName,
        streamUrl = streamUrl,
        scheduledStartMs = restoredRequestedStartMs,
        scheduledEndMs = restoredRequestedEndMs,
        programTitle = programTitle,
        recurrence = recurrence,
        recurringRuleId = recurringRuleId,
        paddingBeforeMs = restoredPaddingBeforeMs,
        paddingAfterMs = restoredPaddingAfterMs
    )
}

internal fun List<ScheduledRecordingBackup>.normalizedRecurringBackups(): List<ScheduledRecordingBackup> {
    if (isEmpty()) return emptyList()
    val oneShot = filterNot { it.hasStableRecurringIdentity() }
    val recurring = filter { it.hasStableRecurringIdentity() }
        .groupBy { it.recurringRuleId!! }
        .values
        .map { group ->
            group.minWithOrNull(compareBy<ScheduledRecordingBackup> { it.requestedStartMs ?: it.scheduledStartMs }
                .thenBy { it.requestedEndMs ?: it.scheduledEndMs }
                .thenBy { it.scheduledStartMs }) ?: group.first()
        }
    return (oneShot + recurring).sortedBy { it.requestedStartMs ?: it.scheduledStartMs }
}

internal suspend fun importScheduledRecordingBackups(
    recordings: List<ScheduledRecordingBackup>,
    storedProviders: List<ProviderEntity>,
    existingSchedules: MutableList<com.streamvault.domain.model.RecordingItem>,
    conflictStrategy: BackupConflictStrategy,
    recordingManager: RecordingManager,
    nowMs: Long = System.currentTimeMillis()
): RecordingScheduleImportSummary {
    val outcomes = mutableListOf<RecordingScheduleImportOutcome>()
    recordings.forEach { scheduled ->
        if (scheduled.scheduledEndMs <= nowMs) {
            outcomes += scheduled.toImportOutcome(
                disposition = RecordingScheduleImportDisposition.SKIPPED_EXPIRED,
                reason = "Recording window has already ended."
            )
            return@forEach
        }

        val provider = storedProviders.findMatchingProvider(
            serverUrl = scheduled.providerServerUrl,
            username = scheduled.providerUsername,
            stalkerMacAddress = scheduled.providerStalkerMacAddress
        )
        if (provider == null) {
            outcomes += scheduled.toImportOutcome(
                disposition = RecordingScheduleImportDisposition.SKIPPED_MISSING_PROVIDER,
                reason = "Matching provider was not found during import."
            )
            return@forEach
        }

        val conflict = existingSchedules.firstOrNull {
            it.providerId == provider.id &&
                it.scheduledStartMs == scheduled.scheduledStartMs &&
                (it.channelId == scheduled.channelId || it.streamUrl == scheduled.streamUrl)
        }
        if (conflict != null && conflictStrategy == BackupConflictStrategy.KEEP_EXISTING) {
            outcomes += scheduled.toImportOutcome(
                disposition = RecordingScheduleImportDisposition.SKIPPED_EXISTING,
                reason = "Keeping existing schedule for the same provider, start time, and channel/stream."
            )
            return@forEach
        }
        if (conflict != null && conflictStrategy == BackupConflictStrategy.REPLACE_EXISTING) {
            when (val cancelResult = recordingManager.cancelRecording(conflict.id)) {
                is Result.Success -> existingSchedules.remove(conflict)
                is Result.Error -> {
                    outcomes += scheduled.toImportOutcome(
                        disposition = RecordingScheduleImportDisposition.FAILED,
                        reason = cancelResult.message
                    )
                    return@forEach
                }
                Result.Loading -> {
                    outcomes += scheduled.toImportOutcome(
                        disposition = RecordingScheduleImportDisposition.FAILED,
                        reason = "Cancellation did not complete."
                    )
                    return@forEach
                }
            }
        }

        when (val result = recordingManager.scheduleRecording(scheduled.toRecordingRequest(provider.id))) {
            is Result.Success -> {
                existingSchedules += result.data
                outcomes += scheduled.toImportOutcome(
                    disposition = if (conflict != null) {
                        RecordingScheduleImportDisposition.REPLACED_EXISTING
                    } else {
                        RecordingScheduleImportDisposition.IMPORTED
                    }
                )
            }
            is Result.Error -> {
                outcomes += scheduled.toImportOutcome(
                    disposition = RecordingScheduleImportDisposition.FAILED,
                    reason = result.message
                )
            }
            Result.Loading -> {
                outcomes += scheduled.toImportOutcome(
                    disposition = RecordingScheduleImportDisposition.FAILED,
                    reason = "Scheduling did not complete."
                )
            }
        }
    }
    return RecordingScheduleImportSummary(outcomes = outcomes)
}

private fun ScheduledRecordingBackup.toImportOutcome(
    disposition: RecordingScheduleImportDisposition,
    reason: String? = null
): RecordingScheduleImportOutcome = RecordingScheduleImportOutcome(
    channelName = channelName,
    programTitle = programTitle,
    scheduledStartMs = scheduledStartMs,
    scheduledEndMs = scheduledEndMs,
    recurrence = recurrence,
    disposition = disposition,
    reason = reason
)

private fun ScheduledRecordingBackup.hasStableRecurringIdentity(): Boolean =
    recurrence != RecordingRecurrence.NONE && !recurringRuleId.isNullOrBlank()

private fun com.streamvault.domain.model.Provider.toSecureEntityForBackup(
    credentialCrypto: CredentialCrypto
) = copy(password = credentialCrypto.encryptIfNeeded(password)).toEntity()

private fun Iterable<ProviderEntity>.findMatchingProvider(
    serverUrl: String,
    username: String,
    stalkerMacAddress: String?
): ProviderEntity? {
    val candidates = filter {
        it.serverUrl == serverUrl &&
            it.username == username
    }
    val normalizedMacAddress = stalkerMacAddress
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.uppercase()

    if (normalizedMacAddress != null) {
        return candidates.firstOrNull { it.stalkerMacAddress.equals(normalizedMacAddress, ignoreCase = true) }
    }

    return candidates.singleOrNull { it.stalkerMacAddress.isBlank() }
        ?: candidates.singleOrNull()
}

private const val SHA256_PREFIX = "sha256:"
private const val CURRENT_BACKUP_VERSION = 7
private const val FILE_URI_SCHEME = "file"
private val MAP_STRING_STRING_TYPE: Type = object : TypeToken<Map<String, String>>() {}.type
private val PROVIDER_LIST_TYPE: Type = object : TypeToken<List<com.streamvault.domain.model.Provider>>() {}.type
private val FAVORITE_LIST_TYPE: Type = object : TypeToken<List<com.streamvault.domain.model.Favorite>>() {}.type
private val VIRTUAL_GROUP_LIST_TYPE: Type = object : TypeToken<List<com.streamvault.domain.model.VirtualGroup>>() {}.type
private val PLAYBACK_HISTORY_LIST_TYPE: Type = object : TypeToken<List<com.streamvault.domain.model.PlaybackHistory>>() {}.type
private val MULTIVIEW_PRESETS_TYPE: Type = object : TypeToken<Map<String, List<Long>>>() {}.type
private val PROTECTED_CATEGORY_LIST_TYPE: Type = object : TypeToken<List<ProtectedCategoryBackup>>() {}.type
private val SCHEDULED_RECORDING_LIST_TYPE: Type = object : TypeToken<List<ScheduledRecordingBackup>>() {}.type
