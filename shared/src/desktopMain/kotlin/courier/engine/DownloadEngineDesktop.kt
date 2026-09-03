package courier.engine

import courier.model.DownloadItem
import courier.model.GalleryEntry
import courier.model.MediaType
import courier.model.Platform
import courier.model.VideoFormat
import courier.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

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
            "--ignore-no-formats-error",
            "--extractor-args", "youtube:player_client=android,web;player_skip=configs,webpage"
        )

        if (!cookieBrowser.isNullOrBlank() && cookieBrowser != "none") {
            cmd.addAll(listOf("--cookies-from-browser", cookieBrowser))
        }

        if (ffmpeg != null) {
            cmd.addAll(listOf("--ffmpeg-location", ffmpeg.parentFile.absolutePath))
        }

        cmd.add(url)

        var process: Process? = null
        try {
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(false)
            val proc = pb.start()
            process = proc

            val stdoutReader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
            val stderrReader = BufferedReader(InputStreamReader(proc.errorStream, Charsets.UTF_8))

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

            val exited = proc.waitFor(30, TimeUnit.SECONDS)
            if (!exited) {
                proc.destroyForcibly()
                return@withContext Result.failure(Exception("yt-dlp fetchVideoInfo timed out after 30 seconds"))
            }

            val rawJson = stdoutBuilder.toString().trim()
            if (rawJson.startsWith("{")) {
                Result.success(YtDlpJsonParser.parse(rawJson, url))
            } else {
                val error = stderrBuilder.toString().trim().ifBlank { "Could not fetch media info" }
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                if (process?.isAlive == true) {
                    process.destroyForcibly()
                }
            } catch (_: Exception) {}
        }
    }

    override suspend fun downloadVideo(
        item: DownloadItem,
        formatId: String?,
        outputDir: String,
        cookieBrowser: String?,
        onProgress: (progress: Float, speed: String?, eta: String?, downloaded: String?, total: String?) -> Unit
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val ytDlp = BinaryManagerDesktop.getYtDlpExecutable()
        val ffmpeg = BinaryManagerDesktop.getFfmpegExecutable()

        val outDir = File(outputDir)
        if (!outDir.exists()) {
            outDir.mkdirs()
        }

        val isGallery = item.mediaType == MediaType.GALLERY || item.selectedGalleryIndices.isNotEmpty()
        val outputTemplate = if (isGallery) {
            File(outDir, "%(title).80s_%(playlist_index)s.%(ext)s").absolutePath
        } else {
            File(outDir, "%(title).100s.%(ext)s").absolutePath
        }

        val progressTemplate = """download:{"progress":"%(progress._percent_str)s","speed":"%(progress._speed_str)s","eta":"%(progress._eta_str)s","downloaded":"%(progress._downloaded_bytes_str)s","total":"%(progress._total_bytes_str)s"}"""

        fun baseCommand() = mutableListOf(
            ytDlp.absolutePath,
            "--newline",
            "--progress",
            "--progress-template", progressTemplate,
            "--no-mtime",
            "--windows-filenames",
            "--concurrent-fragments", "5",
            "-o", outputTemplate
        )

        /** yt-dlp flags that fetch a post's image rather than a video stream. */
        fun MutableList<String>.addPhotoArgs() = addAll(
            listOf("--ignore-no-formats-error", "--write-thumbnail", "--skip-download")
        )

        /** Codec-constrained video selection, plus a transcode pass when required. */
        fun MutableList<String>.addVideoArgs() {
            val formatArg = FormatSelector.videoFormatArg(formatId, item.outputProfile)
            val container = FormatSelector.mergeOutputFormat(
                item.outputProfile, item.selectedVcodec, item.transcodeCodec
            )
            addAll(listOf("-f", formatArg, "--merge-output-format", container))
            if (FormatSelector.needsTranscode(item.outputProfile, item.selectedVcodec, item.transcodeCodec)) {
                // The transcode re-encodes audio at 48 kHz itself; normalising
                // during the merge as well would just encode it twice.
                addAll(FormatSelector.transcodeArgs(item.transcodeCodec))
            } else {
                addAll(FormatSelector.audioNormalisationArgs(item.outputProfile))
            }
        }

        fun MutableList<String>.finish(): List<String> {
            if (!cookieBrowser.isNullOrBlank() && cookieBrowser != "none") {
                addAll(listOf("--cookies-from-browser", cookieBrowser))
            }
            if (ffmpeg != null) {
                addAll(listOf("--ffmpeg-location", ffmpeg.parentFile.absolutePath))
            }
            add(item.url)
            return this
        }

        // Build one command per kind of media in this request.
        //
        // A mixed Instagram carousel needs two yt-dlp passes: the photo flags
        // include --skip-download, which would otherwise skip the videos and
        // leave only their thumbnails as stills.
        val passes = mutableListOf<List<String>>()

        if (item.isAudioOnly || item.mediaType == MediaType.AUDIO) {
            passes.add(baseCommand().apply {
                addAll(listOf("-f", "bestaudio/best", "-x", "--audio-format", "mp3"))
            }.finish())
        } else if (item.mediaType == MediaType.IMAGE) {
            passes.add(baseCommand().apply { addPhotoArgs() }.finish())
        } else if (isGallery) {
            val selected = item.selectedGalleryIndices
            val videoIdx = item.galleryVideoIndices.filter { selected.isEmpty() || it in selected }
            val photoIdx = selected.filter { it !in videoIdx }

            if (photoIdx.isNotEmpty() || selected.isEmpty()) {
                passes.add(baseCommand().apply {
                    addPhotoArgs()
                    if (photoIdx.isNotEmpty()) {
                        addAll(listOf("--playlist-items", photoIdx.joinToString(",")))
                    }
                }.finish())
            }
            if (videoIdx.isNotEmpty()) {
                passes.add(baseCommand().apply {
                    addVideoArgs()
                    addAll(listOf("--playlist-items", videoIdx.joinToString(",")))
                }.finish())
            }
        } else {
            passes.add(baseCommand().apply { addVideoArgs() }.finish())
        }

        val allWritten = mutableListOf<String>()
        var lastFailure: Exception? = null

        for ((passIndex, cmd) in passes.withIndex()) {
            val result = runPass(
                cmd = cmd,
                item = item,
                outDir = outDir,
                passIndex = passIndex,
                passCount = passes.size,
                onProgress = onProgress
            )
            result.fold(
                onSuccess = { allWritten.addAll(it) },
                onFailure = { lastFailure = it as? Exception ?: Exception(it.message) }
            )
        }

        // Partial success still counts: on a mixed carousel one kind may fail
        // (a login-walled video, say) while the other downloads fine. Only
        // report failure when nothing at all landed.
        return@withContext if (allWritten.isNotEmpty()) {
            Result.success(allWritten)
        } else {
            Result.failure(lastFailure ?: Exception("Download produced no files."))
        }
    }

    /**
     * Runs one yt-dlp invocation and returns every output file it produced.
     *
     * [passIndex] and [passCount] scale reported progress so a two-pass mixed
     * carousel advances 0-50% then 50-100% instead of resetting halfway.
     */
    private suspend fun runPass(
        cmd: List<String>,
        item: DownloadItem,
        outDir: File,
        passIndex: Int,
        passCount: Int,
        onProgress: (progress: Float, speed: String?, eta: String?, downloaded: String?, total: String?) -> Unit
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val passBase = passIndex * 100f / passCount
        val passSpan = 100f / passCount
        val scaled: (Float) -> Float = { raw -> passBase + (raw.coerceIn(0f, 100f) * passSpan / 100f) }
        val report: (Float, String?, String?, String?, String?) -> Unit = { p, s, e, d, t ->
            onProgress(scaled(p), s, e, d, t)
        }

        try {
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            val process = pb.start()
            runningProcesses[item.id] = process

            coroutineContext[Job]?.invokeOnCompletion {
                process.destroyForcibly()
                runningProcesses.remove(item.id)
            }

            var finalFilePath: String? = null
            var lastErrorMessage: String? = null
            val writtenFiles = mutableListOf<String>()

            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            reader.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("download:{")) {
                    parseJsonProgress(trimmed.removePrefix("download:"), report)
                } else if (trimmed.startsWith("[download]") && trimmed.contains("|")) {
                    parseProgressLine(trimmed, report)
                } else if (trimmed.contains("Writing video thumbnail") && trimmed.contains(" to: ")) {
                    val extracted = trimmed.substringAfter(" to: ").trim()
                    if (extracted.isNotBlank()) {
                        writtenFiles.add(extracted)
                        finalFilePath = extracted
                    }
                    // --skip-download emits no [download] progress lines at all,
                    // so progress here is synthesised from files written.
                    val expected = when {
                        item.selectedGalleryIndices.isNotEmpty() -> item.selectedGalleryIndices.size
                        (item.galleryCount ?: 0) > 0 -> item.galleryCount ?: 1
                        else -> 1
                    }
                    report((writtenFiles.size * 100f / expected).coerceIn(0f, 100f), null, null, null, null)
                } else if (trimmed.contains("Downloading video thumbnail")) {
                    if (writtenFiles.isEmpty()) {
                        report(50f, null, null, null, null)
                    }
                } else if (trimmed.contains("[Merger] Merging formats into \"")) {
                    val extracted = trimmed.substringAfter("into \"").substringBefore("\"")
                    if (extracted.isNotBlank()) {
                        finalFilePath = extracted
                        writtenFiles.add(extracted)
                    }
                } else if (trimmed.contains("[VideoConvertor] Converting video from") &&
                    trimmed.contains("Destination: ")
                ) {
                    // The transcode renames the output (e.g. .mkv -> .mp4), so this
                    // must override the earlier [Merger] path or we would report a
                    // file that no longer exists.
                    val extracted = trimmed.substringAfter("Destination: ").trim()
                    if (extracted.isNotBlank()) {
                        writtenFiles.remove(finalFilePath)
                        finalFilePath = extracted
                        writtenFiles.add(extracted)
                    }
                    report(99f, null, null, null, null)
                } else if (trimmed.contains("[ExtractAudio] Destination: ")) {
                    val extracted = trimmed.substringAfter("Destination: ").trim()
                    if (extracted.isNotBlank()) {
                        finalFilePath = extracted
                        writtenFiles.add(extracted)
                    }
                } else if (trimmed.contains("[download] Destination: ")) {
                    val extracted = trimmed.substringAfter("Destination: ").trim()
                    if (extracted.isNotBlank()) finalFilePath = extracted
                } else if (trimmed.contains("[download]") && trimmed.contains("has already been downloaded")) {
                    val extracted = trimmed.substringAfter("[download]").substringBefore("has already been downloaded").trim()
                    if (extracted.isNotBlank()) finalFilePath = extracted
                } else if (trimmed.contains("ERROR:") || trimmed.contains("error:")) {
                    lastErrorMessage = trimmed
                }
            }

            val exitCode = process.waitFor()
            runningProcesses.remove(item.id)

            if (exitCode == 0) {
                val existing = writtenFiles.filter { File(it).isFile }.distinct()
                if (existing.isNotEmpty()) return@withContext Result.success(existing)

                val fallback = finalFilePath?.takeIf { File(it).isFile }
                    ?: findNewestFileInDir(outDir)
                if (fallback != null && File(fallback).isFile) {
                    Result.success(listOf(fallback))
                } else {
                    Result.failure(
                        Exception(lastErrorMessage ?: "Download completed, but output file could not be located.")
                    )
                }
            } else {
                Result.failure(
                    Exception(lastErrorMessage ?: "Download process exited with code $exitCode")
                )
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

    private fun parseJsonProgress(
        jsonStr: String,
        onProgress: (progress: Float, speed: String?, eta: String?, downloaded: String?, total: String?) -> Unit
    ) {
        try {
            val jsonElement = json.parseToJsonElement(jsonStr).jsonObject
            val percentRaw = jsonElement["progress"]?.jsonPrimitive?.contentOrNull?.replace("%", "")?.trim()
            val percent = percentRaw?.toFloatOrNull() ?: 0f
            val speed = jsonElement["speed"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
            val eta = jsonElement["eta"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
            val downloaded = jsonElement["downloaded"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
            val total = jsonElement["total"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }

            onProgress(percent, speed, eta, downloaded, total)
        } catch (_: Exception) {}
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
        } catch (_: Exception) {}
    }

    private fun findNewestFileInDir(dir: File): String? {
        return try {
            val cutoff = System.currentTimeMillis() - 180_000
            val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            val recent = files?.filter { it.lastModified() > cutoff }?.maxByOrNull { it.lastModified() }
            if (recent != null) return recent.absolutePath
            files?.maxByOrNull { it.lastModified() }?.absolutePath
        } catch (_: Exception) {
            null
        }
    }
}

actual fun createDownloadEngine(): DownloadEngine = DownloadEngineDesktop()
