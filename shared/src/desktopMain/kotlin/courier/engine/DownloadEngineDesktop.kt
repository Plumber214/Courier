package courier.engine

import courier.model.DownloadItem
import courier.model.Platform
import courier.model.VideoFormat
import courier.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

class DownloadEngineDesktop : DownloadEngine {
    private val runningProcesses = ConcurrentHashMap<String, Process>()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetchVideoInfo(url: String, cookieBrowser: String?): Result<VideoInfo> = withContext(Dispatchers.IO) {
        val ytDlp = BinaryManagerDesktop.getYtDlpExecutable()
        val ffmpeg = BinaryManagerDesktop.getFfmpegExecutable()

        val cmd = mutableListOf(
            ytDlp.absolutePath,
            "--dump-single-json",
            "--no-warnings",
            "--no-playlist",
            "--no-check-certificates",
            "--extractor-args", "youtube:player_client=android,web;player_skip=configs,webpage"
        )

        if (!cookieBrowser.isNullOrBlank() && cookieBrowser != "none") {
            cmd.addAll(listOf("--cookies-from-browser", cookieBrowser))
        }

        if (ffmpeg != null) {
            cmd.addAll(listOf("--ffmpeg-location", ffmpeg.parentFile.absolutePath))
        }

        cmd.add(url)

        try {
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(false)
            val process = pb.start()

            val stdoutReader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            val stderrReader = BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8))

            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val stdoutThread = Thread {
                stdoutReader.forEachLine { stdoutBuilder.appendLine(it) }
            }
            val stderrThread = Thread {
                stderrReader.forEachLine { stderrBuilder.appendLine(it) }
            }

            stdoutThread.start()
            stderrThread.start()

            stdoutThread.join(30_000)
            stderrThread.join(30_000)
            process.waitFor()

            val rawJson = stdoutBuilder.toString().trim()
            if (rawJson.startsWith("{")) {
                val jsonObject = json.parseToJsonElement(rawJson).jsonObject
                val title = jsonObject["title"]?.jsonPrimitive?.contentOrNull ?: "${Platform.fromUrl(url).displayName} Video"
                val id = jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "video_${url.hashCode()}"
                val uploader = jsonObject["uploader"]?.jsonPrimitive?.contentOrNull
                val duration = jsonObject["duration"]?.jsonPrimitive?.longOrNull
                val thumbnail = jsonObject["thumbnail"]?.jsonPrimitive?.contentOrNull

                val formatsList = mutableListOf<VideoFormat>()

                // Standard quality options
                formatsList.add(VideoFormat("best", "Best Available Quality", resolution = "Highest", ext = "mp4"))

                val rawFormats = jsonObject["formats"]?.jsonArray
                if (rawFormats != null) {
                    val seenHeights = mutableSetOf<Int>()
                    for (fmt in rawFormats) {
                        val fmtObj = fmt.jsonObject
                        val height = fmtObj["height"]?.jsonPrimitive?.intOrNull
                        val ext = fmtObj["ext"]?.jsonPrimitive?.contentOrNull ?: "mp4"
                        val vcodec = fmtObj["vcodec"]?.jsonPrimitive?.contentOrNull
                        val fmtId = fmtObj["format_id"]?.jsonPrimitive?.contentOrNull ?: continue
                        val fps = fmtObj["fps"]?.jsonPrimitive?.intOrNull
                        val filesize = fmtObj["filesize"]?.jsonPrimitive?.longOrNull

                        if (height != null && height >= 240 && !seenHeights.contains(height)) {
                            seenHeights.add(height)
                            val label = "${height}p" + (if (height >= 1080) " Full HD" else if (height >= 720) " HD" else " SD")
                            formatsList.add(
                                VideoFormat(
                                    formatId = "${fmtId}+bestaudio/best",
                                    qualityLabel = label,
                                    resolution = "${height}p",
                                    ext = ext,
                                    fileSizeBytes = filesize,
                                    fps = fps,
                                    isAudioOnly = false
                                )
                            )
                        }
                    }
                }

                // Add standard fallback formats if list is small
                if (formatsList.size <= 1) {
                    formatsList.add(VideoFormat("1080p", "1080p Full HD", resolution = "1080p", ext = "mp4"))
                    formatsList.add(VideoFormat("720p", "720p HD", resolution = "720p", ext = "mp4"))
                    formatsList.add(VideoFormat("480p", "480p SD", resolution = "480p", ext = "mp4"))
                    formatsList.add(VideoFormat("360p", "360p Standard", resolution = "360p", ext = "mp4"))
                }

                // Add Audio options
                formatsList.add(VideoFormat("bestaudio", "Best Audio Quality (M4A)", ext = "m4a", isAudioOnly = true))
                formatsList.add(VideoFormat("mp3", "MP3 Audio (Converted 320kbps)", ext = "mp3", isAudioOnly = true))

                val videoInfo = VideoInfo(
                    id = id,
                    url = url,
                    title = title,
                    uploader = uploader,
                    durationSeconds = duration,
                    thumbnailUrl = thumbnail,
                    platform = Platform.fromUrl(url),
                    formats = formatsList
                )
                Result.success(videoInfo)
            } else {
                val error = stderrBuilder.toString().trim().ifBlank { "Could not fetch video info" }
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
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
        val ytDlp = BinaryManagerDesktop.getYtDlpExecutable()
        val ffmpeg = BinaryManagerDesktop.getFfmpegExecutable()

        val outDir = File(outputDir)
        if (!outDir.exists()) {
            outDir.mkdirs()
        }

        val outputTemplate = File(outDir, "%(title).100s.%(ext)s").absolutePath

        val cmd = mutableListOf(
            ytDlp.absolutePath,
            "--newline",
            "--progress",
            "--progress-template", "[download] %(progress._percent_str)s | %(progress._speed_str)s | %(progress._eta_str)s | %(progress._downloaded_bytes_str)s | %(progress._total_bytes_str)s",
            "--no-mtime",
            "--windows-filenames",
            "--no-check-certificates",
            "--merge-output-format", "mp4",
            "--concurrent-fragments", "5",
            "-o", outputTemplate
        )

        if (item.isAudioOnly) {
            cmd.addAll(listOf("-x", "--audio-format", "mp3"))
        } else {
            val formatArg = when (formatId) {
                null, "", "best" -> "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best"
                "1080p" -> "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=1080]+bestaudio/best"
                "720p" -> "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=720]+bestaudio/best"
                "480p" -> "bestvideo[height<=480][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=480]+bestaudio/best"
                "360p" -> "bestvideo[height<=360][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=360]+bestaudio/best"
                else -> formatId
            }
            cmd.addAll(listOf("-f", formatArg))
        }

        if (!cookieBrowser.isNullOrBlank() && cookieBrowser != "none") {
            cmd.addAll(listOf("--cookies-from-browser", cookieBrowser))
        }

        if (ffmpeg != null) {
            cmd.addAll(listOf("--ffmpeg-location", ffmpeg.parentFile.absolutePath))
        }

        cmd.add(item.url)

        try {
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            val process = pb.start()
            runningProcesses[item.id] = process

            var finalFilePath: String? = null
            var lastErrorMessage: String? = null

            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            reader.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("[download]") && trimmed.contains("|")) {
                    parseProgressLine(trimmed, onProgress)
                } else if (trimmed.contains("[Merger] Merging formats into \"")) {
                    val extracted = trimmed.substringAfter("into \"").substringBefore("\"")
                    if (extracted.isNotBlank()) finalFilePath = extracted
                } else if (trimmed.contains("[download] Destination: ")) {
                    val extracted = trimmed.substringAfter("Destination: ").trim()
                    if (extracted.isNotBlank()) finalFilePath = extracted
                } else if (trimmed.contains("ERROR:") || trimmed.contains("error:")) {
                    lastErrorMessage = trimmed
                }
            }

            val exitCode = process.waitFor()
            runningProcesses.remove(item.id)

            if (exitCode == 0) {
                val resolvedPath = finalFilePath ?: findNewestFileInDir(outDir) ?: outDir.absolutePath
                Result.success(resolvedPath)
            } else {
                val err = lastErrorMessage ?: "Download process exited with code $exitCode"
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            runningProcesses.remove(item.id)
            Result.failure(e)
        }
    }

    override fun cancelDownload(id: String) {
        val process = runningProcesses.remove(id)
        try {
            process?.destroyForcibly()
        } catch (e: Exception) {
            println("Error destroying process: ${e.message}")
        }
    }

    override suspend fun updateEngine(): Result<String> {
        return BinaryManagerDesktop().updateBinaries()
    }

    private fun parseProgressLine(
        line: String,
        onProgress: (progress: Float, speed: String?, eta: String?, downloaded: String?, total: String?) -> Unit
    ) {
        try {
            val content = line.substringAfter("[download]").trim()
            val parts = content.split("|").map { it.trim() }
            if (parts.isNotEmpty()) {
                val percentStr = parts[0].replace("%", "").trim()
                val percent = percentStr.toFloatOrNull() ?: 0f
                val speed = parts.getOrNull(1)?.ifBlank { null }
                val eta = parts.getOrNull(2)?.ifBlank { null }
                val downloaded = parts.getOrNull(3)?.ifBlank { null }
                val total = parts.getOrNull(4)?.ifBlank { null }

                onProgress(percent, speed, eta, downloaded, total)
            }
        } catch (e: Exception) {
            // Ignore format parsing hiccups
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

actual fun createDownloadEngine(): DownloadEngine = DownloadEngineDesktop()
