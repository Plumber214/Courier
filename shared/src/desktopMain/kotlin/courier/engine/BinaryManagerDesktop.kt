package courier.engine

import courier.platform.getPlatformActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class BinaryManagerDesktop : BinaryManager {
    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    override val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    override val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _statusMessage = MutableStateFlow("Initializing...")
    override val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    companion object {
        fun getBinDirectory(): File {
            val appStorage = getPlatformActions().getAppStorageDirectory()
            val binDir = File(appStorage, "bin")
            if (!binDir.exists()) {
                binDir.mkdirs()
            }
            return binDir
        }

        fun getYtDlpExecutable(): File {
            val local = File(getBinDirectory(), "yt-dlp.exe")
            if (local.exists()) return local

            // Check if yt-dlp is in PATH
            val systemPath = System.getenv("PATH") ?: ""
            for (p in systemPath.split(File.pathSeparator)) {
                val exe = File(p, "yt-dlp.exe")
                if (exe.exists()) return exe
                val bin = File(p, "yt-dlp")
                if (bin.exists()) return bin
            }
            return local
        }

        fun getFfmpegExecutable(): File? {
            val local = File(getBinDirectory(), "ffmpeg.exe")
            if (local.exists()) return local

            val systemPath = System.getenv("PATH") ?: ""
            for (p in systemPath.split(File.pathSeparator)) {
                val exe = File(p, "ffmpeg.exe")
                if (exe.exists()) return exe
                val bin = File(p, "ffmpeg")
                if (bin.exists()) return bin
            }
            return null
        }
    }

    override suspend fun ensureBinariesReady(): Result<Unit> = withContext(Dispatchers.IO) {
        val ytDlp = getYtDlpExecutable()
        if (ytDlp.exists()) {
            _isReady.value = true
            _isDownloading.value = false
            _statusMessage.value = "Engine Ready (${getBinaryVersion()})"
            return@withContext Result.success(Unit)
        }

        // Download yt-dlp.exe
        _isDownloading.value = true
        _isReady.value = false
        _errorMessage.value = null
        _statusMessage.value = "Downloading yt-dlp engine..."
        _downloadProgress.value = 0f

        val targetFile = File(getBinDirectory(), "yt-dlp.exe")
        val downloadUrl = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe"

        try {
            var url = URL(downloadUrl)
            var conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Courier/1.0")
            conn.connect()

            // Follow redirect manually if needed
            var redirects = 0
            while (conn.responseCode in 301..308 && redirects < 5) {
                val location = conn.getHeaderField("Location") ?: break
                url = URL(location)
                conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "Courier/1.0")
                conn.connect()
                redirects++
            }

            val totalBytes = conn.contentLengthLong.let { if (it > 0) it else 18_000_000L }
            var downloadedBytes = 0L

            conn.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        val prog = (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                        _downloadProgress.value = prog
                        _statusMessage.value = "Downloading yt-dlp: ${(downloadedBytes / (1024 * 1024))} MB / ${(totalBytes / (1024 * 1024))} MB"
                    }
                }
            }

            targetFile.setExecutable(true)
            _isReady.value = true
            _isDownloading.value = false
            _statusMessage.value = "Engine Ready"
            Result.success(Unit)
        } catch (e: Exception) {
            _isDownloading.value = false
            _isReady.value = false
            val err = "Failed to download yt-dlp: ${e.message}"
            _errorMessage.value = err
            _statusMessage.value = err
            Result.failure(e)
        }
    }

    override suspend fun updateBinaries(): Result<String> = withContext(Dispatchers.IO) {
        val ytDlp = getYtDlpExecutable()
        if (!ytDlp.exists()) {
            return@withContext ensureBinariesReady().map { "Downloaded latest yt-dlp" }
        }

        try {
            val pb = ProcessBuilder(ytDlp.absolutePath, "-U")
            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            Result.success(output.trim().ifBlank { "yt-dlp is up to date." })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getBinaryVersion(): String {
        return try {
            val ytDlp = getYtDlpExecutable()
            if (!ytDlp.exists()) return "Not installed"
            val pb = ProcessBuilder(ytDlp.absolutePath, "--version")
            val process = pb.start()
            val version = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            version.ifBlank { "Available" }
        } catch (e: Exception) {
            "Ready"
        }
    }
}

actual fun createBinaryManager(): BinaryManager = BinaryManagerDesktop()
