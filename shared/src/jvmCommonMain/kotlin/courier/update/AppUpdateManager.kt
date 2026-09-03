package courier.update

import courier.data.SettingsRepository
import courier.security.FileChecksum
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
import java.net.HttpURLConnection
import java.net.URI

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
    /**
     * A newer version exists, but this installation cannot replace itself in
     * place — it was installed from the MSI/EXE distribution, whose jars are
     * managed by the installer.
     */
    data class ManualUpdateRequired(
        val latestVersion: String,
        val releaseNotes: String,
        val reason: String,
        val releaseUrl: String
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
                val url = URI.create(RELEASES_API_URL).toURL()
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 15000
                    setRequestProperty("User-Agent", "Courier-Desktop/${AppVersion.VERSION_NAME}")
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                }

                val code = conn.responseCode
                if (code != 200) {
                    _updateState.value = AppUpdateState.Error("GitHub API returned HTTP $code", now)
                    return@launch
                }

                val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
                val parsed = json.parseToJsonElement(responseBody).jsonObject
                val tagName = parsed["tag_name"]?.jsonPrimitive?.content ?: ""
                val body = parsed["body"]?.jsonPrimitive?.content ?: ""
                val assets = parsed["assets"]?.jsonArray ?: emptyList()

                val currentVer = SemanticVersion.parse(AppVersion.VERSION_NAME)
                val latestVer = SemanticVersion.parse(tagName)

                if (currentVer != null && latestVer != null && latestVer > currentVer) {
                    val releaseUrl = parsed["html_url"]?.jsonPrimitive?.content
                        ?: "https://github.com/$GITHUB_REPO/releases/latest"

                    // An install that cannot replace itself should not download
                    // 100 MB to discover that at the end.
                    val blocker = inPlaceUpdateBlocker()
                    if (blocker != null) {
                        _updateState.value = AppUpdateState.ManualUpdateRequired(
                            latestVersion = latestVer.toString(),
                            releaseNotes = body,
                            reason = blocker,
                            releaseUrl = releaseUrl
                        )
                        return@launch
                    }

                    var downloadUrl = ""
                    var assetName = ""
                    var sizeBytes = 0L
                    var sumsUrl: String? = null

                    for (assetElem in assets) {
                        val obj = assetElem.jsonObject
                        val name = obj["name"]?.jsonPrimitive?.content ?: ""
                        val url = obj["browser_download_url"]?.jsonPrimitive?.content ?: ""

                        if (isChecksumAsset(name)) {
                            sumsUrl = url
                            continue
                        }
                        // Match the artifact this updater actually knows how to
                        // apply, rather than the first .jar/.zip in the list.
                        if (downloadUrl.isBlank() &&
                            name.startsWith("Courier-Desktop", ignoreCase = true) &&
                            name.endsWith(".jar", ignoreCase = true)
                        ) {
                            downloadUrl = url
                            assetName = name
                            sizeBytes = obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                        }
                    }

                    if (downloadUrl.isBlank()) {
                        _updateState.value = AppUpdateState.Error(
                            "Release ${latestVer} has no desktop jar to install", now
                        )
                        return@launch
                    }

                    if (sumsUrl == null) {
                        // Refuse rather than stage something unverifiable. This
                        // artifact gets copied over the running application and
                        // executed; "no checksum published" is not a pass.
                        _updateState.value = AppUpdateState.ManualUpdateRequired(
                            latestVersion = latestVer.toString(),
                            releaseNotes = body,
                            reason = "Release ${latestVer} publishes no checksum file, so the " +
                                "download cannot be verified before it replaces this install.",
                            releaseUrl = releaseUrl
                        )
                        return@launch
                    }

                    _updateState.value = AppUpdateState.UpdateAvailable(
                        currentVersion = AppVersion.VERSION_NAME,
                        latestVersion = latestVer.toString(),
                        releaseNotes = body,
                        downloadUrl = downloadUrl,
                        assetName = assetName,
                        sizeBytes = sizeBytes
                    )

                    downloadAndStageUpdate(
                        latestVer.toString(), body, downloadUrl, assetName, sizeBytes, sumsUrl
                    )
                } else {
                    _updateState.value = AppUpdateState.UpToDate(AppVersion.VERSION_NAME, now)
                }
            } catch (e: Exception) {
                _updateState.value = AppUpdateState.Error(e.message ?: "Failed to check for updates", now)
            }
        }
    }

    /**
     * Why this installation cannot replace its own files, or null if it can.
     *
     * The uber jar is self-contained and can be overwritten in place. The
     * MSI/EXE distribution is not: `codeSource.location` there points at one
     * dependency jar inside jpackage's `app/` directory, and copying the uber
     * jar over it produces a broken installation rather than an updated one.
     * That distribution is the one the README recommends first.
     */
    private fun inPlaceUpdateBlocker(): String? {
        val isWindows = System.getProperty("os.name")?.lowercase()?.contains("win") == true
        if (!isWindows) {
            return "Automatic updates are only implemented for Windows."
        }

        val running = runningArtifact()
            ?: return "Courier is running from loose classes rather than a jar."

        if (!running.name.endsWith(".jar", ignoreCase = true)) {
            return "Courier is not running from a jar."
        }

        // jpackage lays out <install>/app/*.jar beside <install>/runtime.
        val parent = running.parentFile
        if (parent != null && parent.name.equals("app", ignoreCase = true) &&
            File(parent.parentFile, "runtime").isDirectory
        ) {
            return "This copy was installed from the Windows installer, which manages its own " +
                "files. Download the new installer to update."
        }

        return null
    }

    private fun runningArtifact(): File? = try {
        AppUpdateManager::class.java.protectionDomain?.codeSource?.location
            ?.toURI()?.let { File(it) }?.takeIf { it.exists() }
    } catch (e: Exception) {
        null
    }

    private fun isChecksumAsset(name: String): Boolean {
        val n = name.lowercase()
        return n == "sha256sums" || n == "sha2-256sums" || n == "checksums.sha256" ||
            n.endsWith(".sha256")
    }

    private suspend fun downloadAndStageUpdate(
        version: String,
        notes: String,
        downloadUrlStr: String,
        assetName: String,
        sizeBytes: Long,
        sumsUrl: String
    ) {
        val updateDir = getUpdateStagingDir()
        if (!updateDir.exists()) updateDir.mkdirs()

        val stagedFile = File(updateDir, "staged_$assetName")

        try {
            var currentUrlStr = downloadUrlStr
            var conn: HttpURLConnection
            var redirects = 0
            while (true) {
                val url = URI.create(currentUrlStr).toURL()
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "Courier-Desktop/${AppVersion.VERSION_NAME}")
                    instanceFollowRedirects = true
                }

                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_SEE_OTHER) {
                    val newLoc = conn.getHeaderField("Location")
                    if (newLoc != null && redirects < 5) {
                        currentUrlStr = newLoc
                        redirects++
                        continue
                    }
                }
                break
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                _updateState.value = AppUpdateState.Error("Download failed with HTTP $code", System.currentTimeMillis())
                return
            }

            val totalBytes = if (sizeBytes > 0) sizeBytes else conn.contentLengthLong.takeIf { it > 0 } ?: 0L
            var downloaded = 0L
            val startTime = System.currentTimeMillis()
            var lastUpdateEmit = 0L

            conn.inputStream.use { input ->
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

            if (stagedFile.length() <= 0) {
                stagedFile.delete()
                _updateState.value = AppUpdateState.Error(
                    "Downloaded update artifact was empty", System.currentTimeMillis()
                )
                return
            }

            // Verify before this file is ever offered for execution. A staged
            // artifact that fails is deleted, not kept around to be retried.
            val expected = try {
                val sumsText = URI.create(sumsUrl).toURL().openStream()
                    .bufferedReader().use { it.readText() }
                FileChecksum.findInSumsFile(sumsText, assetName)
            } catch (e: Exception) {
                null
            }

            if (expected == null) {
                stagedFile.delete()
                _updateState.value = AppUpdateState.Error(
                    "Could not read the published checksum for $assetName — update not applied.",
                    System.currentTimeMillis()
                )
                return
            }

            if (!FileChecksum.matches(stagedFile, expected)) {
                stagedFile.delete()
                _updateState.value = AppUpdateState.Error(
                    "Checksum mismatch for $assetName. The download was discarded and no files " +
                        "were changed.",
                    System.currentTimeMillis()
                )
                return
            }

            _updateState.value = AppUpdateState.UpdateReady(
                latestVersion = version,
                releaseNotes = notes,
                stagedFilePath = stagedFile.absolutePath
            )
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
            // Re-checked at apply time, not just at check time: the app may have
            // been running long enough for the earlier answer to be stale, and
            // this is the step that actually overwrites files.
            val blocker = inPlaceUpdateBlocker()
            if (blocker != null) {
                _updateState.value = AppUpdateState.ManualUpdateRequired(
                    latestVersion = state.latestVersion,
                    releaseNotes = state.releaseNotes,
                    reason = blocker,
                    releaseUrl = "https://github.com/$GITHUB_REPO/releases/latest"
                )
                return
            }

            val currentPid = ProcessHandle.current().pid()
            val runningJarFile = runningArtifact() ?: return
            val appDir = runningJarFile.parentFile ?: File(".")

            // Relaunch through this JVM's own launcher rather than `start
            // <file>.jar`, which depends on a .jar file association that is
            // frequently absent or bound to an archive tool.
            val javaExe = File(
                File(System.getProperty("java.home"), "bin"),
                "javaw.exe"
            ).let { if (it.isFile) it.absolutePath else "javaw" }

            run {
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
copy /y "${runningJarFile.absolutePath}" "${runningJarFile.absolutePath}.prev" >nul
copy /y "${stagedFile.absolutePath}" "${runningJarFile.absolutePath}" >nul
if errorlevel 1 (
    echo Update failed, restoring previous version...
    copy /y "${runningJarFile.absolutePath}.prev" "${runningJarFile.absolutePath}" >nul
)

echo Relaunching Courier...
start "" "$javaExe" -jar "${runningJarFile.absolutePath}"

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