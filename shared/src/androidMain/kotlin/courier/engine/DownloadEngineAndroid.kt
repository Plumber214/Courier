package courier.engine

import android.media.MediaScannerConnection
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import courier.model.DownloadItem
import courier.model.GalleryEntry
import courier.model.MediaType
import courier.model.Platform
import courier.model.VideoFormat
import courier.model.VideoInfo
import courier.platform.AppContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class DownloadEngineAndroid : DownloadEngine {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        @Volatile
        private var isInitialized = false
        @Volatile
        private var initError: Throwable? = null
        private val initLock = Any()

        fun ensureInitialized() {
            if (!isInitialized) {
                synchronized(initLock) {
                    if (!isInitialized && AppContextHolder.isInitialized) {
                        try {
                            YoutubeDL.getInstance().init(AppContextHolder.appContext)
                            FFmpeg.getInstance().init(AppContextHolder.appContext)
                            isInitialized = true
                            initError = null
                            Log.d("Courier", "YoutubeDL and FFmpeg lazy init successful")
                        } catch (e: Exception) {
                            initError = e
                            Log.e("Courier", "Lazy init failed", e)
                            throw e
                        }
                    }
                }
            }
            initError?.let { throw it }
        }
    }

    override suspend fun fetchVideoInfo(url: String, cookieBrowser: String?): Result<VideoInfo> = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()
            val request = YoutubeDLRequest(url)
            request.addOption("--dump-single-json")
            request.addOption("--no-warnings")
            request.addOption("--no-check-certificates")
            request.addOption("--ignore-no-formats-error")
            request.addOption("--add-header", "User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
            request.addOption("--extractor-args", "youtube:player_client=android,web;player_skip=configs,webpage")

            val response = YoutubeDL.getInstance().execute(request)
            val rawJson = response.out?.trim() ?: ""

            if (rawJson.startsWith("{")) {
                Result.success(YtDlpJsonParser.parse(rawJson, url))
            } else {
                // Fallback to getInfo if dump-single-json returned non-JSON
                val ytdlInfo = YoutubeDL.getInstance().getInfo(url)
                val cleanTitle = ytdlInfo.title?.trim()?.ifBlank { null } ?: "${Platform.fromUrl(url).displayName} Video"
                val ext = ytdlInfo.ext?.lowercase() ?: "mp4"
                val isImage = ext in listOf("jpg", "jpeg", "png", "webp", "gif")
                val mediaType = if (isImage) MediaType.IMAGE else MediaType.VIDEO

                val fallbackFormats = mutableListOf<VideoFormat>()
                if (isImage) {
                    fallbackFormats.add(VideoFormat("original", "Original High Resolution Photo", resolution = "Original", ext = ext))
                } else {
                    fallbackFormats.add(VideoFormat("best", "Best Available Quality", resolution = "Highest", ext = "mp4"))
                    fallbackFormats.add(VideoFormat("1080p", "1080p Full HD", resolution = "1080p", ext = "mp4"))
                    fallbackFormats.add(VideoFormat("720p", "720p HD", resolution = "720p", ext = "mp4"))
                    fallbackFormats.add(VideoFormat("480p", "480p SD", resolution = "480p", ext = "mp4"))
                    fallbackFormats.add(VideoFormat("360p", "360p Standard", resolution = "360p", ext = "mp4"))
                    fallbackFormats.add(VideoFormat("bestaudio", "Best Audio Quality (M4A)", ext = "m4a", isAudioOnly = true))
                    fallbackFormats.add(VideoFormat("mp3", "MP3 Audio (Converted 320kbps)", ext = "mp3", isAudioOnly = true))
                }

                val info = VideoInfo(
                    id = ytdlInfo.id ?: "vid_${url.hashCode()}",
                    url = url,
                    title = cleanTitle,
                    uploader = ytdlInfo.uploader,
                    durationSeconds = if (ytdlInfo.duration > 0) ytdlInfo.duration.toLong() else null,
                    thumbnailUrl = ytdlInfo.thumbnail,
                    platform = Platform.fromUrl(url),
                    formats = fallbackFormats,
                    mediaType = mediaType
                )
                Result.success(info)
            }
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

            val isGallery = item.mediaType == MediaType.GALLERY || item.selectedGalleryIndices.isNotEmpty()

            fun newRequest(): YoutubeDLRequest {
                val r = YoutubeDLRequest(item.url)
                if (isGallery) {
                    r.addOption("-o", "${outDir.absolutePath}/%(title).80s_%(playlist_index)s.%(ext)s")
                } else {
                    r.addOption("-o", "${outDir.absolutePath}/%(title).100s.%(ext)s")
                }
                r.addOption("--no-mtime")
                r.addOption("--windows-filenames")
                r.addOption("--no-check-certificates")
                r.addOption("--add-header", "User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                r.addOption("--extractor-args", "youtube:player_client=android,web;player_skip=configs,webpage")
                return r
            }

            fun YoutubeDLRequest.addPhotoArgs() {
                addOption("--ignore-no-formats-error")
                addOption("--write-thumbnail")
                addOption("--skip-download")
            }

            fun YoutubeDLRequest.addVideoArgs() {
                // Shared with desktop via FormatSelector so the two platforms
                // cannot drift apart on codec handling again.
                val formatArg = FormatSelector.videoFormatArg(formatId, item.outputProfile)
                val container = FormatSelector.mergeOutputFormat(
                    item.outputProfile, item.selectedVcodec, item.transcodeCodec
                )
                addOption("-f", formatArg)
                addOption("--merge-output-format", container)

                // The transcode re-encodes audio at 48 kHz itself; normalising
                // during the merge as well would just encode it twice.
                val ppArgs =
                    if (FormatSelector.needsTranscode(item.outputProfile, item.selectedVcodec, item.transcodeCodec)) {
                        FormatSelector.transcodeArgs(item.transcodeCodec)
                    } else {
                        FormatSelector.audioNormalisationArgs(item.outputProfile)
                    }
                var i = 0
                while (i < ppArgs.size - 1) {
                    addOption(ppArgs[i], ppArgs[i + 1])
                    i += 2
                }
            }

            // One pass per kind of media. A mixed Instagram carousel needs two:
            // the photo flags include --skip-download, which would otherwise skip
            // the videos and leave only their thumbnails as stills.
            val requests = mutableListOf<YoutubeDLRequest>()

            if (item.isAudioOnly || item.mediaType == MediaType.AUDIO) {
                requests.add(newRequest().apply {
                    addOption("-f", "bestaudio/best")
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                })
            } else if (item.mediaType == MediaType.IMAGE) {
                requests.add(newRequest().apply { addPhotoArgs() })
            } else if (isGallery) {
                val selected = item.selectedGalleryIndices
                val videoIdx = item.galleryVideoIndices.filter { selected.isEmpty() || it in selected }
                val photoIdx = selected.filter { it !in videoIdx }

                if (photoIdx.isNotEmpty() || selected.isEmpty()) {
                    requests.add(newRequest().apply {
                        addPhotoArgs()
                        if (photoIdx.isNotEmpty()) {
                            addOption("--playlist-items", photoIdx.joinToString(","))
                        }
                    })
                }
                if (videoIdx.isNotEmpty()) {
                    requests.add(newRequest().apply {
                        addVideoArgs()
                        addOption("--playlist-items", videoIdx.joinToString(","))
                    })
                }
            } else {
                requests.add(newRequest().apply { addVideoArgs() })
            }

            val writtenFiles = mutableListOf<String>()
            var lastError: Exception? = null

            for ((passIndex, request) in requests.withIndex()) {
                val passBase = passIndex * 100f / requests.size
                val passSpan = 100f / requests.size
                try {
                    // Reuse item.id as the process id: cancelDownload() destroys by
                    // that id, and the passes run sequentially so it is never
                    // ambiguous which one is live.
                    YoutubeDL.getInstance().execute(request, item.id) { progress, etaInSeconds, line ->
                        val trimmed = line?.trim() ?: ""
                        val scaled = { raw: Float -> passBase + (raw.coerceIn(0f, 100f) * passSpan / 100f) }
                        if (trimmed.contains("Writing video thumbnail") && trimmed.contains(" to: ")) {
                            val extracted = trimmed.substringAfter(" to: ").trim()
                            if (extracted.isNotBlank()) writtenFiles.add(extracted)
                            val expected = when {
                                item.selectedGalleryIndices.isNotEmpty() -> item.selectedGalleryIndices.size
                                (item.galleryCount ?: 0) > 0 -> item.galleryCount ?: 1
                                else -> 1
                            }
                            onProgress(
                                scaled((writtenFiles.size * 100f / expected).coerceIn(0f, 100f)),
                                null, null, null, null
                            )
                        } else if (trimmed.contains("Downloading video thumbnail")) {
                            if (writtenFiles.isEmpty()) onProgress(scaled(50f), null, null, null, null)
                        } else {
                            val etaStr = if (etaInSeconds > 0) "${etaInSeconds}s" else null
                            onProgress(scaled(progress), null, etaStr, null, null)
                        }
                    }
                } catch (e: Exception) {
                    // Partial success still counts: on a mixed carousel one kind
                    // may fail while the other downloads fine.
                    Log.w("Courier", "Gallery pass $passIndex failed", e)
                    lastError = e
                }
            }

            val resolvedPath = writtenFiles.firstOrNull { File(it).isFile }
                ?: findNewestFileInDir(outDir)
            if (resolvedPath == null && lastError != null) {
                return@withContext Result.failure(lastError)
            }
            if (resolvedPath == null || !File(resolvedPath).isFile) {
                return@withContext Result.failure(Exception("Download completed, but output file could not be located."))
            }
            
            // Scan media into Android system gallery / media library
            try {
                if (AppContextHolder.isInitialized) {
                    val filesToScan = if (writtenFiles.isNotEmpty()) writtenFiles.filter { File(it).exists() }.toTypedArray()
                                      else arrayOf(resolvedPath)
                    MediaScannerConnection.scanFile(
                        AppContextHolder.appContext,
                        filesToScan,
                        null,
                        null
                    )
                }
            } catch (scanErr: Exception) {
                Log.w("Courier", "MediaScanner error", scanErr)
            }

            Result.success(resolvedPath)
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
            val cutoff = System.currentTimeMillis() - 180_000
            val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            val recent = files?.filter { it.lastModified() > cutoff }?.maxByOrNull { it.lastModified() }
            if (recent != null) return recent.absolutePath
            files?.maxByOrNull { it.lastModified() }?.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}

actual fun createDownloadEngine(): DownloadEngine = DownloadEngineAndroid()
