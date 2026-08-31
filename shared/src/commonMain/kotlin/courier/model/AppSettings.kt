package courier.model

import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val defaultQuality: String = "best", // "best", "1080p", "720p", "480p", "audio_only"
    val downloadDirectory: String = "",
    val savedDownloadLocations: List<String> = emptyList(),
    val maxConcurrentDownloads: Int = 3,
    val selectedCookieBrowser: String = "None", // "None", "chrome", "firefox", "edge", "brave"
    val isFirstLaunchCompleted: Boolean = false
)
