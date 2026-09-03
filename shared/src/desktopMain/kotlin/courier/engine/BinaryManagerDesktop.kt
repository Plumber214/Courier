package courier.engine

import courier.platform.getPlatformActions
import courier.security.FileChecksum
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
import java.net.URI
import java.util.concurrent.TimeUnit
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

    private val _isMergerAvailable = MutableStateFlow(false)
    override val isMergerAvailable: StateFlow<Boolean> = _isMergerAvailable.asStateFlow()

    companion object {
        // ------------------------------------------------------------------
        // yt-dlp
        //
        // Tracked at `latest` deliberately: extractors break as sites change,
        // and a pinned yt-dlp stops working within weeks. Each release publishes
        // SHA2-256SUMS beside the binary, which is fetched from the same release
        // and checked before the download is installed.
        //
        // That defends against a truncated or corrupted transfer, not against a
        // compromised release — an attacker who can rewrite one asset can
        // rewrite both. Pinning is not an option here; verification is what is
        // available, and it is worth more than nothing.
        // ------------------------------------------------------------------
        private const val YTDLP_EXE = "yt-dlp.exe"
        private const val YTDLP_URL =
            "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe"
        private const val YTDLP_SUMS_URL =
            "https://github.com/yt-dlp/yt-dlp/releases/latest/download/SHA2-256SUMS"

        // ------------------------------------------------------------------
        // FFmpeg
        //
        // Pinned to an immutable dated release, with the expected hash recorded
        // here in the repository. FFmpeg does not need to track upstream the way
        // yt-dlp does, so it gets the stronger guarantee: this hash was read
        // from the release's own checksums.sha256 and committed, meaning a
        // rewritten release is caught too, not just a corrupted download.
        //
        // Previously this used releases/download/latest/, a rolling tag whose
        // contents change under a fixed URL.
        //
        // To bump: pick a newer autobuild tag, take the win64-gpl (non-shared)
        // line from that release's checksums.sha256, and update all three
        // constants together.
        // ------------------------------------------------------------------
        private const val FFMPEG_TAG = "autobuild-2026-09-02-17-51"
        private const val FFMPEG_ASSET = "ffmpeg-N-126390-g9fc8c785e2-win64-gpl.zip"
        private const val FFMPEG_SHA256 =
            "ee698ac088ce89b3e18ecdef48e71748af5dc8e69d93bcbdcb58e9931ac4d3f3"
        private const val FFMPEG_URL =
            "https://github.com/yt-dlp/FFmpeg-Builds/releases/download/$FFMPEG_TAG/$FFMPEG_ASSET"

        fun getBinDirectory(): File {
            val appStorage = getPlatformActions().getAppStorageDirectory()
            val binDir = File(appStorage, "bin")
            if (!binDir.exists()) {
                binDir.mkdirs()
            }
            return binDir
        }

        /**
         * The managed yt-dlp, whether or not it exists yet.
         *
         * Deliberately does not search `PATH`. It used to, which meant any
         * writable directory on the user's path could substitute the binary
         * Courier executes — and made the version reported in Settings not
         * necessarily the version that ran.
         */
        fun getYtDlpExecutable(): File = File(getBinDirectory(), YTDLP_EXE)

        /** The managed FFmpeg, or null if it has not been installed. */
        fun getFfmpegExecutable(): File? =
            File(getBinDirectory(), "ffmpeg.exe").takeIf { it.isFile }
    }

    override suspend fun ensureBinariesReady(): Result<Unit> = withContext(Dispatchers.IO) {
        initMutex.withLock {
            val ytDlp = getYtDlpExecutable()
            val ffmpeg = getFfmpegExecutable()

            if (ytDlp.isFile && ffmpeg != null) {
                _isReady.value = true
                _isMergerAvailable.value = true
                _isDownloading.value = false
                _errorMessage.value = null
                _statusMessage.value = "Engine Ready (${getBinaryVersion()})"
                return@withLock Result.success(Unit)
            }

            _isDownloading.value = true
            _isReady.value = false
            _errorMessage.value = null

            if (!ytDlp.isFile) {
                _statusMessage.value = "Downloading yt-dlp engine..."
                _downloadProgress.value = 0f

                val tmpFile = File(getBinDirectory(), "$YTDLP_EXE.tmp")
                try {
                    downloadFileWithProgress(YTDLP_URL, tmpFile) { prog, downloaded, total ->
                        _downloadProgress.value = prog * 0.4f
                        _statusMessage.value = "Downloading yt-dlp: ${mb(downloaded)} / ${mb(total)}"
                    }

                    _statusMessage.value = "Verifying yt-dlp..."
                    verifyAgainstPublishedSums(tmpFile, YTDLP_SUMS_URL, YTDLP_EXE)

                    tmpFile.setExecutable(true)
                    if (ytDlp.exists()) ytDlp.delete()
                    if (!tmpFile.renameTo(ytDlp)) {
                        throw IllegalStateException("Failed to move verified binary to ${ytDlp.absolutePath}")
                    }
                } catch (e: Exception) {
                    tmpFile.delete()
                    _isDownloading.value = false
                    _isReady.value = false
                    val err = "Failed to install yt-dlp: ${e.message}"
                    _errorMessage.value = err
                    _statusMessage.value = err
                    return@withLock Result.failure(e)
                }
            }

            // yt-dlp is present, so the engine can run. FFmpeg is a separate
            // question and a separate failure.
            _isReady.value = true

            if (getFfmpegExecutable() == null) {
                _statusMessage.value = "Downloading FFmpeg merger components..."
                _downloadProgress.value = 0.4f

                val zipTmp = File(getBinDirectory(), "ffmpeg.zip.tmp")
                try {
                    downloadFileWithProgress(FFMPEG_URL, zipTmp) { prog, downloaded, total ->
                        _downloadProgress.value = 0.4f + (prog * 0.5f)
                        _statusMessage.value = "Downloading FFmpeg: ${mb(downloaded)} / ${mb(total)}"
                    }

                    _statusMessage.value = "Verifying FFmpeg..."
                    if (!FileChecksum.matches(zipTmp, FFMPEG_SHA256)) {
                        throw IllegalStateException(
                            "checksum mismatch for $FFMPEG_ASSET — the download does not match " +
                                "the hash recorded for $FFMPEG_TAG"
                        )
                    }

                    _statusMessage.value = "Extracting FFmpeg merger..."
                    extractBinariesFromZip(zipTmp, getBinDirectory())
                    zipTmp.delete()

                    if (getFfmpegExecutable() == null) {
                        throw IllegalStateException("archive contained no ffmpeg.exe")
                    }
                } catch (e: Exception) {
                    zipTmp.delete()
                    _isDownloading.value = false
                    _isMergerAvailable.value = false
                    _downloadProgress.value = 1.0f
                    _errorMessage.value =
                        "FFmpeg could not be installed (${e.message}). Downloads that need " +
                            "merging or audio extraction will fail. Retry from Settings."
                    _statusMessage.value = "Engine Ready — merger unavailable"
                    // Not a failed result: yt-dlp works, and progressive
                    // downloads will succeed. The degraded state is reported
                    // rather than pretended away, which is the point.
                    return@withLock Result.success(Unit)
                }
            }

            _downloadProgress.value = 1.0f
            _isMergerAvailable.value = true
            _isDownloading.value = false
            _errorMessage.value = null
            _statusMessage.value = "Engine Ready"
            Result.success(Unit)
        }
    }

    private fun mb(bytes: Long): String = "${bytes / (1024 * 1024)} MB"

    /**
     * Downloads [sumsUrl], finds [fileName]'s line, and throws unless [file]
     * matches it.
     *
     * A sums file that cannot be fetched or does not list the file is a
     * failure, not a pass. "Could not check" must never install.
     */
    private fun verifyAgainstPublishedSums(file: File, sumsUrl: String, fileName: String) {
        val sumsText = try {
            openWithRedirects(sumsUrl).inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            throw IllegalStateException("could not fetch published checksums: ${e.message}")
        }

        val expected = FileChecksum.findInSumsFile(sumsText, fileName)
            ?: throw IllegalStateException("published checksums did not list $fileName")

        if (!FileChecksum.matches(file, expected)) {
            throw IllegalStateException("checksum mismatch for $fileName")
        }
    }

    /** Follows redirects manually so every hop keeps the User-Agent header. */
    private fun openWithRedirects(urlStr: String): HttpURLConnection {
        var current = urlStr
        var redirects = 0
        while (true) {
            val conn = (URI.create(current).toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "Courier/${courier.util.AppVersion.VERSION_NAME}")
            }
            val code = conn.responseCode
            if (code in 301..308 && redirects < 5) {
                val location = conn.getHeaderField("Location")
                if (location != null) {
                    current = location
                    redirects++
                    continue
                }
            }
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code for $current")
            }
            return conn
        }
    }

    private fun downloadFileWithProgress(
        urlStr: String,
        targetFile: File,
        onProgress: (prog: Float, downloaded: Long, total: Long) -> Unit
    ) {
        val conn = openWithRedirects(urlStr)
        val totalBytes = conn.contentLengthLong
        var downloadedBytes = 0L

        conn.inputStream.use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    downloadedBytes += read
                    // Only report a fraction when the length is actually known.
                    // The old fallback invented a 20 MB total, so a 170 MB
                    // archive reported 100% about eight times.
                    val prog = if (totalBytes > 0) {
                        (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    onProgress(prog, downloadedBytes, totalBytes.coerceAtLeast(0L))
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
                    if (fileName.equals("ffmpeg.exe", ignoreCase = true) ||
                        fileName.equals("ffprobe.exe", ignoreCase = true)
                    ) {
                        // Only ever written by basename into destDir, so a
                        // crafted entry path cannot escape the directory.
                        val dest = File(destDir, fileName)
                        val tmp = File(destDir, "$fileName.tmp")
                        FileOutputStream(tmp).use { out -> zipIn.copyTo(out) }
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
        if (!ytDlp.isFile) {
            return@withContext ensureBinariesReady().map { "Installed yt-dlp" }
        }

        try {
            val pb = ProcessBuilder(ytDlp.absolutePath, "-U")
            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            // Bounded: a self-update that hangs would otherwise hold this
            // coroutine, and the Settings button with it, indefinitely.
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@withContext Result.failure(Exception("yt-dlp self-update timed out"))
            }
            Result.success(output.trim().ifBlank { "yt-dlp is up to date." })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getBinaryVersion(): String {
        return try {
            val ytDlp = getYtDlpExecutable()
            if (!ytDlp.isFile) return "Not installed"
            val pb = ProcessBuilder(ytDlp.absolutePath, "--version")
            val process = pb.start()
            val version = process.inputStream.bufferedReader().readText().trim()
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return "Unknown"
            }
            version.ifBlank { "Available" }
        } catch (e: Exception) {
            "Unknown"
        }
    }
}

actual fun createBinaryManager(): BinaryManager = BinaryManagerDesktop()
