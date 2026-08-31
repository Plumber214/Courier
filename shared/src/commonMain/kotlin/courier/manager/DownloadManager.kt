package courier.manager

import courier.data.DownloadRepository
import courier.data.SettingsRepository
import courier.engine.BinaryManager
import courier.engine.DownloadEngine
import courier.model.DownloadItem
import courier.model.DownloadStatus
import courier.model.Platform
import courier.model.VideoFormat
import courier.model.VideoInfo
import courier.platform.getPlatformActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DownloadManager(
    private val engine: DownloadEngine,
    val repository: DownloadRepository,
    val settingsRepository: SettingsRepository,
    val binaryManager: BinaryManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    val downloads: StateFlow<List<DownloadItem>> = repository.downloads
    val settings = settingsRepository.settings

    private val activeJobs = mutableMapOf<String, Job>()
    private val queueMutex = Mutex()
    private val lastMetricUpdateTimes = mutableMapOf<String, Long>()

    private val _isProcessingQueue = MutableStateFlow(false)

    init {
        // Reset any downloads that were left in DOWNLOADING state upon app restart to FAILED / QUEUED
        val current = repository.downloads.value.map { item ->
            if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.MERGING || item.status == DownloadStatus.FETCHING_INFO) {
                item.copy(status = DownloadStatus.FAILED, errorMessage = "Interrupted by app restart")
            } else {
                item
            }
        }
        repository.saveDownloads(current)
    }

    fun enqueueDownload(
        url: String,
        videoInfo: VideoInfo?,
        format: VideoFormat?,
        isAudioOnly: Boolean,
        destinationDir: String? = null
    ) {
        val platform = videoInfo?.platform ?: Platform.fromUrl(url)
        val title = videoInfo?.title?.ifBlank { null } ?: "${platform.displayName} Video"
        val thumbnail = videoInfo?.thumbnailUrl
        val formatLabel = if (isAudioOnly) "Audio (${format?.ext?.uppercase() ?: "MP3"})" else (format?.displayLabel ?: "Best Quality")

        val id = "dl_${currentEpochMs()}_${randomId()}"
        val item = DownloadItem(
            id = id,
            url = url,
            title = title,
            thumbnailUrl = thumbnail,
            platform = platform,
            progressPercent = 0f,
            status = DownloadStatus.QUEUED,
            isAudioOnly = isAudioOnly,
            formatLabel = formatLabel,
            createdAtEpochMs = currentEpochMs()
        )

        repository.addOrUpdate(item)
        triggerQueueProcessing(format?.formatId, destinationDir)
    }

    fun cancelDownload(id: String) {
        scope.launch {
            activeJobs[id]?.cancel()
            activeJobs.remove(id)
            lastMetricUpdateTimes.remove(id)
            engine.cancelDownload(id)

            val item = repository.downloads.value.find { it.id == id }
            if (item != null && !item.isFinished) {
                repository.addOrUpdate(item.copy(status = DownloadStatus.CANCELLED, errorMessage = "Cancelled by user"))
            }
            triggerQueueProcessing(null, null)
        }
    }

    fun retryDownload(id: String) {
        val item = repository.downloads.value.find { it.id == id } ?: return
        repository.addOrUpdate(
            item.copy(
                status = DownloadStatus.QUEUED,
                progressPercent = 0f,
                errorMessage = null,
                speedFormatted = null,
                etaFormatted = null
            )
        )
        triggerQueueProcessing(null, null)
    }

    fun removeDownload(id: String, deleteDiskFile: Boolean = true) {
        cancelDownload(id)
        val item = repository.downloads.value.find { it.id == id }
        if (deleteDiskFile && !item?.outputPath.isNullOrBlank()) {
            try {
                getPlatformActions().deleteFile(item!!.outputPath!!)
            } catch (e: Exception) {
                println("Error deleting physical file: ${e.message}")
            }
        }
        repository.remove(id)
    }

    fun clearCompleted() {
        repository.clearCompleted()
    }

    fun openDownloadedFile(item: DownloadItem): Boolean {
        val path = item.outputPath ?: return false
        return getPlatformActions().openFile(path)
    }

    fun openDownloadFolder(item: DownloadItem): Boolean {
        val path = item.outputPath ?: settings.value.downloadDirectory
        return getPlatformActions().openFolder(path)
    }

    private fun triggerQueueProcessing(preferredFormatId: String?, destinationDir: String?) {
        scope.launch {
            queueMutex.withLock {
                val maxConcurrent = settings.value.maxConcurrentDownloads.coerceIn(1, 5)
                val currentActiveCount = activeJobs.size

                if (currentActiveCount >= maxConcurrent) return@withLock

                val slotsAvailable = maxConcurrent - currentActiveCount
                val queuedItems = repository.downloads.value
                    .filter { it.status == DownloadStatus.QUEUED }
                    .take(slotsAvailable)

                for (queuedItem in queuedItems) {
                    startDownloadJob(queuedItem, preferredFormatId, destinationDir)
                }
            }
        }
    }

    private fun startDownloadJob(item: DownloadItem, formatId: String?, customOutputDir: String?) {
        val job = scope.launch {
            val outputDir = customOutputDir ?: settings.value.downloadDirectory.ifBlank {
                getPlatformActions().getDefaultDownloadDirectory()
            }
            val cookieBrowser = settings.value.selectedCookieBrowser.let {
                if (it == "None" || it.isBlank()) null else it.lowercase()
            }

            repository.addOrUpdate(
                item.copy(
                    status = DownloadStatus.DOWNLOADING,
                    errorMessage = null
                )
            )

            val result = engine.downloadVideo(
                item = item,
                formatId = formatId,
                outputDir = outputDir,
                cookieBrowser = cookieBrowser,
                onProgress = { progress, speed, eta, downloaded, total ->
                    val now = currentEpochMs()
                    val lastUpdate = lastMetricUpdateTimes[item.id] ?: 0L
                    val shouldUpdateMetrics = (now - lastUpdate >= 600L) || progress >= 99f

                    val updated = repository.downloads.value.find { it.id == item.id }
                    if (updated != null && updated.status == DownloadStatus.DOWNLOADING) {
                        if (shouldUpdateMetrics) {
                            lastMetricUpdateTimes[item.id] = now
                            repository.addOrUpdate(
                                updated.copy(
                                    progressPercent = progress,
                                    speedFormatted = speed,
                                    etaFormatted = eta,
                                    downloadedSizeFormatted = downloaded,
                                    totalSizeFormatted = total
                                )
                            )
                        } else {
                            // Update progress percentage smoothly without churning text
                            repository.addOrUpdate(
                                updated.copy(progressPercent = progress)
                            )
                        }
                    }
                }
            )

            result.fold(
                onSuccess = { filePath ->
                    val updated = repository.downloads.value.find { it.id == item.id }
                    if (updated != null) {
                        repository.addOrUpdate(
                            updated.copy(
                                status = DownloadStatus.COMPLETED,
                                progressPercent = 100f,
                                outputPath = filePath,
                                speedFormatted = null,
                                etaFormatted = null
                            )
                        )
                    }
                },
                onFailure = { error ->
                    val updated = repository.downloads.value.find { it.id == item.id }
                    if (updated != null && updated.status != DownloadStatus.CANCELLED) {
                        repository.addOrUpdate(
                            updated.copy(
                                status = DownloadStatus.FAILED,
                                errorMessage = error.message ?: "Download failed"
                            )
                        )
                    }
                }
            )

            activeJobs.remove(item.id)
            lastMetricUpdateTimes.remove(item.id)
            triggerQueueProcessing(null, null)
        }

        activeJobs[item.id] = job
    }

    private fun randomId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..8).map { chars.random() }.joinToString("")
    }

    private fun currentEpochMs(): Long {
        return kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
    }
}
