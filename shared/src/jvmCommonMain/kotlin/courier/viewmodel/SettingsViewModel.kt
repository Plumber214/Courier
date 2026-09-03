package courier.viewmodel

import courier.data.SettingsRepository
import courier.engine.BinaryManager
import courier.model.AppSettings
import courier.model.OutputProfile
import courier.model.TranscodeCodec
import courier.platform.getPlatformActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val updateStatusMessage: String? = null,
    val isUpdatingBinaries: Boolean = false,
    val binaryVersion: String = ""
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val binaryManager: BinaryManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    val settings: StateFlow<AppSettings> = settingsRepository.settings

    private val _uiState = MutableStateFlow(
        SettingsUiState(binaryVersion = binaryManager.getBinaryVersion())
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val defaultDownloadDirectory: String = getPlatformActions().getDefaultDownloadDirectory()

    private val _showLocationPickerDialog = MutableStateFlow(false)
    val showLocationPickerDialog: StateFlow<Boolean> = _showLocationPickerDialog.asStateFlow()

    // Device Link, surfaced here so its identity and switch sit with the rest
    // of the app's configuration rather than only on a tab you have to know to
    // open.
    private val linkManager get() = courier.di.AppModule.deviceLinkManager
    val myDeviceIdentity: StateFlow<courier.link.DeviceIdentity> get() = linkManager.myIdentity
    val pairedDevices: StateFlow<List<courier.link.PairedDevice>> get() = linkManager.trustStore.pairedDevices

    private val _showRenameDialog = MutableStateFlow(false)
    val showRenameDialog: StateFlow<Boolean> = _showRenameDialog.asStateFlow()

    fun openRenameDialog() {
        _showRenameDialog.value = true
    }

    fun closeRenameDialog() {
        _showRenameDialog.value = false
    }

    fun submitNewDeviceName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        linkManager.updateDeviceName(trimmed)
        _showRenameDialog.value = false
    }

    fun updateDeviceLinkEnabled(enabled: Boolean) {
        settingsRepository.updateSettings(settings.value.copy(deviceLinkEnabled = enabled))
        linkManager.setLinkEnabled(enabled)
    }

    fun updateWriteSubtitles(enabled: Boolean) {
        settingsRepository.updateSettings(settings.value.copy(writeSubtitles = enabled))
    }

    fun toggleSubtitleLanguage(code: String) {
        val current = settings.value.subtitleLanguages.toMutableList()
        if (!current.remove(code)) {
            current.add(code)
        }
        // Never leave the list empty while subtitles are on: yt-dlp would
        // then be asked for no languages and quietly fetch nothing.
        val resolved = current.ifEmpty { listOf("en") }
        settingsRepository.updateSettings(settings.value.copy(subtitleLanguages = resolved))
    }

    fun updateEmbedChapters(enabled: Boolean) {
        settingsRepository.updateSettings(settings.value.copy(embedChapters = enabled))
    }

    fun updateEmbedThumbnail(enabled: Boolean) {
        settingsRepository.updateSettings(settings.value.copy(embedThumbnail = enabled))
    }

    fun updateEmbedMetadata(enabled: Boolean) {
        settingsRepository.updateSettings(settings.value.copy(embedMetadata = enabled))
    }

    fun showLocationPicker() {
        _showLocationPickerDialog.value = true
    }

    fun dismissLocationPicker() {
        _showLocationPickerDialog.value = false
    }

    fun onLocationPicked(path: String) {
        _showLocationPickerDialog.value = false
        updateDownloadDirectory(path)
    }

    fun updateDefaultQuality(quality: String) {
        settingsRepository.updateSettings(settings.value.copy(defaultQuality = quality))
    }

    fun updateDownloadDirectory(dir: String) {
        val currentLocations = settings.value.savedDownloadLocations.toMutableList()
        if (dir.isNotBlank() && !currentLocations.contains(dir)) {
            currentLocations.add(dir)
        }
        settingsRepository.updateSettings(
            settings.value.copy(
                downloadDirectory = dir,
                savedDownloadLocations = currentLocations
            )
        )
    }

    fun addSavedLocation(dir: String) {
        if (dir.isBlank()) return
        val current = settings.value.savedDownloadLocations.toMutableList()
        if (!current.contains(dir)) {
            current.add(dir)
            settingsRepository.updateSettings(settings.value.copy(savedDownloadLocations = current))
        }
    }

    fun removeSavedLocation(dir: String) {
        val current = settings.value.savedDownloadLocations.toMutableList()
        current.remove(dir)
        val active = if (settings.value.downloadDirectory == dir) {
            current.firstOrNull() ?: ""
        } else {
            settings.value.downloadDirectory
        }
        settingsRepository.updateSettings(
            settings.value.copy(
                downloadDirectory = active,
                savedDownloadLocations = current
            )
        )
    }

    /**
     * Opens the in-app folder picker on both platforms.
     *
     * Desktop used to open a Swing `JFileChooser` here: a system dialog in the
     * platform look and feel, dropped into the middle of a dark Compose app.
     */
    fun browseAndAddLocation() {
        showLocationPicker()
    }

    fun updateMaxConcurrentDownloads(limit: Int) {
        settingsRepository.updateSettings(settings.value.copy(maxConcurrentDownloads = limit.coerceIn(1, 5)))
    }

    fun updateCookieBrowser(browser: String) {
        settingsRepository.updateSettings(settings.value.copy(selectedCookieBrowser = browser))
    }

    fun updateOutputProfile(profile: OutputProfile) {
        settingsRepository.updateSettings(settings.value.copy(outputProfile = profile))
    }

    fun updateTranscodeCodec(codec: TranscodeCodec) {
        settingsRepository.updateSettings(settings.value.copy(transcodeCodec = codec))
    }

    fun updateAutoCheckAppUpdates(enabled: Boolean) {
        settingsRepository.updateSettings(settings.value.copy(autoCheckAppUpdates = enabled))
    }

    fun checkAppUpdates() {
        courier.di.AppModule.appUpdateManager.checkForUpdates(manual = true)
    }

    fun restartAndApplyAppUpdate() {
        courier.di.AppModule.appUpdateManager.applyUpdateAndRestart()
    }

    init {
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        val sevenDaysMs = 7L * 24 * 60 * 60 * 1000L
        if (now - settings.value.lastEngineUpdateCheckEpochMs > sevenDaysMs) {
            checkAndUpdateBinaries(isAutoCheck = true)
        }
    }

    fun checkAndUpdateBinaries(isAutoCheck: Boolean = false) {
        if (_uiState.value.isUpdatingBinaries) return

        if (!isAutoCheck) {
            _uiState.value = _uiState.value.copy(
                isUpdatingBinaries = true,
                updateStatusMessage = "Checking for yt-dlp updates..."
            )
        }

        scope.launch {
            val result = binaryManager.updateBinaries()
            val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
            settingsRepository.updateSettings(settings.value.copy(lastEngineUpdateCheckEpochMs = now))

            result.fold(
                onSuccess = { msg ->
                    _uiState.value = _uiState.value.copy(
                        isUpdatingBinaries = false,
                        updateStatusMessage = msg,
                        binaryVersion = binaryManager.getBinaryVersion()
                    )
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        isUpdatingBinaries = false,
                        updateStatusMessage = if (!isAutoCheck) "Update check failed: ${err.message ?: "Network error"}" else null
                    )
                }
            )
        }
    }
}
