package courier.engine

import android.media.MediaScannerConnection
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import courier.model.DownloadItem
import courier.model.Platform
import courier.model.VideoFormat
import courier.model.VideoInfo
import courier.platform.AppContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DownloadEngineAndroid : DownloadEngine {
    companion object {
        @Volatile
        private var isInitialized = false
        private val initLock = Any()

        fun ensureInitialized() {
            if (!isInitialized) {
                synchronized(initLock) {
                    if (!isInitialized && AppContextHolder.isInitialized) {
                        try {
                            YoutubeDL.getInstance().init(AppContextHolder.appContext)
                            FFmpeg.getInstance().init(AppContextHolder.appContext)
                            isInitialized = true
                            Log.d("Courier", "YoutubeDL and FFmpeg lazy init successful")
                        } catch (e: Exception) {
                            Log.e("Courier", "Lazy init failed", e)
                        }
                    }
                }
            }
        }
    }

    override suspend fun fetchVideoInfo(url: String, cookieBrowser: String?): Result<VideoInfo> = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()
            val ytdlInfo = YoutubeDL.getInstance().getInfo(url)
            val formatsList = mutableListOf<VideoFormat>()

            formatsList.add(VideoFormat("best", "Best Available Quality", resolution = "Highest", ext = "mp4"))
            formatsList.add(VideoFormat("1080p", "1080p Full HD", resolution = "1080p", ext = "mp4"))
            formatsList.add(VideoFormat("720p", "720p HD", resolution = "720p", ext = "mp4"))
            formatsList.add(VideoFormat("480p", "480p SD", resolution = "480p", ext = "mp4"))
            formatsList.add(VideoFormat("360p", "360p Standard", resolution = "360p", ext = "mp4"))
            formatsList.add(VideoFormat("bestaudio", "Best Audio Quality (M4A)", ext = "m4a", isAudioOnly = true))
            formatsList.add(VideoFormat("mp3", "MP3 Audio (Converted 320kbps)", ext = "mp3", isAudioOnly = true))

            val cleanTitle = ytdlInfo.title?.trim()?.ifBlank { null } ?: "${Platform.fromUrl(url).displayName} Video"

            val info = VideoInfo(
                id = ytdlInfo.id ?: "vid_${url.hashCode()}",
                url = url,
                title = cleanTitle,
                uploader = ytdlInfo.uploader,
                durationSeconds = if (ytdlInfo.duration > 0) ytdlInfo.duration.toLong() else null,
                thumbnailUrl = ytdlInfo.thumbnail,
                platform = Platform.fromUrl(url),
                formats = formatsList
            )
            Result.success(info)
        } catch (e: Exception) {
            Log.e("Courier", "fetchVideoInfo failed", e)
            Result.failure(e)
        }
    }

    override suspend fun downloadVideo(
        item: DownloadItem,
        formatId: String?,
        outputDir: String,
        cookieBrowser: String?,
        onProgress: (progress: Float, speed: String?, eta: String?, downloaded: String?, total: String?) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()
            val outDir = File(outputDir)
            if (!outDir.exists()) {
                outDir.mkdirs()
            }

            val request = YoutubeDLRequest(item.url)
            request.addOption("-o", "${outDir.absolutePath}/%(title).100s.%(ext)s")
            request.addOption("--no-mtime")
            request.addOption("--windows-filenames")
            request.addOption("--no-check-certificates")
            request.addOption("--merge-output-format", "mp4")
            request.addOption("--add-header", "User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
            request.addOption("--extractor-args", "youtube:player_client=android,web;player_skip=configs,webpage")

            if (item.isAudioOnly) {
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
            } else {
                val formatArg = when (formatId) {
                    null, "", "best" -> "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best"
                    "1080p" -> "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=1080]+bestaudio/best"
                    "720p" -> "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=720]+bestaudio/best"
                    "480p" -> "bestvideo[height<=480][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=480]+bestaudio/best"
                    "360p" -> "bestvideo[height<=360][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=360]+bestaudio/best"
                    else -> if (formatId.contains("+") || formatId.contains("/")) formatId else "$formatId+bestaudio/best"
                }
                request.addOption("-f", formatArg)
            }

            val response = YoutubeDL.getInstance().execute(request, item.id) { progress, etaInSeconds, line ->
                val etaStr = if (etaInSeconds > 0) "${etaInSeconds}s" else null
                onProgress(progress, null, etaStr, null, null)
            }

            val newest = findNewestFileInDir(outDir) ?: outDir.absolutePath
            
            // Scan media into Android system gallery / media library
            try {
                if (AppContextHolder.isInitialized && File(newest).exists()) {
                    MediaScannerConnection.scanFile(
                        AppContextHolder.appContext,
                        arrayOf(newest),
                        null,
                        null
                    )
                }
            } catch (scanErr: Exception) {
                Log.w("Courier", "MediaScanner error", scanErr)
            }

            Result.success(newest)
        } catch (e: Exception) {
            Log.e("Courier", "downloadVideo failed", e)
            Result.failure(e)
        }
    }

    override fun cancelDownload(id: String) {
        try {
            YoutubeDL.getInstance().destroyProcessById(id)
        } catch (e: Exception) {
            Log.e("Courier", "Error canceling download", e)
        }
    }

    override suspend fun updateEngine(): Result<String> = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()
            val status = YoutubeDL.getInstance().updateYoutubeDL(AppContextHolder.appContext)
            Result.success("Updated engine: $status")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun findNewestFileInDir(dir: File): String? {
        return try {
            dir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.maxByOrNull { it.lastModified() }
                ?.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}

actual fun createDownloadEngine(): DownloadEngine = DownloadEngineAndroid()
