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

    fun browseAndAddLocation() {
        if (getPlatformActions().isAndroid()) {
            showLocationPicker()
        } else {
            scope.launch {
                val chosen = getPlatformActions().chooseDirectory()
                if (!chosen.isNullOrBlank()) {
                    updateDownloadDirectory(chosen)
                }
            }
        }
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

    fun checkAndUpdateBinaries() {
        if (_uiState.value.isUpdatingBinaries) return

        _uiState.value = _uiState.value.copy(
            isUpdatingBinaries = true,
            updateStatusMessage = "Checking for yt-dlp updates..."
        )

        scope.launch {
            val result = binaryManager.updateBinaries()
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
                        updateStatusMessage = "Update check failed: ${err.message ?: "Network error"}"
                    )
                }
            )
        }
    }
}
