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
        val primaryContent = readTextFile(settingsFileName)
        if (!primaryContent.isNullOrBlank()) {
            try {
                return json.decodeFromString<AppSettings>(primaryContent)
            } catch (e: Exception) {
                println("Failed to decode primary settings JSON: ${e.message}, attempting backup recovery")
            }
        }
        val backupContent = readTextFile("$settingsFileName.bak")
        if (!backupContent.isNullOrBlank()) {
            try {
                return json.decodeFromString<AppSettings>(backupContent)
            } catch (_: Exception) {}
        }
        return AppSettings(downloadDirectory = getPlatformActions().getDefaultDownloadDirectory())
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
