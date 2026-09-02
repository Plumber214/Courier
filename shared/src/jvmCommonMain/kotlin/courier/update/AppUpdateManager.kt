package courier.update

import courier.data.SettingsRepository
import courier.platform.getPlatformActions
import courier.util.AppVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

sealed interface AppUpdateState {
    object Idle : AppUpdateState
    object Checking : AppUpdateState
    data class UpdateAvailable(
        val currentVersion: String,
        val latestVersion: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val assetName: String,
        val sizeBytes: Long
    ) : AppUpdateState
    data class Downloading(
        val latestVersion: String,
        val progressPercent: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedFormatted: String
    ) : AppUpdateState
    data class UpdateReady(
        val latestVersion: String,
        val releaseNotes: String,
        val stagedFilePath: String
    ) : AppUpdateState
    data class UpToDate(
        val version: String,
        val lastCheckedEpochMs: Long
    ) : AppUpdateState
    data class Error(
        val message: String,
        val lastCheckedEpochMs: Long
    ) : AppUpdateState
}

class AppUpdateManager(
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val _updateState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val updateState: StateFlow<AppUpdateState> = _updateState.asStateFlow()

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private var activeJob: Job? = null

    private val GITHUB_REPO = "Plumber214/Courier"
    private val RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    fun checkForUpdates(manual: Boolean = false) {
        if (!manual && !settingsRepository.settings.value.autoCheckAppUpdates) {
            return
        }

        activeJob?.cancel()
        activeJob = scope.launch {
            _updateState.value = AppUpdateState.Checking
            val now = System.currentTimeMillis()
            settingsRepository.updateSettings { it.copy(lastAppUpdateCheckEpochMs = now) }

            try {
                val req = HttpRequest.newBuilder()
                    .uri(URI.create(RELEASES_API_URL))
                    .header("User-Agent", "Courier-Desktop/${AppVersion.VERSION_NAME}")
                    .header("Accept", "application/vnd.github.v3+json")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build()

                val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
                if (res.statusCode() != 200) {
                    _updateState.value = AppUpdateState.Error("GitHub API returned ${res.statusCode()}", now)
                    return@launch
                }

                val parsed = json.parseToJsonElement(res.body()).jsonObject
                val tagName = parsed["tag_name"]?.jsonPrimitive?.content ?: ""
                val body = parsed["body"]?.jsonPrimitive?.content ?: ""
                val assets = parsed["assets"]?.jsonArray ?: emptyList()

                val currentVer = SemanticVersion.parse(AppVersion.VERSION_NAME)
                val latestVer = SemanticVersion.parse(tagName)

                if (currentVer != null && latestVer != null && latestVer > currentVer) {
                    // Find suitable desktop asset
                    var downloadUrl = ""
                    var assetName = ""
                    var sizeBytes = 0L

                    for (assetElem in assets) {
                        val obj = assetElem.jsonObject
                        val name = obj["name"]?.jsonPrimitive?.content ?: ""
                        if (name.endsWith(".jar", ignoreCase = true) || name.endsWith(".zip", ignoreCase = true)) {
                            downloadUrl = obj["browser_download_url"]?.jsonPrimitive?.content ?: ""
                            assetName = name
                            sizeBytes = obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                            break
                        }
                    }

                    if (downloadUrl.isNotBlank()) {
                        _updateState.value = AppUpdateState.UpdateAvailable(
                            currentVersion = AppVersion.VERSION_NAME,
                            latestVersion = latestVer.toString(),
                            releaseNotes = body,
                            downloadUrl = downloadUrl,
                            assetName = assetName,
                            sizeBytes = sizeBytes
                        )

                        // Automatically begin downloading and staging the update
                        downloadAndStageUpdate(latestVer.toString(), body, downloadUrl, assetName, sizeBytes)
                    } else {
                        _updateState.value = AppUpdateState.UpToDate(AppVersion.VERSION_NAME, now)
                    }
                } else {
                    _updateState.value = AppUpdateState.UpToDate(AppVersion.VERSION_NAME, now)
                }
            } catch (e: Exception) {
                _updateState.value = AppUpdateState.Error(e.message ?: "Failed to check for updates", now)
            }
        }
    }

    private suspend fun downloadAndStageUpdate(
        version: String,
        notes: String,
        url: String,
        assetName: String,
        sizeBytes: Long
    ) {
        val updateDir = getUpdateStagingDir()
        if (!updateDir.exists()) updateDir.mkdirs()

        val stagedFile = File(updateDir, "staged_$assetName")

        try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Courier-Desktop/${AppVersion.VERSION_NAME}")
                .GET()
                .build()

            val res = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream())
            if (res.statusCode() !in 200..299) {
                _updateState.value = AppUpdateState.Error("Download failed with HTTP ${res.statusCode()}", System.currentTimeMillis())
                return
            }

            val totalBytes = if (sizeBytes > 0) sizeBytes else res.headers().firstValueAsLong("Content-Length").orElse(0L)
            var downloaded = 0L
            val startTime = System.currentTimeMillis()
            var lastUpdateEmit = 0L

            res.body().use { input ->
                FileOutputStream(stagedFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastUpdateEmit > 200L || downloaded == totalBytes) {
                            lastUpdateEmit = now
                            val elapsedSec = (now - startTime) / 1000.0
                            val speedBytesPerSec = if (elapsedSec > 0) (downloaded / elapsedSec) else 0.0
                            val speedFormatted = formatSpeed(speedBytesPerSec)
                            val percent = if (totalBytes > 0) ((downloaded.toFloat() / totalBytes) * 100f).coerceIn(0f, 100f) else 0f

                            _updateState.value = AppUpdateState.Downloading(
                                latestVersion = version,
                                progressPercent = percent,
                                downloadedBytes = downloaded,
                                totalBytes = totalBytes,
                                speedFormatted = speedFormatted
                            )
                        }
                    }
                }
            }

            if (stagedFile.length() > 0) {
                _updateState.value = AppUpdateState.UpdateReady(
                    latestVersion = version,
                    releaseNotes = notes,
                    stagedFilePath = stagedFile.absolutePath
                )
            } else {
                _updateState.value = AppUpdateState.Error("Downloaded update artifact was empty", System.currentTimeMillis())
            }
        } catch (e: Exception) {
            _updateState.value = AppUpdateState.Error("Download interrupted: ${e.message}", System.currentTimeMillis())
        }
    }

    fun applyUpdateAndRestart() {
        val state = _updateState.value
        if (state !is AppUpdateState.UpdateReady) return

        val stagedFile = File(state.stagedFilePath)
        if (!stagedFile.exists()) return

        try {
            val isWindows = System.getProperty("os.name")?.lowercase()?.contains("win") == true
            val currentPid = ProcessHandle.current().pid()

            // Resolve target location
            val runningJarUri = AppUpdateManager::class.java.protectionDomain?.codeSource?.location?.toURI()
            val runningJarFile = runningJarUri?.let { File(it) } ?: File("release/Courier-Desktop-latest.jar")
            val appDir = runningJarFile.parentFile ?: File(".")

            if (isWindows) {
                val scriptFile = File(getUpdateStagingDir(), "apply_update.bat")
                val scriptContent = """
@echo off
setlocal
echo Waiting for Courier (PID $currentPid) to exit...
:waitloop
tasklist /fi "PID eq $currentPid" | find "$currentPid" >nul
if not errorlevel 1 (
    timeout /t 1 /nobreak >nul
    goto waitloop
)

echo Replacing application files...
copy /y "${stagedFile.absolutePath}" "${runningJarFile.absolutePath}" >nul

echo Relaunching Courier...
start "" "${runningJarFile.absolutePath}"

del "%~f0"
exit
                """.trimIndent()

                scriptFile.writeText(scriptContent)

                val pb = ProcessBuilder("cmd.exe", "/c", scriptFile.absolutePath)
                pb.directory(appDir)
                pb.start()
            }

            kotlin.system.exitProcess(0)
        } catch (e: Exception) {
            _updateState.value = AppUpdateState.Error("Failed to launch updater: ${e.message}", System.currentTimeMillis())
        }
    }

    fun dismiss() {
        _updateState.value = AppUpdateState.Idle
    }

    private fun getUpdateStagingDir(): File {
        val userHome = System.getProperty("user.home") ?: "."
        val isWindows = System.getProperty("os.name")?.lowercase()?.contains("win") == true
        return if (isWindows) {
            val appData = System.getenv("APPDATA") ?: userHome
            File(appData, "Courier/updates")
        } else {
            File(userHome, ".courier/updates")
        }
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024 * 1024))
            bytesPerSec >= 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024)
            else -> String.format("%.0f B/s", bytesPerSec)
        }
    }
}