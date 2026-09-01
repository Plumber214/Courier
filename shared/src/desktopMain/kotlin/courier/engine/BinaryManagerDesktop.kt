package courier.engine

import courier.platform.getPlatformActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class BinaryManagerDesktop : BinaryManager {
    private val initMutex = Mutex()

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
        initMutex.withLock {
            val ytDlp = getYtDlpExecutable()
            val ffmpeg = getFfmpegExecutable()

            if (ytDlp.exists() && ffmpeg != null && ffmpeg.exists()) {
                _isReady.value = true
                _isDownloading.value = false
                _statusMessage.value = "Engine Ready (${getBinaryVersion()})"
                return@withLock Result.success(Unit)
            }

            _isDownloading.value = true
            _isReady.value = false
            _errorMessage.value = null

            // 1. Download yt-dlp if needed
            if (!ytDlp.exists()) {
                _statusMessage.value = "Downloading yt-dlp engine..."
                _downloadProgress.value = 0f

                val targetFile = File(getBinDirectory(), "yt-dlp.exe")
                val tmpFile = File(getBinDirectory(), "yt-dlp.exe.tmp")
                val downloadUrl = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe"

                try {
                    downloadFileWithProgress(downloadUrl, tmpFile) { prog, downloaded, total ->
                        _downloadProgress.value = prog * 0.4f // 40% of total progress
                        _statusMessage.value = "Downloading yt-dlp: ${(downloaded / (1024 * 1024))} MB / ${(total / (1024 * 1024))} MB"
                    }

                    tmpFile.setExecutable(true)
                    if (targetFile.exists()) targetFile.delete()
                    if (!tmpFile.renameTo(targetFile)) {
                        throw IllegalStateException("Failed to move temp binary to ${targetFile.absolutePath}")
                    }
                } catch (e: Exception) {
                    if (tmpFile.exists()) try { tmpFile.delete() } catch (_: Exception) {}
                    _isDownloading.value = false
                    _isReady.value = false
                    val err = "Failed to download yt-dlp: ${e.message}"
                    _errorMessage.value = err
                    _statusMessage.value = err
                    return@withLock Result.failure(e)
                }
            }

            // 2. Download FFmpeg if needed (enables video+audio muxing and audio extraction)
            val currentFfmpeg = getFfmpegExecutable()
            if (currentFfmpeg == null || !currentFfmpeg.exists()) {
                _statusMessage.value = "Downloading FFmpeg merger components..."
                _downloadProgress.value = 0.4f

                val ffmpegZipTmp = File(getBinDirectory(), "ffmpeg.zip.tmp")
                val ffmpegDownloadUrl = "https://github.com/yt-dlp/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip"

                try {
                    downloadFileWithProgress(ffmpegDownloadUrl, ffmpegZipTmp) { prog, downloaded, total ->
                        _downloadProgress.value = 0.4f + (prog * 0.5f) // 40% -> 90%
                        _statusMessage.value = "Downloading FFmpeg: ${(downloaded / (1024 * 1024))} MB / ${(total / (1024 * 1024))} MB"
                    }

                    _statusMessage.value = "Extracting FFmpeg merger..."
                    extractBinariesFromZip(ffmpegZipTmp, getBinDirectory())
                    try { ffmpegZipTmp.delete() } catch (_: Exception) {}
                } catch (e: Exception) {
                    if (ffmpegZipTmp.exists()) try { ffmpegZipTmp.delete() } catch (_: Exception) {}
                    // Note: If FFmpeg download fails, yt-dlp is still available, but log error
                    println("FFmpeg download warning: ${e.message}")
                }
            }

            _downloadProgress.value = 1.0f
            _isReady.value = true
            _isDownloading.value = false
            _statusMessage.value = "Engine Ready"
            Result.success(Unit)
        }
    }

    private fun downloadFileWithProgress(
        urlStr: String,
        targetFile: File,
        onProgress: (prog: Float, downloaded: Long, total: Long) -> Unit
    ) {
        var url = URL(urlStr)
        var conn = url.openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Courier/1.3")
        conn.connect()

        var redirects = 0
        while (conn.responseCode in 301..308 && redirects < 5) {
            val location = conn.getHeaderField("Location") ?: break
            url = URL(location)
            conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Courier/1.3")
            conn.connect()
            redirects++
        }

        val totalBytes = conn.contentLengthLong.let { if (it > 0) it else 20_000_000L }
        var downloadedBytes = 0L

        conn.inputStream.use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    val prog = (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                    onProgress(prog, downloadedBytes, totalBytes)
                }
            }
        }
    }

    private fun extractBinariesFromZip(zipFile: File, destDir: File) {
        ZipInputStream(FileInputStream(zipFile)).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.replace("\\", "/")
                    val fileName = name.substringAfterLast("/")
                    if (fileName.equals("ffmpeg.exe", ignoreCase = true) || fileName.equals("ffprobe.exe", ignoreCase = true)) {
                        val dest = File(destDir, fileName)
                        val tmp = File(destDir, "$fileName.tmp")
                        FileOutputStream(tmp).use { out ->
                            zipIn.copyTo(out)
                        }
                        tmp.setExecutable(true)
                        if (dest.exists()) dest.delete()
                        tmp.renameTo(dest)
                    }
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
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
