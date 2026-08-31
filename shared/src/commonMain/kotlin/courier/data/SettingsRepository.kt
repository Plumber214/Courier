package courier.data

import courier.model.AppSettings
import courier.platform.getPlatformActions
import courier.platform.readTextFile
import courier.platform.saveTextFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

class SettingsRepository {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val settingsFileName = "courier_settings.json"

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadSettings(): AppSettings {
        return try {
            val content = readTextFile(settingsFileName)
            if (!content.isNullOrBlank()) {
                json.decodeFromString<AppSettings>(content)
            } else {
                AppSettings(downloadDirectory = getPlatformActions().getDefaultDownloadDirectory())
            }
        } catch (e: Exception) {
            AppSettings(downloadDirectory = getPlatformActions().getDefaultDownloadDirectory())
        }
    }

    fun updateSettings(newSettings: AppSettings) {
        _settings.value = newSettings
        try {
            val content = json.encodeToString(AppSettings.serializer(), newSettings)
            saveTextFile(settingsFileName, content)
        } catch (e: Exception) {
            println("Failed to save settings: ")
        }
    }
}
