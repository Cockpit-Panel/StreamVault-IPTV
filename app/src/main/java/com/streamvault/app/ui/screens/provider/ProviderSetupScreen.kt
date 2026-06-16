package com.streamvault.app.ui.screens.provider

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import com.streamvault.app.ui.interaction.mouseClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.*
import com.streamvault.app.R
import com.streamvault.app.device.rememberIsTelevisionDevice
import com.streamvault.app.pairing.ProviderQrPairingState
import com.streamvault.app.pairing.ProviderQrPairingStatus
import com.streamvault.app.ui.components.dialogs.PremiumDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.app.ui.components.extractProgressFraction
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.components.shell.StatusPill
import com.streamvault.app.ui.screens.settings.BackupImportPreviewDialog
import com.streamvault.app.ui.theme.*
import com.streamvault.data.util.ProviderInputSanitizer
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import com.streamvault.domain.model.StalkerAuthMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import android.widget.Toast
import kotlin.coroutines.resume

// ??? Source type ?????????????????????????????????????????????????????????????

private enum class SourceType { XTREAM, STALKER, M3U_URL, M3U_FILE }

// ??? Screen ??????????????????????????????????????????????????????????????????

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderSetupScreen(
    onProviderAdded: () -> Unit,
    onBack: () -> Unit,
    editProviderId: Long? = null,
    initialImportUri: String? = null,
    viewModel: ProviderSetupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val knownLocalM3uUrls by viewModel.knownLocalM3uUrls.collectAsStateWithLifecycle()
    val pairingState by viewModel.pairingState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // ?? Local form state ??????????????????????????????????????????????????????
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var m3uUrl by rememberSaveable { mutableStateOf("") }
    var serverUrl by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var httpUserAgent by rememberSaveable { mutableStateOf("") }
    var httpHeaders by rememberSaveable { mutableStateOf("") }
    var stalkerMacAddress by rememberSaveable { mutableStateOf("") }
    var stalkerAuthMode by rememberSaveable { mutableStateOf(StalkerAuthMode.AUTO) }
    var stalkerDeviceProfile by rememberSaveable { mutableStateOf("") }
    var stalkerDeviceTimezone by rememberSaveable { mutableStateOf("") }
    var stalkerDeviceLocale by rememberSaveable { mutableStateOf("") }
    var stalkerSerialNumber by rememberSaveable { mutableStateOf("") }
    var stalkerDeviceId by rememberSaveable { mutableStateOf("") }
    var stalkerDeviceId2 by rememberSaveable { mutableStateOf("") }
    var stalkerSignature by rememberSaveable { mutableStateOf("") }
    var fileImportError by rememberSaveable { mutableStateOf<String?>(null) }
    var handledInitialImportUri by rememberSaveable { mutableStateOf<String?>(null) }
    var showDiscardDraftDialog by rememberSaveable { mutableStateOf(false) }
    var showImportOptionsDialog by rememberSaveable { mutableStateOf(false) }

    // ?? File import helper ????????????????????????????????????????????????????
    fun importM3uUri(uri: android.net.Uri) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    withContext(Dispatchers.Main) {
                        fileImportError = context.getString(R.string.setup_file_import_failed)
                    }
                    return@launch
                }
                inputStream.use {
                    var fileName = "Local_Playlist"
                    val cursor = context.contentResolver.query(uri, null, null, null, null)
                    cursor?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (idx != -1) {
                                val displayName = c.getString(idx)
                                fileName = if (displayName.contains(".")) displayName.substringBeforeLast(".") else displayName
                            }
                        }
                    }
                    val ext = if (uri.toString().substringBefore('?').lowercase().endsWith(".m3u8")) "m3u8" else "m3u"
                    val outFile = java.io.File(context.filesDir, "m3u_${System.currentTimeMillis()}.$ext")
                    outFile.outputStream().use { out -> inputStream.copyTo(out) }
                    cleanupOldImportedM3uFiles(
                        filesDir = context.filesDir,
                        protectedFileUris = knownLocalM3uUrls + "file://${outFile.absolutePath}",
                        keepLatest = 20
                    )
                    withContext(Dispatchers.Main) {
                        viewModel.updateM3uTab(1)
                        m3uUrl = "file://${outFile.absolutePath}"
                        if (name.isEmpty()) name = ProviderInputSanitizer.sanitizeProviderNameForEditing(fileName)
                        fileImportError = null
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { fileImportError = resolveFileImportError(context, e) }
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? -> if (uri != null) importM3uUri(uri) }

    val backupImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? -> uri?.let { viewModel.inspectBackup(it.toString()) } }

    val driveSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result -> viewModel.completeDriveSignIn(result.data) }

    // ?? Effects ???????????????????????????????????????????????????????????????
    LaunchedEffect(knownLocalM3uUrls) {
        cleanupOldImportedM3uFilesAsync(context.filesDir, knownLocalM3uUrls, 20)
    }

    LaunchedEffect(initialImportUri) {
        val importUri = initialImportUri?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (handledInitialImportUri == importUri) return@LaunchedEffect
        handledInitialImportUri = importUri
        selectedTab = 2
        viewModel.updateM3uTab(1)
        runCatching { android.net.Uri.parse(importUri) }.getOrNull()?.let(::importM3uUri)
    }

    LaunchedEffect(uiState.backupImportSuccess) {
        if (uiState.backupImportSuccess) {
            onProviderAdded()
        }
    }
    LaunchedEffect(pairingState.status) {
        if (pairingState.status == ProviderQrPairingStatus.COMPLETE) {
            delay(1200)
            onProviderAdded()
        }
    }
    val showXtream = uiState.portals.isEmpty() || uiState.portals.any { it.type.lowercase() == "xc" }
    val showStalker = uiState.portals.isEmpty() || uiState.portals.any { it.type.lowercase() == "stalker" }

    LaunchedEffect(uiState.portals) {
        if (!uiState.isEditing && uiState.portals.isNotEmpty()) {
            val hasXc = uiState.portals.any { it.type.lowercase() == "xc" }
            val hasStalker = uiState.portals.any { it.type.lowercase() == "stalker" }
            if (!hasXc && hasStalker) {
                selectedTab = 1
            } else if (hasXc) {
                selectedTab = 0
            }
        }
    }

    ProviderSetupCompletionLayer(
        uiState = uiState,
        knownLocalM3uUrls = knownLocalM3uUrls,
        selectedM3uUrl = m3uUrl,
        filesDir = context.filesDir,
        onProviderAdded = onProviderAdded,
        onAttachCreatedProvider = viewModel::attachCreatedProviderToCombined,
        onSkipCreatedProviderCombinedAttach = viewModel::skipCreatedProviderCombinedAttach
    )

    LaunchedEffect(editProviderId) {
        if (editProviderId != null) viewModel.loadProvider(editProviderId)
    }

    LaunchedEffect(uiState.isEditing, uiState.existingProviderId) {
        if (uiState.isEditing) {
            selectedTab = uiState.selectedTab
            name = uiState.name
            serverUrl = uiState.serverUrl
            username = uiState.username
            password = uiState.password
            m3uUrl = uiState.m3uUrl
            httpUserAgent = uiState.httpUserAgent
            httpHeaders = uiState.httpHeaders
            stalkerMacAddress = uiState.stalkerMacAddress
            stalkerAuthMode = uiState.stalkerAuthMode
            stalkerDeviceProfile = uiState.stalkerDeviceProfile
            stalkerDeviceTimezone = uiState.stalkerDeviceTimezone
            stalkerDeviceLocale = uiState.stalkerDeviceLocale
            stalkerSerialNumber = uiState.stalkerSerialNumber
            stalkerDeviceId = uiState.stalkerDeviceId
            stalkerDeviceId2 = uiState.stalkerDeviceId2
            stalkerSignature = uiState.stalkerSignature
        }
    }

    // ?? Derived UI source type ????????????????????????????????????????????????
    val sourceType = when {
        selectedTab == 0 -> SourceType.XTREAM
        selectedTab == 1 -> SourceType.STALKER
        uiState.m3uTab == 1 -> SourceType.M3U_FILE
        else -> SourceType.M3U_URL
    }

    fun onSourceTypeSelected(type: SourceType) {
        if (uiState.isEditing) return
        when (type) {
            SourceType.XTREAM  -> {
                selectedTab = 0
                viewModel.applySourceDefaults(ProviderSetupViewModel.SetupSourceType.XTREAM)
            }
            SourceType.STALKER -> {
                selectedTab = 1
                viewModel.applySourceDefaults(ProviderSetupViewModel.SetupSourceType.STALKER)
            }
            SourceType.M3U_URL -> {
                selectedTab = 2
                viewModel.updateM3uTab(0)
                viewModel.applySourceDefaults(ProviderSetupViewModel.SetupSourceType.M3U)
            }
            SourceType.M3U_FILE-> {
                selectedTab = 2
                viewModel.updateM3uTab(1)
                viewModel.applySourceDefaults(ProviderSetupViewModel.SetupSourceType.M3U)
            }
        }
    }

    val hasUnsavedDraft = !uiState.isEditing && name.isBlank() && (
        serverUrl.isNotBlank() ||
            username.isNotBlank() ||
            password.isNotBlank() ||
            httpUserAgent.isNotBlank() ||
            httpHeaders.isNotBlank() ||
            stalkerMacAddress.isNotBlank() ||
            stalkerAuthMode != StalkerAuthMode.AUTO ||
            stalkerDeviceProfile.isNotBlank() ||
            stalkerDeviceTimezone.isNotBlank() ||
            stalkerDeviceLocale.isNotBlank() ||
            stalkerSerialNumber.isNotBlank() ||
            stalkerDeviceId.isNotBlank() ||
            stalkerDeviceId2.isNotBlank() ||
            stalkerSignature.isNotBlank() ||
            m3uUrl.isNotBlank()
        )

    BackHandler {
        if (hasUnsavedDraft) {
            showDiscardDraftDialog = true
        } else {
            onBack()
        }
    }

    // ?? Layout ????????????????????????????????????????????????????????????????
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = listOf(BackgroundDeep, Background, Surface)))
    ) {
        val isWide = maxWidth >= 700.dp
        val hPad = if (isWide) 24.dp else 16.dp

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = hPad, vertical = 16.dp)
        ) {
            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    SourceTypeSelectorPanel(
                        sourceType = sourceType,
                        isEditing = uiState.isEditing,
                        isEditLabel = if (uiState.isEditing) androidx.compose.ui.res.stringResource(R.string.setup_edit_provider)
                                      else androidx.compose.ui.res.stringResource(R.string.setup_provider_title),
                        showXtream = showXtream,
                        showStalker = showStalker,
                        onSelect = ::onSourceTypeSelected,
                        onImportClick = { showImportOptionsDialog = true },
                        modifier = Modifier.width(200.dp).fillMaxHeight()
                    )
                    ProviderFormContent(
                        sourceType = sourceType,
                        uiState = uiState,
                        pairingState = pairingState,
                        name = name, onNameChange = { name = ProviderInputSanitizer.sanitizeProviderNameForEditing(it) },
                        serverUrl = serverUrl, onServerUrlChange = { serverUrl = ProviderInputSanitizer.sanitizeUrlForEditing(it) },
                        username = username, onUsernameChange = { username = ProviderInputSanitizer.sanitizeUsernameForEditing(it) },
                        password = password, onPasswordChange = { password = ProviderInputSanitizer.sanitizePasswordForEditing(it) },
                        m3uUrl = m3uUrl, onM3uUrlChange = { m3uUrl = ProviderInputSanitizer.sanitizeUrlForEditing(it) },
                        httpUserAgent = httpUserAgent, onHttpUserAgentChange = { httpUserAgent = ProviderInputSanitizer.sanitizeHttpUserAgentForEditing(it) },
                        httpHeaders = httpHeaders, onHttpHeadersChange = { httpHeaders = ProviderInputSanitizer.sanitizeHttpHeadersForEditing(it) },
                        stalkerMacAddress = stalkerMacAddress, onStalkerMacAddressChange = { stalkerMacAddress = ProviderInputSanitizer.sanitizeMacAddressForEditing(it) },
                        stalkerAuthMode = stalkerAuthMode, onStalkerAuthModeChange = { stalkerAuthMode = it },
                        stalkerDeviceProfile = stalkerDeviceProfile, onStalkerDeviceProfileChange = { stalkerDeviceProfile = ProviderInputSanitizer.sanitizeDeviceProfileForEditing(it) },
                        stalkerDeviceTimezone = stalkerDeviceTimezone, onStalkerDeviceTimezoneChange = { stalkerDeviceTimezone = ProviderInputSanitizer.sanitizeTimezoneForEditing(it) },
                        stalkerDeviceLocale = stalkerDeviceLocale, onStalkerDeviceLocaleChange = { stalkerDeviceLocale = ProviderInputSanitizer.sanitizeLocaleForEditing(it) },
                        stalkerSerialNumber = stalkerSerialNumber, onStalkerSerialNumberChange = { stalkerSerialNumber = ProviderInputSanitizer.sanitizeStalkerSerialForEditing(it) },
                        stalkerDeviceId = stalkerDeviceId, onStalkerDeviceIdChange = { stalkerDeviceId = ProviderInputSanitizer.sanitizeStalkerDeviceIdForEditing(it) },
                        stalkerDeviceId2 = stalkerDeviceId2, onStalkerDeviceId2Change = { stalkerDeviceId2 = ProviderInputSanitizer.sanitizeStalkerDeviceIdForEditing(it) },
                        stalkerSignature = stalkerSignature, onStalkerSignatureChange = { stalkerSignature = ProviderInputSanitizer.sanitizeStalkerSignatureForEditing(it) },
                        fileImportError = fileImportError,
                        onFilePick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        onLoginXtream = { viewModel.loginXtream(serverUrl, username, password, name, httpUserAgent, httpHeaders) },
                        onLoginStalker = { viewModel.loginStalker(serverUrl, stalkerMacAddress, stalkerAuthMode, username, password, name, stalkerDeviceProfile, stalkerDeviceTimezone, stalkerDeviceLocale, stalkerSerialNumber, stalkerDeviceId, stalkerDeviceId2, stalkerSignature) },
                        onAddM3u = { viewModel.addM3u(m3uUrl, name, httpUserAgent, httpHeaders) },
                        onStartPhonePairing = viewModel::startPhonePairing,
                        onStopPhonePairing = viewModel::stopPhonePairing,
                        onToggleM3uVodClassification = { viewModel.updateM3uVodClassificationEnabled(!uiState.m3uVodClassificationEnabled) },
                        onSelectEpgSyncMode = viewModel::updateEpgSyncMode,
                        onSelectXtreamLiveSyncMode = viewModel::updateXtreamLiveSyncMode,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SourceTypeTabRow(
                        sourceType = sourceType,
                        isEditing = uiState.isEditing,
                        showXtream = showXtream,
                        showStalker = showStalker,
                        onSelect = ::onSourceTypeSelected,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ProviderFormContent(
                        sourceType = sourceType,
                        uiState = uiState,
                        pairingState = pairingState,
                        name = name, onNameChange = { name = ProviderInputSanitizer.sanitizeProviderNameForEditing(it) },
                        serverUrl = serverUrl, onServerUrlChange = { serverUrl = ProviderInputSanitizer.sanitizeUrlForEditing(it) },
                        username = username, onUsernameChange = { username = ProviderInputSanitizer.sanitizeUsernameForEditing(it) },
                        password = password, onPasswordChange = { password = ProviderInputSanitizer.sanitizePasswordForEditing(it) },
                        m3uUrl = m3uUrl, onM3uUrlChange = { m3uUrl = ProviderInputSanitizer.sanitizeUrlForEditing(it) },
                        httpUserAgent = httpUserAgent, onHttpUserAgentChange = { httpUserAgent = ProviderInputSanitizer.sanitizeHttpUserAgentForEditing(it) },
                        httpHeaders = httpHeaders, onHttpHeadersChange = { httpHeaders = ProviderInputSanitizer.sanitizeHttpHeadersForEditing(it) },
                        stalkerMacAddress = stalkerMacAddress, onStalkerMacAddressChange = { stalkerMacAddress = ProviderInputSanitizer.sanitizeMacAddressForEditing(it) },
                        stalkerAuthMode = stalkerAuthMode, onStalkerAuthModeChange = { stalkerAuthMode = it },
                        stalkerDeviceProfile = stalkerDeviceProfile, onStalkerDeviceProfileChange = { stalkerDeviceProfile = ProviderInputSanitizer.sanitizeDeviceProfileForEditing(it) },
                        stalkerDeviceTimezone = stalkerDeviceTimezone, onStalkerDeviceTimezoneChange = { stalkerDeviceTimezone = ProviderInputSanitizer.sanitizeTimezoneForEditing(it) },
                        stalkerDeviceLocale = stalkerDeviceLocale, onStalkerDeviceLocaleChange = { stalkerDeviceLocale = ProviderInputSanitizer.sanitizeLocaleForEditing(it) },
                        stalkerSerialNumber = stalkerSerialNumber, onStalkerSerialNumberChange = { stalkerSerialNumber = ProviderInputSanitizer.sanitizeStalkerSerialForEditing(it) },
                        stalkerDeviceId = stalkerDeviceId, onStalkerDeviceIdChange = { stalkerDeviceId = ProviderInputSanitizer.sanitizeStalkerDeviceIdForEditing(it) },
                        stalkerDeviceId2 = stalkerDeviceId2, onStalkerDeviceId2Change = { stalkerDeviceId2 = ProviderInputSanitizer.sanitizeStalkerDeviceIdForEditing(it) },
                        stalkerSignature = stalkerSignature, onStalkerSignatureChange = { stalkerSignature = ProviderInputSanitizer.sanitizeStalkerSignatureForEditing(it) },
                        fileImportError = fileImportError,
                        onFilePick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        onLoginXtream = { viewModel.loginXtream(serverUrl, username, password, name, httpUserAgent, httpHeaders) },
                        onLoginStalker = { viewModel.loginStalker(serverUrl, stalkerMacAddress, stalkerAuthMode, username, password, name, stalkerDeviceProfile, stalkerDeviceTimezone, stalkerDeviceLocale, stalkerSerialNumber, stalkerDeviceId, stalkerDeviceId2, stalkerSignature) },
                        onAddM3u = { viewModel.addM3u(m3uUrl, name, httpUserAgent, httpHeaders) },
                        onStartPhonePairing = viewModel::startPhonePairing,
                        onStopPhonePairing = viewModel::stopPhonePairing,
                        onToggleM3uVodClassification = { viewModel.updateM3uVodClassificationEnabled(!uiState.m3uVodClassificationEnabled) },
                        onSelectEpgSyncMode = viewModel::updateEpgSyncMode,
                        onSelectXtreamLiveSyncMode = viewModel::updateXtreamLiveSyncMode,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }
            }

            if (!uiState.isEditing && !isWide) {
                ImportOptionsButton(
                    text = stringResource(R.string.settings_restore_data),
                    onClick = { showImportOptionsDialog = true },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 10.dp, end = 6.dp)
                        .zIndex(1f)
                )
            }
        }
    }

    if (uiState.syncProgress != null) {
        SyncProgressDialog(message = uiState.syncProgress!!)
    }

    val backupPreview = uiState.backupPreview
    if (backupPreview != null && uiState.pendingBackupUri != null) {
        BackupImportPreviewDialog(
            preview = backupPreview,
            plan = uiState.backupImportPlan,
            onDismiss = { viewModel.dismissBackupPreview() },
            onStrategySelected = { viewModel.setBackupConflictStrategy(it) },
            onImportPreferencesChanged = { viewModel.setImportPreferences(it) },
            onImportProvidersChanged = { viewModel.setImportProviders(it) },
            onImportSavedLibraryChanged = { viewModel.setImportSavedLibrary(it) },
            onImportPlaybackHistoryChanged = { viewModel.setImportPlaybackHistory(it) },
            onImportMultiViewChanged = { viewModel.setImportMultiViewPresets(it) },
            onImportRecordingSchedulesChanged = { viewModel.setImportRecordingSchedules(it) },
            isImporting = uiState.isImportingBackup,
            onConfirm = { viewModel.confirmBackupImport() }
        )
    }

    if (showDiscardDraftDialog) {
        PremiumDialog(
            title = stringResource(R.string.setup_discard_draft_title),
            subtitle = stringResource(R.string.setup_discard_draft_body),
            onDismissRequest = { showDiscardDraftDialog = false },
            content = {},
            footer = {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.setup_discard_draft_cancel),
                    onClick = { showDiscardDraftDialog = false }
                )
                PremiumDialogFooterButton(
                    label = stringResource(R.string.setup_discard_draft_confirm),
                    onClick = {
                        showDiscardDraftDialog = false
                        onBack()
                    },
                    emphasized = true
                )
            }
        )
    }

    if (showImportOptionsDialog) {
        ImportOptionsDialog(
            isImportingBackup = uiState.isImportingBackup || uiState.syncProgress != null,
            driveSignedIn = uiState.driveSignedIn,
            onDismiss = { showImportOptionsDialog = false },
            onImportBackup = {
                showImportOptionsDialog = false
                backupImportLauncher.launch(arrayOf("application/json"))
            },
            onImportFromDrive = {
                showImportOptionsDialog = false
                viewModel.importBackupFromDrive()
            },
            onDriveSignIn = {
                showImportOptionsDialog = false
                viewModel.beginDriveSignIn(driveSignInLauncher)
            }
        )
    }

}

    @Composable
    internal fun ProviderSetupCompletionLayer(
        uiState: ProviderSetupState,
        knownLocalM3uUrls: Set<String>,
        selectedM3uUrl: String,
        filesDir: java.io.File,
        onProviderAdded: () -> Unit,
        onAttachCreatedProvider: () -> Unit,
        onSkipCreatedProviderCombinedAttach: () -> Unit,
        cleanupImportedFiles: suspend (java.io.File, Set<String>, Int) -> Unit = ::cleanupOldImportedM3uFilesAsync
    ) {
        val context = LocalContext.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(uiState.onboardingCompletion, uiState.completionWarning, uiState.pendingCombinedAttachProfileId) {
            if (
                uiState.onboardingCompletion != ProviderSetupViewModel.OnboardingCompletion.NONE &&
                uiState.pendingCombinedAttachProfileId == null
            ) {
                if (uiState.completionWarning != null) {
                    Toast.makeText(
                        context,
                        "Provider saved. Sync will resume in background.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                val previousLocal = uiState.m3uUrl.takeIf { it.startsWith("file://") }
                val selectedLocal = selectedM3uUrl.takeIf { it.startsWith("file://") }
                val protectedUris = buildSet {
                    knownLocalM3uUrls.forEach { knownUri ->
                        if (knownUri != previousLocal || previousLocal == selectedLocal) add(knownUri)
                    }
                    selectedLocal?.let(::add)
                }
                cleanupImportedFiles(filesDir, protectedUris, 20)
                lifecycle.awaitResumed()
                onProviderAdded()
            }
        }

        if (uiState.pendingCombinedAttachProfileId != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = onSkipCreatedProviderCombinedAttach,
                title = { Text("Add Playlist To Combined M3U?") },
                text = {
                    Text(
                        buildString {
                            append("Add ")
                            append(uiState.createdProviderName ?: "this playlist")
                            append(" to ")
                            append(uiState.pendingCombinedAttachProfileName ?: "the active combined source")
                            append(" and keep that combined source active for Live TV?")
                        },
                        color = OnSurface
                    )
                },
                confirmButton = {
                    TextButton(onClick = onAttachCreatedProvider) {
                        Text("Add To Combined", color = Primary)
                    }
                },
                dismissButton = {
                    TextButton(onClick = onSkipCreatedProviderCombinedAttach) {
                        Text("Not Now", color = OnSurface)
                    }
                }
            )
        }
    }

// ??? Form content ?????????????????????????????????????????????????????????????

private suspend fun Lifecycle.awaitResumed() {
    if (currentState.isAtLeast(Lifecycle.State.RESUMED)) return

    suspendCancellableCoroutine { continuation ->
        lateinit var observer: LifecycleEventObserver
        observer = LifecycleEventObserver { source, _ ->
            if (source.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                source.lifecycle.removeObserver(observer)
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
        addObserver(observer)
        continuation.invokeOnCancellation { removeObserver(observer) }
    }
}

@Composable
private fun PhonePairingCard(
    pairingState: ProviderQrPairingState,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val isActive = pairingState.status == ProviderQrPairingStatus.READY ||
        pairingState.status == ProviderQrPairingStatus.RECEIVING
    val message = pairingState.message ?: stringResource(R.string.setup_phone_pairing_body)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = SurfaceDefaults.colors(containerColor = Surface.copy(alpha = 0.72f)),
        border = Border(
            border = BorderStroke(
                1.dp,
                if (isActive) Primary.copy(alpha = 0.55f) else SurfaceHighlight
            ),
            shape = RoundedCornerShape(16.dp)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.setup_phone_pairing_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = OnBackground
                    )
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )
                }
                StatusPill(
                    label = when (pairingState.status) {
                        ProviderQrPairingStatus.IDLE -> stringResource(R.string.setup_phone_pairing_idle)
                        ProviderQrPairingStatus.READY -> stringResource(R.string.setup_phone_pairing_ready)
                        ProviderQrPairingStatus.RECEIVING -> stringResource(R.string.setup_phone_pairing_receiving)
                        ProviderQrPairingStatus.COMPLETE -> stringResource(R.string.setup_phone_pairing_complete)
                        ProviderQrPairingStatus.ERROR -> stringResource(R.string.setup_phone_pairing_error)
                    },
                    containerColor = if (pairingState.status == ProviderQrPairingStatus.ERROR) {
                        ErrorColor.copy(alpha = 0.35f)
                    } else {
                        PrimaryGlow
                    }
                )
            }

            pairingState.qrBitmap?.let { bitmap ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.setup_phone_pairing_qr_description),
                        modifier = Modifier
                            .size(156.dp)
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .padding(8.dp)
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.setup_phone_pairing_same_wifi),
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnBackground
                        )
                        pairingState.url?.let { url ->
                            Text(
                                text = url,
                                style = MaterialTheme.typography.bodySmall,
                                color = Primary,
                                maxLines = 3
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    SmallActionButton(
                        text = if (isActive) {
                            stringResource(R.string.setup_phone_pairing_restart)
                        } else {
                            stringResource(R.string.setup_phone_pairing_start)
                        },
                        onClick = onStart
                    )
                }
                if (isActive) {
                    Box(modifier = Modifier.weight(1f)) {
                        SmallActionButton(
                            text = stringResource(R.string.setup_phone_pairing_stop),
                            onClick = onStop
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderFormContent(
    sourceType: SourceType,
    uiState: ProviderSetupState,
    pairingState: ProviderQrPairingState,
    name: String, onNameChange: (String) -> Unit,
    serverUrl: String, onServerUrlChange: (String) -> Unit,
    username: String, onUsernameChange: (String) -> Unit,
    password: String, onPasswordChange: (String) -> Unit,
    m3uUrl: String, onM3uUrlChange: (String) -> Unit,
    httpUserAgent: String, onHttpUserAgentChange: (String) -> Unit,
    httpHeaders: String, onHttpHeadersChange: (String) -> Unit,
    stalkerMacAddress: String, onStalkerMacAddressChange: (String) -> Unit,
    stalkerAuthMode: StalkerAuthMode, onStalkerAuthModeChange: (StalkerAuthMode) -> Unit,
    stalkerDeviceProfile: String, onStalkerDeviceProfileChange: (String) -> Unit,
    stalkerDeviceTimezone: String, onStalkerDeviceTimezoneChange: (String) -> Unit,
    stalkerDeviceLocale: String, onStalkerDeviceLocaleChange: (String) -> Unit,
    stalkerSerialNumber: String, onStalkerSerialNumberChange: (String) -> Unit,
    stalkerDeviceId: String, onStalkerDeviceIdChange: (String) -> Unit,
    stalkerDeviceId2: String, onStalkerDeviceId2Change: (String) -> Unit,
    stalkerSignature: String, onStalkerSignatureChange: (String) -> Unit,
    fileImportError: String?,
    onFilePick: () -> Unit,
    onLoginXtream: () -> Unit,
    onLoginStalker: () -> Unit,
    onAddM3u: () -> Unit,
    onStartPhonePairing: () -> Unit,
    onStopPhonePairing: () -> Unit,
    onToggleM3uVodClassification: () -> Unit,
    onSelectEpgSyncMode: (ProviderEpgSyncMode) -> Unit,
    onSelectXtreamLiveSyncMode: (ProviderXtreamLiveSyncMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val isTelevisionDevice = rememberIsTelevisionDevice()

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        border = Border(border = BorderStroke(1.dp, SurfaceHighlight), shape = RoundedCornerShape(20.dp)),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated.copy(alpha = 0.95f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .imePadding()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Playlist name ן¿½ always shown
            ProviderTextField(
                value = name,
                onValueChange = onNameChange,
                placeholder = androidx.compose.ui.res.stringResource(R.string.setup_name_hint)
            )

            if (!uiState.isEditing) {
                PhonePairingCard(
                    pairingState = pairingState,
                    onStart = onStartPhonePairing,
                    onStop = onStopPhonePairing
                )
            }

            HorizontalDivider(color = SurfaceHighlight.copy(alpha = 0.6f))

            when (sourceType) {
                SourceType.XTREAM -> {
                    PortalSelector(
                        selectedUrl = serverUrl,
                        portals = uiState.portals,
                        portalType = "xc",
                        onPortalSelected = { portal ->
                            onServerUrlChange(portal.url)
                            val prefix = username.trim()
                            val newDefaultName = if (prefix.isNotEmpty()) "$prefix@${portal.name}" else portal.name
                            if (name.isEmpty() || name == "Phone Xtream Provider" || uiState.portals.any { it.name == name } || (name.contains("@") && uiState.portals.any { it.name == name.substringAfter("@") })) {
                                onNameChange(newDefaultName)
                            }
                        }
                    )
                    ProviderTextField(
                        value = username, onValueChange = { newVal ->
                            onUsernameChange(newVal)
                            val selectedPortal = uiState.portals.find { it.url == serverUrl }
                            if (selectedPortal != null) {
                                val currentServerName = selectedPortal.name
                                val prefix = newVal.trim()
                                val newDefaultName = if (prefix.isNotEmpty()) "$prefix@$currentServerName" else currentServerName
                                if (name.isEmpty() || name == currentServerName || (name.contains("@") && name.substringAfter("@") == currentServerName)) {
                                    onNameChange(newDefaultName)
                                }
                            }
                        },
                        placeholder = androidx.compose.ui.res.stringResource(R.string.setup_user_hint),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Next
                        )
                    )
                    ProviderTextField(
                        value = password, onValueChange = onPasswordChange,
                        placeholder = androidx.compose.ui.res.stringResource(R.string.setup_pass_hint),
                        isPassword = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = if (isTelevisionDevice) KeyboardType.Ascii else KeyboardType.Password,
                            imeAction = ImeAction.Done
                        )
                    )
                    AdvancedProviderOptionsSection(
                        sourceType = sourceType,
                        uiState = uiState,
                        httpUserAgent = httpUserAgent,
                        onHttpUserAgentChange = onHttpUserAgentChange,
                        httpHeaders = httpHeaders,
                        onHttpHeadersChange = onHttpHeadersChange,
                        onToggleM3uVodClassification = onToggleM3uVodClassification,
                        onSelectEpgSyncMode = onSelectEpgSyncMode,
                        onSelectXtreamLiveSyncMode = onSelectXtreamLiveSyncMode,
                        username = username,
                        onUsernameChange = onUsernameChange,
                        password = password,
                        onPasswordChange = onPasswordChange,
                        stalkerAuthMode = stalkerAuthMode,
                        onStalkerAuthModeChange = onStalkerAuthModeChange,
                        stalkerMacAddress = stalkerMacAddress,
                        stalkerDeviceProfile = stalkerDeviceProfile,
                        onStalkerDeviceProfileChange = onStalkerDeviceProfileChange,
                        stalkerDeviceTimezone = stalkerDeviceTimezone,
                        onStalkerDeviceTimezoneChange = onStalkerDeviceTimezoneChange,
                        stalkerDeviceLocale = stalkerDeviceLocale,
                        onStalkerDeviceLocaleChange = onStalkerDeviceLocaleChange,
                        stalkerSerialNumber = stalkerSerialNumber,
                        onStalkerSerialNumberChange = onStalkerSerialNumberChange,
                        stalkerDeviceId = stalkerDeviceId,
                        onStalkerDeviceIdChange = onStalkerDeviceIdChange,
                        stalkerDeviceId2 = stalkerDeviceId2,
                        onStalkerDeviceId2Change = onStalkerDeviceId2Change,
                        stalkerSignature = stalkerSignature,
                        onStalkerSignatureChange = onStalkerSignatureChange
                    )
                    FormErrors(uiState.validationError, uiState.error)
                    ActionButton(
                        text = when {
                            uiState.isLoading -> androidx.compose.ui.res.stringResource(R.string.setup_connecting)
                            uiState.isEditing -> androidx.compose.ui.res.stringResource(R.string.setup_save)
                            else              -> androidx.compose.ui.res.stringResource(R.string.setup_login)
                        },
                        isLoading = uiState.isLoading,
                        onClick = onLoginXtream
                    )
                }

                SourceType.STALKER -> {
                    PortalSelector(
                        selectedUrl = serverUrl,
                        portals = uiState.portals,
                        portalType = "stalker",
                        onPortalSelected = { portal ->
                            onServerUrlChange(portal.url)
                            val macPart = stalkerMacAddress.trim().takeLast(8)
                            val newDefaultName = if (macPart.isNotEmpty()) "$macPart@${portal.name}" else portal.name
                            if (name.isEmpty() || name == "Phone Stalker Portal" || uiState.portals.any { it.name == name } || (name.contains("@") && uiState.portals.any { it.name == name.substringAfter("@") })) {
                                onNameChange(newDefaultName)
                            }
                        }
                    )
                    ProviderTextField(
                        value = stalkerMacAddress, onValueChange = { newVal ->
                            onStalkerMacAddressChange(newVal)
                            val selectedPortal = uiState.portals.find { it.url == serverUrl }
                            if (selectedPortal != null) {
                                val currentServerName = selectedPortal.name
                                val macPart = newVal.trim().takeLast(8)
                                val newDefaultName = if (macPart.isNotEmpty()) "$macPart@$currentServerName" else currentServerName
                                if (name.isEmpty() || name == currentServerName || (name.contains("@") && name.substringAfter("@") == currentServerName)) {
                                    onNameChange(newDefaultName)
                                }
                            }
                        },
                        placeholder = if (stalkerAuthMode == StalkerAuthMode.CREDENTIALS_ONLY) {
                            "MAC address (optional)"
                        } else {
                            "MAC address"
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Next
                        )
                    )
                    AdvancedProviderOptionsSection(
                        sourceType = sourceType,
                        uiState = uiState,
                        httpUserAgent = httpUserAgent,
                        onHttpUserAgentChange = onHttpUserAgentChange,
                        httpHeaders = httpHeaders,
                        onHttpHeadersChange = onHttpHeadersChange,
                        onToggleM3uVodClassification = onToggleM3uVodClassification,
                        onSelectEpgSyncMode = onSelectEpgSyncMode,
                        onSelectXtreamLiveSyncMode = onSelectXtreamLiveSyncMode,
                        username = username,
                        onUsernameChange = onUsernameChange,
                        password = password,
                        onPasswordChange = onPasswordChange,
                        stalkerAuthMode = stalkerAuthMode,
                        onStalkerAuthModeChange = onStalkerAuthModeChange,
                        stalkerMacAddress = stalkerMacAddress,
                        stalkerDeviceProfile = stalkerDeviceProfile,
                        onStalkerDeviceProfileChange = onStalkerDeviceProfileChange,
                        stalkerDeviceTimezone = stalkerDeviceTimezone,
                        onStalkerDeviceTimezoneChange = onStalkerDeviceTimezoneChange,
                        stalkerDeviceLocale = stalkerDeviceLocale,
                        onStalkerDeviceLocaleChange = onStalkerDeviceLocaleChange,
                        stalkerSerialNumber = stalkerSerialNumber,
                        onStalkerSerialNumberChange = onStalkerSerialNumberChange,
                        stalkerDeviceId = stalkerDeviceId,
                        onStalkerDeviceIdChange = onStalkerDeviceIdChange,
                        stalkerDeviceId2 = stalkerDeviceId2,
                        onStalkerDeviceId2Change = onStalkerDeviceId2Change,
                        stalkerSignature = stalkerSignature,
                        onStalkerSignatureChange = onStalkerSignatureChange
                    )
                    FormErrors(uiState.validationError, uiState.error)
                    ActionButton(
                        text = when {
                            uiState.isLoading -> androidx.compose.ui.res.stringResource(R.string.setup_connecting)
                            uiState.isEditing -> androidx.compose.ui.res.stringResource(R.string.setup_save)
                            else              -> androidx.compose.ui.res.stringResource(R.string.setup_login)
                        },
                        isLoading = uiState.isLoading,
                        onClick = onLoginStalker
                    )
                }

                SourceType.M3U_URL -> {
                    ProviderTextField(
                        value = m3uUrl, onValueChange = onM3uUrlChange,
                        placeholder = androidx.compose.ui.res.stringResource(R.string.setup_m3u_hint),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done)
                    )
                    AdvancedProviderOptionsSection(
                        sourceType = sourceType,
                        uiState = uiState,
                        httpUserAgent = httpUserAgent,
                        onHttpUserAgentChange = onHttpUserAgentChange,
                        httpHeaders = httpHeaders,
                        onHttpHeadersChange = onHttpHeadersChange,
                        onToggleM3uVodClassification = onToggleM3uVodClassification,
                        onSelectEpgSyncMode = onSelectEpgSyncMode,
                        onSelectXtreamLiveSyncMode = onSelectXtreamLiveSyncMode,
                        username = username,
                        onUsernameChange = onUsernameChange,
                        password = password,
                        onPasswordChange = onPasswordChange,
                        stalkerAuthMode = stalkerAuthMode,
                        onStalkerAuthModeChange = onStalkerAuthModeChange,
                        stalkerMacAddress = stalkerMacAddress,
                        stalkerDeviceProfile = stalkerDeviceProfile,
                        onStalkerDeviceProfileChange = onStalkerDeviceProfileChange,
                        stalkerDeviceTimezone = stalkerDeviceTimezone,
                        onStalkerDeviceTimezoneChange = onStalkerDeviceTimezoneChange,
                        stalkerDeviceLocale = stalkerDeviceLocale,
                        onStalkerDeviceLocaleChange = onStalkerDeviceLocaleChange,
                        stalkerSerialNumber = stalkerSerialNumber,
                        onStalkerSerialNumberChange = onStalkerSerialNumberChange,
                        stalkerDeviceId = stalkerDeviceId,
                        onStalkerDeviceIdChange = onStalkerDeviceIdChange,
                        stalkerDeviceId2 = stalkerDeviceId2,
                        onStalkerDeviceId2Change = onStalkerDeviceId2Change,
                        stalkerSignature = stalkerSignature,
                        onStalkerSignatureChange = onStalkerSignatureChange
                    )
                    FormErrors(uiState.validationError, uiState.error)
                    ActionButton(
                        text = when {
                            uiState.isLoading -> androidx.compose.ui.res.stringResource(R.string.setup_validating)
                            uiState.isEditing -> androidx.compose.ui.res.stringResource(R.string.setup_save)
                            else              -> androidx.compose.ui.res.stringResource(R.string.setup_add)
                        },
                        isLoading = uiState.isLoading,
                        onClick = onAddM3u
                    )
                }

                SourceType.M3U_FILE -> {
                    FileSelectorCard(
                        fileName = if (m3uUrl.startsWith("file://")) m3uUrl.substringAfterLast("/") else null,
                        fileSelectedHint = androidx.compose.ui.res.stringResource(R.string.setup_file_replace_hint),
                        emptySelectionTitle = androidx.compose.ui.res.stringResource(R.string.setup_file_select_title),
                        emptySelectionHint = androidx.compose.ui.res.stringResource(R.string.setup_file_browse_hint),
                        onClick = onFilePick
                    )
                    fileImportError?.let {
                        Text(text = it, style = MaterialTheme.typography.bodyMedium, color = ErrorColor)
                    }
                    AdvancedProviderOptionsSection(
                        sourceType = sourceType,
                        uiState = uiState,
                        httpUserAgent = httpUserAgent,
                        onHttpUserAgentChange = onHttpUserAgentChange,
                        httpHeaders = httpHeaders,
                        onHttpHeadersChange = onHttpHeadersChange,
                        onToggleM3uVodClassification = onToggleM3uVodClassification,
                        onSelectEpgSyncMode = onSelectEpgSyncMode,
                        onSelectXtreamLiveSyncMode = onSelectXtreamLiveSyncMode,
                        username = username,
                        onUsernameChange = onUsernameChange,
                        password = password,
                        onPasswordChange = onPasswordChange,
                        stalkerAuthMode = stalkerAuthMode,
                        onStalkerAuthModeChange = onStalkerAuthModeChange,
                        stalkerMacAddress = stalkerMacAddress,
                        stalkerDeviceProfile = stalkerDeviceProfile,
                        onStalkerDeviceProfileChange = onStalkerDeviceProfileChange,
                        stalkerDeviceTimezone = stalkerDeviceTimezone,
                        onStalkerDeviceTimezoneChange = onStalkerDeviceTimezoneChange,
                        stalkerDeviceLocale = stalkerDeviceLocale,
                        onStalkerDeviceLocaleChange = onStalkerDeviceLocaleChange,
                        stalkerSerialNumber = stalkerSerialNumber,
                        onStalkerSerialNumberChange = onStalkerSerialNumberChange,
                        stalkerDeviceId = stalkerDeviceId,
                        onStalkerDeviceIdChange = onStalkerDeviceIdChange,
                        stalkerDeviceId2 = stalkerDeviceId2,
                        onStalkerDeviceId2Change = onStalkerDeviceId2Change,
                        stalkerSignature = stalkerSignature,
                        onStalkerSignatureChange = onStalkerSignatureChange
                    )
                    FormErrors(uiState.validationError, uiState.error)
                    ActionButton(
                        text = when {
                            uiState.isLoading -> androidx.compose.ui.res.stringResource(R.string.setup_validating)
                            uiState.isEditing -> androidx.compose.ui.res.stringResource(R.string.setup_save)
                            else              -> androidx.compose.ui.res.stringResource(R.string.setup_add)
                        },
                        isLoading = uiState.isLoading,
                        onClick = onAddM3u
                    )
                }
            }
        }
    }
}


@Composable
private fun AdvancedProviderOptionsSection(
    sourceType: SourceType,
    uiState: ProviderSetupState,
    httpUserAgent: String,
    onHttpUserAgentChange: (String) -> Unit,
    httpHeaders: String,
    onHttpHeadersChange: (String) -> Unit,
    onToggleM3uVodClassification: () -> Unit,
    onSelectEpgSyncMode: (ProviderEpgSyncMode) -> Unit,
    onSelectXtreamLiveSyncMode: (ProviderXtreamLiveSyncMode) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    stalkerAuthMode: StalkerAuthMode,
    onStalkerAuthModeChange: (StalkerAuthMode) -> Unit,
    stalkerMacAddress: String,
    stalkerDeviceProfile: String,
    onStalkerDeviceProfileChange: (String) -> Unit,
    stalkerDeviceTimezone: String,
    onStalkerDeviceTimezoneChange: (String) -> Unit,
    stalkerDeviceLocale: String,
    onStalkerDeviceLocaleChange: (String) -> Unit,
    stalkerSerialNumber: String,
    onStalkerSerialNumberChange: (String) -> Unit,
    stalkerDeviceId: String,
    onStalkerDeviceIdChange: (String) -> Unit,
    stalkerDeviceId2: String,
    onStalkerDeviceId2Change: (String) -> Unit,
    stalkerSignature: String,
    onStalkerSignatureChange: (String) -> Unit
) {
    var showAdvancedOptions by rememberSaveable(sourceType) { mutableStateOf(false) }
    val defaultEpgSyncMode = when (sourceType) {
        SourceType.STALKER -> ProviderEpgSyncMode.BACKGROUND
        SourceType.XTREAM,
        SourceType.M3U_URL,
        SourceType.M3U_FILE -> ProviderEpgSyncMode.UPFRONT
    }

    LaunchedEffect(uiState.isEditing, uiState.epgSyncMode, uiState.xtreamLiveSyncMode, sourceType) {
        val hasNonDefaultSelection = ((sourceType == SourceType.XTREAM || sourceType == SourceType.STALKER) && uiState.epgSyncMode != defaultEpgSyncMode) ||
            (sourceType == SourceType.XTREAM && uiState.xtreamLiveSyncMode != ProviderXtreamLiveSyncMode.AUTO) ||
            ((sourceType == SourceType.M3U_URL || sourceType == SourceType.M3U_FILE) && !uiState.m3uVodClassificationEnabled) ||
            ((sourceType == SourceType.XTREAM || sourceType == SourceType.M3U_URL || sourceType == SourceType.M3U_FILE) &&
                (httpUserAgent.isNotBlank() || httpHeaders.isNotBlank())) ||
            (sourceType == SourceType.STALKER && (
                stalkerAuthMode != StalkerAuthMode.AUTO ||
                    username.isNotBlank() ||
                    password.isNotBlank() ||
                    stalkerMacAddress.isBlank() ||
                stalkerDeviceProfile.isNotBlank() ||
                    stalkerDeviceTimezone.isNotBlank() ||
                    stalkerDeviceLocale.isNotBlank() ||
                    stalkerSerialNumber.isNotBlank() ||
                    stalkerDeviceId.isNotBlank() ||
                    stalkerDeviceId2.isNotBlank() ||
                    stalkerSignature.isNotBlank()
                ))
        if (uiState.isEditing && hasNonDefaultSelection) {
            showAdvancedOptions = true
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            onClick = { showAdvancedOptions = !showAdvancedOptions },
            modifier = Modifier
                .fillMaxWidth()
                .mouseClickable(onClick = { showAdvancedOptions = !showAdvancedOptions }),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (showAdvancedOptions) Primary.copy(alpha = 0.12f) else Surface,
                focusedContainerColor = Primary.copy(alpha = 0.24f)
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(
                    BorderStroke(
                        1.dp,
                        if (showAdvancedOptions) Primary.copy(alpha = 0.45f) else SurfaceHighlight
                    )
                ),
                focusedBorder = Border(BorderStroke(3.dp, PrimaryLight))
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.setup_advanced_options_label),
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.setup_advanced_options_helper),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )
                }
                Text(
                    text = if (showAdvancedOptions) {
                        androidx.compose.ui.res.stringResource(R.string.setup_advanced_options_hide)
                    } else {
                        androidx.compose.ui.res.stringResource(R.string.setup_advanced_options_show)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = PrimaryLight
                )
            }
        }

        AnimatedVisibility(visible = showAdvancedOptions) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (sourceType == SourceType.M3U_URL || sourceType == SourceType.M3U_FILE) {
                    Surface(
                        onClick = onToggleM3uVodClassification,
                        modifier = Modifier
                            .fillMaxWidth()
                            .mouseClickable(onClick = onToggleM3uVodClassification),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (uiState.m3uVodClassificationEnabled) Primary.copy(alpha = 0.1f) else Surface,
                            focusedContainerColor = Primary.copy(alpha = 0.22f)
                        ),
                        border = ClickableSurfaceDefaults.border(
                            border = Border(
                                BorderStroke(
                                    1.dp,
                                    if (uiState.m3uVodClassificationEnabled) Primary.copy(alpha = 0.4f) else SurfaceHighlight
                                )
                            ),
                            focusedBorder = Border(BorderStroke(3.dp, PrimaryLight))
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = androidx.compose.ui.res.stringResource(R.string.setup_m3u_vod_classification_label),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextPrimary
                                )
                                Text(
                                    text = androidx.compose.ui.res.stringResource(R.string.setup_m3u_vod_classification_helper),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurfaceDim
                                )
                            }
                            Switch(
                                checked = uiState.m3uVodClassificationEnabled,
                                onCheckedChange = { onToggleM3uVodClassification() }
                            )
                        }
                    }
                }

                if (sourceType == SourceType.XTREAM || sourceType == SourceType.M3U_URL || sourceType == SourceType.M3U_FILE) {
                    ProviderTextField(
                        value = httpUserAgent,
                        onValueChange = onHttpUserAgentChange,
                        placeholder = "User-Agent override (optional)",
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Next
                        )
                    )
                    ProviderTextField(
                        value = httpHeaders,
                        onValueChange = onHttpHeadersChange,
                        placeholder = "Custom headers (optional, Header: Value | Header2: Value)",
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Next
                        )
                    )
                }

                if (sourceType == SourceType.XTREAM || sourceType == SourceType.STALKER) {
                    if (sourceType == SourceType.XTREAM) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Surface, RoundedCornerShape(12.dp))
                                .border(1.dp, SurfaceHighlight, RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = androidx.compose.ui.res.stringResource(R.string.setup_xtream_live_sync_mode_label),
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary
                            )
                            Text(
                                text = androidx.compose.ui.res.stringResource(R.string.setup_xtream_live_sync_mode_helper),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                            ProviderXtreamLiveSyncMode.entries.forEach { mode ->
                                XtreamLiveSyncModeOptionRow(
                                    mode = mode,
                                    selected = uiState.xtreamLiveSyncMode == mode,
                                    onSelect = { onSelectXtreamLiveSyncMode(mode) }
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Surface, RoundedCornerShape(12.dp))
                            .border(1.dp, SurfaceHighlight, RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.setup_epg_sync_mode_label),
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = androidx.compose.ui.res.stringResource(
                                if (sourceType == SourceType.STALKER) {
                                    R.string.setup_stalker_epg_sync_mode_helper
                                } else {
                                    R.string.setup_epg_sync_mode_helper
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                        ProviderEpgSyncMode.entries.forEach { mode ->
                            EpgSyncModeOptionRow(
                                mode = mode,
                                sourceType = sourceType,
                                selected = uiState.epgSyncMode == mode,
                                onSelect = { onSelectEpgSyncMode(mode) }
                            )
                        }
                    }
                }

                if (sourceType == SourceType.STALKER) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Surface, RoundedCornerShape(12.dp))
                            .border(1.dp, SurfaceHighlight, RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Stalker auth mode",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = "Auto-detect is the default. Override it only when the portal needs credentials or a mixed MAG + account login flow.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                        StalkerAuthMode.entries.forEach { mode ->
                            StalkerAuthModeOptionRow(
                                mode = mode,
                                selected = stalkerAuthMode == mode,
                                onSelect = { onStalkerAuthModeChange(mode) }
                            )
                        }
                    }
                    if (stalkerAuthMode != StalkerAuthMode.MAC_ONLY) {
                        ProviderTextField(
                            value = username,
                            onValueChange = onUsernameChange,
                            placeholder = if (stalkerAuthMode == StalkerAuthMode.AUTO) {
                                "Portal username (optional)"
                            } else {
                                "Portal username"
                            },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Next
                            )
                        )
                        ProviderTextField(
                            value = password,
                            onValueChange = onPasswordChange,
                            placeholder = if (stalkerAuthMode == StalkerAuthMode.AUTO) {
                                "Portal password (optional)"
                            } else {
                                "Portal password"
                            },
                            isPassword = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Next
                            )
                        )
                    }
                    ProviderTextField(
                        value = stalkerDeviceProfile,
                        onValueChange = onStalkerDeviceProfileChange,
                        placeholder = "Device profile (optional)"
                    )
                    ProviderTextField(
                        value = stalkerDeviceTimezone,
                        onValueChange = onStalkerDeviceTimezoneChange,
                        placeholder = "Timezone (optional)"
                    )
                    ProviderTextField(
                        value = stalkerDeviceLocale,
                        onValueChange = onStalkerDeviceLocaleChange,
                        placeholder = "Locale (optional)"
                    )
                    ProviderTextField(
                        value = stalkerSerialNumber,
                        onValueChange = onStalkerSerialNumberChange,
                        placeholder = "Serial number (optional)"
                    )
                    ProviderTextField(
                        value = stalkerDeviceId,
                        onValueChange = onStalkerDeviceIdChange,
                        placeholder = "Device ID (optional)"
                    )
                    ProviderTextField(
                        value = stalkerDeviceId2,
                        onValueChange = onStalkerDeviceId2Change,
                        placeholder = "Device ID2 (optional)"
                    )
                    ProviderTextField(
                        value = stalkerSignature,
                        onValueChange = onStalkerSignatureChange,
                        placeholder = "Signature (optional)"
                    )
                }
            }
        }
    }
}

@Composable
private fun XtreamLiveSyncModeOptionRow(
    mode: ProviderXtreamLiveSyncMode,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val titleRes = when (mode) {
        ProviderXtreamLiveSyncMode.AUTO -> R.string.setup_xtream_live_sync_mode_auto_title
        ProviderXtreamLiveSyncMode.CATEGORY_BY_CATEGORY -> R.string.setup_xtream_live_sync_mode_category_title
        ProviderXtreamLiveSyncMode.STREAM_ALL -> R.string.setup_xtream_live_sync_mode_stream_all_title
    }
    val descriptionRes = when (mode) {
        ProviderXtreamLiveSyncMode.AUTO -> R.string.setup_xtream_live_sync_mode_auto_description
        ProviderXtreamLiveSyncMode.CATEGORY_BY_CATEGORY -> R.string.setup_xtream_live_sync_mode_category_description
        ProviderXtreamLiveSyncMode.STREAM_ALL -> R.string.setup_xtream_live_sync_mode_stream_all_description
    }
    Surface(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .mouseClickable(onClick = onSelect),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Primary.copy(alpha = 0.12f) else Color.Transparent,
            focusedContainerColor = if (selected) Primary.copy(alpha = 0.26f) else SurfaceHighlight.copy(alpha = 0.9f)
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                BorderStroke(
                    1.dp,
                    if (selected) Primary.copy(alpha = 0.45f) else Color.Transparent
                )
            ),
            focusedBorder = Border(BorderStroke(3.dp, PrimaryLight))
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelect
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = androidx.compose.ui.res.stringResource(titleRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }
        }
    }
}

@Composable
private fun StalkerAuthModeOptionRow(
    mode: StalkerAuthMode,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val title = when (mode) {
        StalkerAuthMode.AUTO -> "Auto-detect"
        StalkerAuthMode.MAC_ONLY -> "MAC only"
        StalkerAuthMode.MAC_PLUS_CREDENTIALS -> "MAC + credentials"
        StalkerAuthMode.CREDENTIALS_ONLY -> "Credentials only"
    }
    val description = when (mode) {
        StalkerAuthMode.AUTO -> "Try the portal's likely auth flow first and retry once if a different Stalker mode fits better."
        StalkerAuthMode.MAC_ONLY -> "Use MAG-style MAC authentication without a portal account login."
        StalkerAuthMode.MAC_PLUS_CREDENTIALS -> "Keep the MAG identity and add portal account credentials for stricter portals."
        StalkerAuthMode.CREDENTIALS_ONLY -> "Use portal account credentials even if the MAC address is optional or ignored."
    }
    Surface(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .mouseClickable(onClick = onSelect),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Primary.copy(alpha = 0.12f) else Color.Transparent,
            focusedContainerColor = if (selected) Primary.copy(alpha = 0.26f) else SurfaceHighlight.copy(alpha = 0.9f)
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                BorderStroke(
                    1.dp,
                    if (selected) Primary.copy(alpha = 0.45f) else Color.Transparent
                )
            ),
            focusedBorder = Border(BorderStroke(3.dp, PrimaryLight))
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelect
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }
        }
    }
}

@Composable
private fun EpgSyncModeOptionRow(
    mode: ProviderEpgSyncMode,
    sourceType: SourceType,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val titleRes = when (mode) {
        ProviderEpgSyncMode.UPFRONT -> R.string.setup_epg_sync_mode_upfront_title
        ProviderEpgSyncMode.BACKGROUND -> R.string.setup_epg_sync_mode_background_title
        ProviderEpgSyncMode.SKIP -> R.string.setup_epg_sync_mode_skip_title
    }
    val descriptionRes = when (mode) {
        ProviderEpgSyncMode.UPFRONT -> if (sourceType == SourceType.STALKER) {
            R.string.setup_stalker_epg_sync_mode_upfront_description
        } else {
            R.string.setup_epg_sync_mode_upfront_description
        }
        ProviderEpgSyncMode.BACKGROUND -> if (sourceType == SourceType.STALKER) {
            R.string.setup_stalker_epg_sync_mode_background_description
        } else {
            R.string.setup_epg_sync_mode_background_description
        }
        ProviderEpgSyncMode.SKIP -> if (sourceType == SourceType.STALKER) {
            R.string.setup_stalker_epg_sync_mode_skip_description
        } else {
            R.string.setup_epg_sync_mode_skip_description
        }
    }
    Surface(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .mouseClickable(onClick = onSelect),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Primary.copy(alpha = 0.12f) else Color.Transparent,
            focusedContainerColor = if (selected) Primary.copy(alpha = 0.26f) else SurfaceHighlight.copy(alpha = 0.9f)
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                BorderStroke(
                    1.dp,
                    if (selected) Primary.copy(alpha = 0.45f) else Color.Transparent
                )
            ),
            focusedBorder = Border(BorderStroke(3.dp, PrimaryLight))
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelect
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = androidx.compose.ui.res.stringResource(titleRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }
        }
    }
}

@Composable
private fun FormErrors(validationError: String?, error: String?) {
    validationError?.let {
        Text(text = it, style = MaterialTheme.typography.bodyMedium, color = ErrorColor)
    }
    error?.let {
        Text(text = it, style = MaterialTheme.typography.bodyMedium, color = ErrorColor)
    }
}

// ??? Source type selector ן¿½ wide layout (left sidebar) ????????????????????????

@Composable
private fun SourceTypeSelectorPanel(
    sourceType: SourceType,
    isEditing: Boolean,
    isEditLabel: String,
    showXtream: Boolean,
    showStalker: Boolean,
    onSelect: (SourceType) -> Unit,
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = SurfaceDefaults.colors(containerColor = Surface.copy(alpha = 0.92f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = isEditLabel,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.setup_shell_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.setup_source_type_label),
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
            if ((!isEditing && showXtream) || (isEditing && sourceType == SourceType.XTREAM)) {
                SourceTypeCard(
                    title = androidx.compose.ui.res.stringResource(R.string.setup_xtream),
                    subtitle = androidx.compose.ui.res.stringResource(R.string.setup_info_xtream_body),
                    selected = sourceType == SourceType.XTREAM,
                    enabled = !isEditing,
                    onClick = { onSelect(SourceType.XTREAM) }
                )
            }
            if ((!isEditing && showStalker) || (isEditing && sourceType == SourceType.STALKER)) {
                SourceTypeCard(
                    title = androidx.compose.ui.res.stringResource(R.string.setup_stalker),
                    badge = androidx.compose.ui.res.stringResource(R.string.badge_beta),
                    subtitle = androidx.compose.ui.res.stringResource(R.string.setup_info_stalker_body),
                    selected = sourceType == SourceType.STALKER,
                    enabled = !isEditing,
                    onClick = { onSelect(SourceType.STALKER) }
                )
            }
            if (!isEditing) {
                ImportOptionsButton(
                    text = stringResource(R.string.settings_restore_data),
                    onClick = onImportClick,
                    compact = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.setup_info_manage_title),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.setup_info_manage_body),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim.copy(alpha = 0.55f)
            )
        }
    }
}

@Composable
private fun SourceTypeCard(
    title: String,
    badge: String? = null,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = { if (enabled) onClick() },
        modifier = Modifier.fillMaxWidth().mouseClickable(enabled = enabled, onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor  = if (selected) Primary.copy(alpha = 0.18f) else SurfaceElevated,
            focusedContainerColor = if (selected) Primary.copy(alpha = 0.28f) else SurfaceHighlight
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (selected) Primary.copy(alpha = 0.5f) else SurfaceHighlight)),
            focusedBorder = Border(BorderStroke(2.dp, FocusBorder))
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) Primary else TextPrimary
                )
                badge?.let {
                    StatusPill(
                        label = it,
                        containerColor = AccentAmber,
                        contentColor = Color.Black,
                        horizontalPadding = 6.dp,
                        verticalPadding = 2.dp,
                        cornerRadius = 6.dp
                    )
                }
            }
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim, maxLines = 2)
        }
    }
}

// ??? Source type row ן¿½ narrow layout (top tabs) ???????????????????????????????

@Composable
private fun SourceTypeTabRow(
    sourceType: SourceType,
    isEditing: Boolean,
    showXtream: Boolean,
    showStalker: Boolean,
    onSelect: (SourceType) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if ((!isEditing && showXtream) || (isEditing && sourceType == SourceType.XTREAM)) {
            TabButton(
                text = androidx.compose.ui.res.stringResource(R.string.setup_xtream),
                isSelected = sourceType == SourceType.XTREAM,
                onClick = { if (!isEditing) onSelect(SourceType.XTREAM) }
            )
        }
        if ((!isEditing && showStalker) || (isEditing && sourceType == SourceType.STALKER)) {
            TabButton(
                text = androidx.compose.ui.res.stringResource(R.string.setup_stalker),
                badge = androidx.compose.ui.res.stringResource(R.string.badge_beta),
                isSelected = sourceType == SourceType.STALKER,
                onClick = { if (!isEditing) onSelect(SourceType.STALKER) }
            )
        }
    }
}

// ??? ProviderTextField ????????????????????????????????????????????????????????
//
// Key fix: uses BasicTextField with decorationBox and tracks focus via
// onFocusEvent { it.hasFocus } ן¿½ hasFocus is true when this node OR any
// descendant (the actual cursor/text composable) has focus. The old approach
// used onFocusChanged { isFocused } on an outer Box, which became false the
// moment the inner BasicTextField took focus, breaking keyboard scroll.

@Composable
private fun ProviderTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    val isTelevisionDevice = rememberIsTelevisionDevice()
    var hasContainerFocus by remember { mutableStateOf(false) }
    var hasInputFocus by remember { mutableStateOf(false) }
    var acceptsInput by remember(isTelevisionDevice) { mutableStateOf(!isTelevisionDevice) }
    var pendingInputActivation by remember { mutableStateOf(false) }
    var editBaselineValue by remember { mutableStateOf(value) }
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }
    var isPasswordVisible by rememberSaveable(isPassword) { mutableStateOf(false) }
    var revealedPasswordIndex by remember { mutableStateOf<Int?>(null) }
    var previousValue by remember { mutableStateOf(value) }
    val containerFocusRequester = remember { FocusRequester() }
    val inputFocusRequester = remember { FocusRequester() }
    val visibilityToggleFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()
    val isFocused = hasContainerFocus || hasInputFocus
    val passwordVisibilityDescription = if (isPassword) {
        androidx.compose.ui.res.stringResource(
            if (isPasswordVisible) R.string.setup_hide_password else R.string.setup_show_password
        )
    } else {
        null
    }

    fun activateInput() {
        if (!acceptsInput) {
            editBaselineValue = value
        }
        if (!isTelevisionDevice) {
            acceptsInput = true
            inputFocusRequester.requestFocus()
            keyboardController?.show()
            coroutineScope.launch {
                runCatching { bringIntoViewRequester.bringIntoView() }
                delay(180)
                runCatching { bringIntoViewRequester.bringIntoView() }
            }
            return
        }
        acceptsInput = true
        pendingInputActivation = true
        coroutineScope.launch {
            runCatching { bringIntoViewRequester.bringIntoView() }
        }
    }

    LaunchedEffect(value) {
        if (value != fieldValue.text) {
            val coercedSelectionStart = fieldValue.selection.start.coerceIn(0, value.length)
            val coercedSelectionEnd = fieldValue.selection.end.coerceIn(0, value.length)
            val coercedComposition = fieldValue.composition?.let { composition ->
                val compositionStart = composition.start.coerceIn(0, value.length)
                val compositionEnd = composition.end.coerceIn(0, value.length)
                if (compositionStart <= compositionEnd) {
                    TextRange(compositionStart, compositionEnd)
                } else {
                    null
                }
            }
            fieldValue = fieldValue.copy(
                text = value,
                selection = TextRange(coercedSelectionStart, coercedSelectionEnd),
                composition = coercedComposition
            )
        }
    }

    LaunchedEffect(acceptsInput, pendingInputActivation) {
        if (!isTelevisionDevice || !acceptsInput || !pendingInputActivation) {
            return@LaunchedEffect
        }
        inputFocusRequester.requestFocus()
        keyboardController?.show()
        coroutineScope.launch {
            delay(120)
            runCatching { bringIntoViewRequester.bringIntoView() }
        }
        pendingInputActivation = false
    }

    LaunchedEffect(isPassword) {
        if (!isPassword) {
            isPasswordVisible = false
        }
    }

    // Show most-recently typed character briefly before masking
    LaunchedEffect(value, isPassword) {
        if (!isPassword) { previousValue = value; revealedPasswordIndex = null; return@LaunchedEffect }
        revealedPasswordIndex = when {
            value.length > previousValue.length && value.isNotEmpty() -> value.lastIndex
            value.isEmpty() -> null
            else -> revealedPasswordIndex?.takeIf { it < value.length }
        }
        previousValue = value
    }
    LaunchedEffect(revealedPasswordIndex, value, isPassword) {
        val idx = revealedPasswordIndex ?: return@LaunchedEffect
        if (!isPassword || idx >= value.length) return@LaunchedEffect
        delay(1500)
        if (revealedPasswordIndex == idx) revealedPasswordIndex = null
    }

    val borderColor by animateColorAsState(if (isFocused) Primary else SurfaceHighlight, tween(150), label = "border")
    val bgColor     by animateColorAsState(if (isFocused) Surface  else SurfaceElevated, tween(150), label = "bg")

    Box(
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(containerFocusRequester)
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusEvent {
                hasContainerFocus = it.hasFocus
                if (!it.hasFocus && isTelevisionDevice) {
                    acceptsInput = false
                    keyboardController?.hide()
                }
            }
            .mouseClickable(focusRequester = containerFocusRequester, onClick = ::activateInput)
            .clickable(onClick = ::activateInput)
            .focusable()
            .padding(0.dp)
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = fieldValue,
            onValueChange = { updatedValue ->
                fieldValue = updatedValue
                if (updatedValue.text != value) {
                    onValueChange(updatedValue.text)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(inputFocusRequester)
                .focusProperties {
                    canFocus = !isTelevisionDevice || acceptsInput
                    if (isTelevisionDevice && acceptsInput) {
                        left = FocusRequester.Cancel
                        right = if (isPassword) visibilityToggleFocusRequester else FocusRequester.Cancel
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (!isTelevisionDevice || !acceptsInput || event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) {
                        return@onPreviewKeyEvent false
                    }
                    val cursor = fieldValue.selection.end
                    when (event.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_BACK -> {
                            fieldValue = TextFieldValue(
                                text = editBaselineValue,
                                selection = TextRange(editBaselineValue.length)
                            )
                            if (editBaselineValue != value) {
                                onValueChange(editBaselineValue)
                            }
                            acceptsInput = false
                            pendingInputActivation = false
                            keyboardController?.hide()
                            containerFocusRequester.requestFocus()
                            true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                            val nextCursor = (cursor - 1).coerceAtLeast(0)
                            fieldValue = fieldValue.copy(selection = TextRange(nextCursor))
                            true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (isPassword && cursor >= fieldValue.text.length) {
                                visibilityToggleFocusRequester.requestFocus()
                                return@onPreviewKeyEvent true
                            }
                            val nextCursor = (cursor + 1).coerceAtMost(fieldValue.text.length)
                            fieldValue = fieldValue.copy(selection = TextRange(nextCursor))
                            true
                        }
                        else -> false
                    }
                }
                .onFocusEvent {
                    hasInputFocus = it.hasFocus
                    if (it.hasFocus) {
                        coroutineScope.launch {
                            delay(120)
                            runCatching { bringIntoViewRequester.bringIntoView() }
                        }
                    } else if (isTelevisionDevice) {
                        keyboardController?.hide()
                    }
                },
            singleLine = true,
            readOnly = isTelevisionDevice && !acceptsInput,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = OnBackground),
            visualTransformation = when {
                !isPassword || isPasswordVisible -> VisualTransformation.None
                else -> RevealingPasswordVisualTransformation(revealedPasswordIndex)
            },
            cursorBrush = SolidColor(Primary),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .background(bgColor, RoundedCornerShape(10.dp))
                        .border(if (isFocused) 2.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (value.isEmpty()) {
                            Text(text = placeholder, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
                        }
                        innerTextField()
                    }

                    if (isPassword) {
                        Box(
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .size(24.dp)
                                .focusRequester(visibilityToggleFocusRequester)
                                .focusProperties {
                                    canFocus = !isTelevisionDevice || acceptsInput
                                    left = inputFocusRequester
                                }
                                .onFocusEvent {
                                    if (it.hasFocus) {
                                        coroutineScope.launch {
                                            delay(120)
                                            runCatching { bringIntoViewRequester.bringIntoView() }
                                        }
                                    }
                                }
                                .semantics {
                                    contentDescription = passwordVisibilityDescription.orEmpty()
                                }
                                .clickable {
                                    isPasswordVisible = !isPasswordVisible
                                }
                                .mouseClickable(focusRequester = visibilityToggleFocusRequester) {
                                    isPasswordVisible = !isPasswordVisible
                                }
                                .focusable(enabled = !isTelevisionDevice || acceptsInput)
                        ) {
                            PasswordVisibilityGlyph(
                                isVisible = isPasswordVisible,
                                tint = if (isFocused) Primary else OnSurfaceDim,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun PasswordVisibilityGlyph(
    isVisible: Boolean,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(18.dp)) {
        val stroke = Stroke(width = size.minDimension * 0.11f)
        drawOval(
            color = tint,
            topLeft = androidx.compose.ui.geometry.Offset(1.6f, size.height * 0.22f),
            size = androidx.compose.ui.geometry.Size(size.width - 3.2f, size.height * 0.56f),
            style = stroke
        )
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.14f,
            center = center
        )
        if (!isVisible) {
            drawLine(
                color = tint,
                start = androidx.compose.ui.geometry.Offset(size.width * 0.18f, size.height * 0.82f),
                end = androidx.compose.ui.geometry.Offset(size.width * 0.82f, size.height * 0.18f),
                strokeWidth = size.minDimension * 0.11f
            )
        }
    }
}

// ??? Sync progress dialog ?????????????????????????????????????????????????????

@Composable
fun SyncProgressDialog(message: String) {
    val fraction = extractProgressFraction(message)
    val animatedFraction by animateFloatAsState(
        targetValue = fraction ?: 0f,
        animationSpec = tween(durationMillis = 400),
        label = "syncFraction"
    )
    PremiumDialog(
        title = androidx.compose.ui.res.stringResource(R.string.settings_syncing_title),
        subtitle = androidx.compose.ui.res.stringResource(R.string.settings_syncing_subtitle),
        onDismissRequest = {},
        widthFraction = 0.32f,
        heightFraction = null,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(color = Primary)
                StatusPill(
                    label = androidx.compose.ui.res.stringResource(R.string.settings_syncing_btn),
                    containerColor = PrimaryGlow
                )
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { animatedFraction },
                        color = Primary,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(
                        color = Primary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnBackground,
                    textAlign = TextAlign.Center
                )
            }
        }
    )
}

// ??? ActionButton ?????????????????????????????????????????????????????????????

@Composable
private fun ActionButton(
    text: String,
    isLoading: Boolean = false,
    onClick: () -> Unit
) {
    ProviderActionButton(
        text = text,
        height = 52.dp,
        isLoading = isLoading,
        onClick = onClick
    )
}

@Composable
private fun SmallActionButton(
    text: String,
    isLoading: Boolean = false,
    onClick: () -> Unit
) {
    ProviderActionButton(
        text = text,
        height = 40.dp,
        isLoading = isLoading,
        onClick = onClick
    )
}

@Composable
private fun ProviderActionButton(
    text: String,
    height: androidx.compose.ui.unit.Dp,
    isLoading: Boolean = false,
    onClick: () -> Unit
) {
    TvClickableSurface(
        onClick = onClick,
        enabled = !isLoading,
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (!isLoading) Primary else SurfaceHighlight,
            focusedContainerColor = if (!isLoading) Primary else SurfaceHighlight
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow.None),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (!isLoading) PrimaryLight else SurfaceHighlight)),
            focusedBorder = Border(BorderStroke(2.dp, FocusBorder))
        )
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = OnBackground.copy(alpha = 0.6f)
                )
            } else {
                Text(text = text, style = MaterialTheme.typography.bodySmall, color = Color.White)
            }
        }
    }
}

@Composable
private fun ImportOptionsButton(
    text: String,
    onClick: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    TvClickableSurface(
        onClick = onClick,
        modifier = modifier
            .height(if (compact) 38.dp else 44.dp)
            .onFocusEvent { isFocused = it.hasFocus }
            .semantics { contentDescription = text },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Surface.copy(alpha = 0.9f),
            focusedContainerColor = SurfaceHighlight
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (isFocused) PrimaryLight else SurfaceHighlight)),
            focusedBorder = Border(BorderStroke(2.dp, FocusBorder))
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (compact) 10.dp else 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.SettingsBackupRestore,
                contentDescription = null,
                modifier = Modifier.size(if (compact) 18.dp else 22.dp),
                tint = if (isFocused) TextPrimary else OnSurface
            )
            Spacer(modifier = Modifier.width(if (compact) 6.dp else 8.dp))
            Text(
                text = text,
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                color = if (isFocused) TextPrimary else OnSurface
            )
        }
    }
}

@Composable
private fun ImportOptionsDialog(
    isImportingBackup: Boolean,
    driveSignedIn: Boolean,
    onDismiss: () -> Unit,
    onImportBackup: () -> Unit,
    onImportFromDrive: () -> Unit,
    onDriveSignIn: () -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.settings_backup_restore),
        subtitle = stringResource(R.string.settings_restore_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.34f,
        heightFraction = null,
        bodyHeightFraction = 0.28f,
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                ImportDialogActionButton(
                    text = stringResource(R.string.setup_import_backup),
                    isLoading = isImportingBackup,
                    onClick = onImportBackup
                )
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.add_group_cancel),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun ImportDialogActionButton(
    text: String,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth(0.72f)) {
        SmallActionButton(
            text = text,
            isLoading = isLoading,
            onClick = onClick
        )
    }
}

// ??? FileSelectorCard ?????????????????????????????????????????????????????????

@Composable
private fun FileSelectorCard(
    fileName: String?,
    fileSelectedHint: String,
    emptySelectionTitle: String,
    emptySelectionHint: String,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val borderColor = if (isFocused) Primary else SurfaceHighlight
    val bgColor     = if (isFocused) Surface  else SurfaceElevated

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(80.dp).onFocusEvent { isFocused = it.hasFocus }.mouseClickable(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, borderColor)),
            focusedBorder = Border(BorderStroke(2.dp, FocusBorder))
        ),
        colors = ClickableSurfaceDefaults.colors(containerColor = bgColor, focusedContainerColor = bgColor)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (fileName != null) {
                Text(text = fileName, style = MaterialTheme.typography.bodyLarge, color = OnBackground, textAlign = TextAlign.Center)
                Text(text = fileSelectedHint, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
            } else {
                Text(text = emptySelectionTitle, style = MaterialTheme.typography.bodyLarge, color = OnBackground)
                Text(text = emptySelectionHint, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
            }
        }
    }
}

// ??? TabButton (used by SourceTypeTabRow) ?????????????????????????????????????

@Composable
private fun TabButton(text: String, isSelected: Boolean, onClick: () -> Unit, badge: String? = null) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.onFocusEvent { isFocused = it.hasFocus }.mouseClickable(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Primary.copy(alpha = 0.2f) else Surface,
            focusedContainerColor = SurfaceHighlight
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (isSelected) Primary.copy(alpha = 0.4f) else SurfaceHighlight)),
            focusedBorder = Border(BorderStroke(2.dp, FocusBorder))
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected) Primary else if (isFocused) TextPrimary else OnSurface
            )
            badge?.let {
                StatusPill(
                    label = it,
                    containerColor = AccentAmber,
                    contentColor = Color.Black,
                    horizontalPadding = 6.dp,
                    verticalPadding = 2.dp,
                    cornerRadius = 6.dp
                )
            }
        }
    }
}

// ─── Revealing password visual transformation ─────────────────────────────────

private class RevealingPasswordVisualTransformation(
    private val revealedIndex: Int?
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val transformed = buildString(text.length) {
            text.text.forEachIndexed { index, ch ->
                append(if (revealedIndex != null && index == revealedIndex) ch else '*')
            }
        }
        return TransformedText(AnnotatedString(transformed), OffsetMapping.Identity)
    }
}

// ─── File cleanup helpers ─────────────────────────────────────────────────────

private fun cleanupOldImportedM3uFiles(
    filesDir: java.io.File,
    protectedFileUris: Set<String>,
    keepLatest: Int
) {
    val protectedPaths = protectedFileUris
        .mapNotNull { it.removePrefix("file://").takeIf { p -> p.isNotBlank() } }
        .toSet()

    val importedFiles = filesDir
        .listFiles { file ->
            file.isFile && file.name.startsWith("m3u_") &&
                (file.name.endsWith(".m3u") || file.name.endsWith(".m3u8"))
        }
        ?.sortedByDescending { it.lastModified() }
        ?: return

    importedFiles.drop(keepLatest).forEach { stale ->
        if (stale.absolutePath !in protectedPaths) runCatching { stale.delete() }
    }
}

private suspend fun cleanupOldImportedM3uFilesAsync(
    filesDir: java.io.File,
    protectedFileUris: Set<String>,
    keepLatest: Int
) = withContext(Dispatchers.IO) {
    cleanupOldImportedM3uFiles(filesDir, protectedFileUris, keepLatest)
}

private fun resolveFileImportError(context: android.content.Context, error: Throwable): String {
    val msg = error.message.orEmpty()
    val isStorageFull = msg.contains("ENOSPC", ignoreCase = true) ||
        msg.contains("no space", ignoreCase = true) ||
        msg.contains("space left", ignoreCase = true)
    return if (isStorageFull) context.getString(R.string.setup_file_import_storage_full)
           else context.getString(R.string.setup_file_import_failed)
}

@Composable
private fun PortalSelector(
    selectedUrl: String,
    portals: List<com.streamvault.app.pairing.PortalInfo>,
    portalType: String,
    onPortalSelected: (com.streamvault.app.pairing.PortalInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }
    val filteredPortals = portals.filter { it.type.lowercase() == portalType.lowercase() }
    
    val selectedPortal = filteredPortals.find { it.url == selectedUrl }
    val displayText = when {
        selectedPortal != null -> selectedPortal.name
        else -> "Select Portal"
    }

    var isFocused by remember { mutableStateOf(false) }
    val borderColor = if (isFocused) Primary else SurfaceHighlight
    val bgColor     = if (isFocused) Surface  else SurfaceElevated

    Surface(
        onClick = { showDialog = true },
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .onFocusEvent { isFocused = it.hasFocus }
            .mouseClickable(onClick = { showDialog = true }),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, borderColor)),
            focusedBorder = Border(BorderStroke(2.dp, FocusBorder))
        ),
        colors = ClickableSurfaceDefaults.colors(containerColor = bgColor, focusedContainerColor = bgColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = displayText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selectedUrl.isNotEmpty()) OnBackground else OnSurfaceDim
            )
            Text(
                text = "▼",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
        }
    }

    if (showDialog) {
        PremiumDialog(
            title = "Select Portal",
            subtitle = "Choose a portal from the list",
            onDismissRequest = { showDialog = false },
            content = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    if (filteredPortals.isEmpty()) {
                        Text(
                            text = "No portals available.",
                            color = OnSurfaceDim,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        filteredPortals.forEach { portal ->
                            var itemFocused by remember { mutableStateOf(false) }
                            Surface(
                                onClick = {
                                    onPortalSelected(portal)
                                    showDialog = false
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusEvent { itemFocused = it.hasFocus }
                                    .mouseClickable {
                                        onPortalSelected(portal)
                                        showDialog = false
                                    },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = if (itemFocused) Primary.copy(alpha = 0.15f) else Color.Transparent,
                                    focusedContainerColor = Primary.copy(alpha = 0.25f)
                                ),
                                border = ClickableSurfaceDefaults.border(
                                    border = Border(BorderStroke(1.dp, if (itemFocused) Primary else Color.Transparent)),
                                    focusedBorder = Border(BorderStroke(2.dp, FocusBorder))
                                )
                            ) {
                                Text(
                                    text = portal.name,
                                    modifier = Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = OnBackground
                                )
                            }
                        }
                    }
                }
            },
            footer = {
                PremiumDialogFooterButton(
                    label = "Cancel",
                    onClick = { showDialog = false }
                )
            }
        )
    }
}

