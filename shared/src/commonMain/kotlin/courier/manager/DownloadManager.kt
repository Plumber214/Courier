package courier.manager

import courier.data.DownloadRepository
import courier.data.SettingsRepository
import courier.engine.BinaryManager
import courier.engine.DownloadEngine
import courier.model.DownloadItem
import courier.model.DownloadStatus
import courier.model.MediaType
import courier.model.Platform
import courier.model.VideoFormat
import courier.model.VideoInfo
import courier.platform.getPlatformActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
    val downloads: StateFlow<List<DownloadItem>> = combine(
        repository.downloads,
        repository.progressMap
    ) { downloadList, progressMap ->
        downloadList.map { item ->
            val progress = progressMap[item.id]
            if (progress != null && item.status == DownloadStatus.DOWNLOADING) {
                item.copy(
                    progressPercent = progress.progressPercent,
                    speedFormatted = progress.speedFormatted ?: item.speedFormatted,
                    etaFormatted = progress.etaFormatted ?: item.etaFormatted,
                    downloadedSizeFormatted = progress.downloadedSizeFormatted ?: item.downloadedSizeFormatted,
                    totalSizeFormatted = progress.totalSizeFormatted ?: item.totalSizeFormatted
                )
            } else {
                item
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, repository.downloads.value)

    val settings = settingsRepository.settings

    private val activeJobs = mutableMapOf<String, Job>()
    private val stateMutex = Mutex()
    private val lastMetricUpdateTimes = mutableMapOf<String, Long>()

    private val _isProcessingQueue = MutableStateFlow(false)

    init {
        // Evaluate downloads left in active state upon app restart
        val current = repository.downloads.value.map { item ->
            if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.MERGING || item.status == DownloadStatus.FETCHING_INFO) {
                val canResume = item.resumeAttempts < 3 && item.mediaType != MediaType.GALLERY && item.mediaType != MediaType.IMAGE
                if (canResume) {
                    val attempt = item.resumeAttempts + 1
                    item.copy(
                        status = DownloadStatus.QUEUED,
                        resumeAttempts = attempt,
                        errorMessage = "Resuming interrupted download (attempt $attempt/3)"
                    )
                } else {
                    item.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = if (item.resumeAttempts >= 3) "Max resume attempts exceeded (3/3)" else "Interrupted by app restart"
                    )
                }
            } else {
                item
            }
        }
        repository.saveDownloads(current)
        if (current.any { it.status == DownloadStatus.QUEUED }) {
            triggerQueueProcessing()
        }
    }

    fun enqueueDownload(
        url: String,
        videoInfo: VideoInfo?,
        format: VideoFormat?,
        isAudioOnly: Boolean,
        destinationDir: String? = null,
        mediaType: MediaType = videoInfo?.mediaType ?: (if (isAudioOnly) MediaType.AUDIO else MediaType.VIDEO),
        selectedGalleryIndices: List<Int> = emptyList()
    ) {
        val platform = videoInfo?.platform ?: Platform.fromUrl(url)
        val title = videoInfo?.title?.ifBlank { null } ?: when (mediaType) {
            MediaType.GALLERY -> "${platform.displayName} Gallery"
            MediaType.IMAGE -> "${platform.displayName} Photo"
            MediaType.AUDIO -> "${platform.displayName} Audio"
            MediaType.VIDEO -> "${platform.displayName} Video"
        }
        val thumbnail = videoInfo?.thumbnailUrl
        val formatLabel = when (mediaType) {
            MediaType.GALLERY -> if (selectedGalleryIndices.isNotEmpty()) "Gallery (${selectedGalleryIndices.size} Photos)" else "Gallery (${videoInfo?.galleryEntries?.size ?: 0} Photos)"
            MediaType.IMAGE -> "Photo (${format?.ext?.uppercase() ?: "JPG"})"
            MediaType.AUDIO -> "Audio (${format?.ext?.uppercase() ?: "MP3"})"
            MediaType.VIDEO -> if (isAudioOnly) "Audio (${format?.ext?.uppercase() ?: "MP3"})" else (format?.displayLabel ?: "Best Quality")
        }

        // Resolve the gallery selection to explicit indices up front. The picker
        // sends an empty list to mean "everything", which the engine cannot act
        // on once it needs to split videos from photos.
        val entries = videoInfo?.galleryEntries.orEmpty()
        val resolvedIndices = when {
            mediaType != MediaType.GALLERY -> selectedGalleryIndices
            selectedGalleryIndices.isNotEmpty() -> selectedGalleryIndices
            else -> entries.map { it.index }
        }
        val videoIndices = entries
            .filter { it.isVideo && it.index in resolvedIndices }
            .map { it.index }

        val id = "dl_${currentEpochMs()}_${randomId()}"
        val item = DownloadItem(
            id = id,
            url = url,
            title = title,
            thumbnailUrl = thumbnail,
            platform = platform,
            progressPercent = 0f,
            status = DownloadStatus.QUEUED,
            isAudioOnly = isAudioOnly || mediaType == MediaType.AUDIO,
            formatLabel = formatLabel,
            mediaType = mediaType,
            selectedGalleryIndices = resolvedIndices,
            galleryVideoIndices = videoIndices,
            galleryCount = if (mediaType == MediaType.GALLERY) resolvedIndices.size.takeIf { it > 0 } else null,
            createdAtEpochMs = currentEpochMs(),
            outputProfile = settings.value.outputProfile,
            transcodeCodec = settings.value.transcodeCodec,
            selectedVcodec = format?.vcodec,
            formatId = format?.formatId,
            destinationDir = destinationDir
        )

        repository.addOrUpdate(item)
        triggerQueueProcessing()
    }

    fun cancelDownload(id: String) {
        scope.launch {
            val jobToCancel = stateMutex.withLock {
                val job = activeJobs.remove(id)
                synchronized(lastMetricUpdateTimes) {
                    lastMetricUpdateTimes.remove(id)
                }
                job
            }
            jobToCancel?.cancel()
            engine.cancelDownload(id)
            jobToCancel?.join()
            repository.clearProgress(id)

            val item = repository.downloads.value.find { it.id == id }
            if (item != null && !item.isFinished) {
                repository.addOrUpdate(item.copy(status = DownloadStatus.CANCELLED, errorMessage = "Cancelled by user"))
            }
            triggerQueueProcessing()
        }
    }

    fun cancelAll() {
        scope.launch {
            val currentDownloads = repository.downloads.value
            val activeOrQueuedIds = currentDownloads
                .filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.MERGING }
                .map { it.id }

            val jobsToCancel = stateMutex.withLock {
                val jobs = activeJobs.values.toList()
                activeJobs.clear()
                synchronized(lastMetricUpdateTimes) {
                    lastMetricUpdateTimes.clear()
                }
                jobs
            }

            for (job in jobsToCancel) {
                job.cancel()
            }
            for (id in activeOrQueuedIds) {
                engine.cancelDownload(id)
            }
            for (job in jobsToCancel) {
                job.join()
            }

            for (id in activeOrQueuedIds) {
                repository.clearProgress(id)
                val item = repository.downloads.value.find { it.id == id }
                if (item != null && !item.isFinished) {
                    repository.addOrUpdate(item.copy(status = DownloadStatus.CANCELLED, errorMessage = "Cancelled by user"))
                }
            }
        }
    }

    fun retryDownload(id: String) {
        val item = repository.downloads.value.find { it.id == id } ?: return
        repository.clearProgress(id)
        repository.addOrUpdate(
            item.copy(
                status = DownloadStatus.QUEUED,
                progressPercent = 0f,
                errorMessage = null,
                speedFormatted = null,
                etaFormatted = null
            )
        )
        triggerQueueProcessing()
    }

    fun removeDownload(id: String, deleteDiskFile: Boolean = true) {
        scope.launch {
            val jobToCancel = stateMutex.withLock {
                val job = activeJobs.remove(id)
                synchronized(lastMetricUpdateTimes) {
                    lastMetricUpdateTimes.remove(id)
                }
                job
            }
            jobToCancel?.cancel()
            engine.cancelDownload(id)
            jobToCancel?.join()
            repository.clearProgress(id)

            val item = repository.downloads.value.find { it.id == id }
            if (deleteDiskFile && item != null) {
                val pathsToDelete = (item.outputPaths + listOfNotNull(item.outputPath)).distinct()
                for (path in pathsToDelete) {
                    if (path.isNotBlank()) {
                        try {
                            getPlatformActions().deleteFile(path)
                        } catch (e: Exception) {
                            println("Error deleting physical file $path: ${e.message}")
                        }
                    }
                }
            }
            repository.remove(id)
            triggerQueueProcessing()
        }
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

    private fun triggerQueueProcessing() {
        scope.launch {
            stateMutex.withLock {
                val maxConcurrent = settings.value.maxConcurrentDownloads.coerceIn(1, 5)
                val currentActiveCount = activeJobs.size

                if (currentActiveCount >= maxConcurrent) return@withLock

                val slotsAvailable = maxConcurrent - currentActiveCount
                val queuedItems = repository.downloads.value
                    .filter { it.status == DownloadStatus.QUEUED }
                    .take(slotsAvailable)

                for (queuedItem in queuedItems) {
                    startDownloadJobLocked(queuedItem)
                }
            }
        }
    }

    private fun startDownloadJobLocked(item: DownloadItem) {
        val job = scope.launch {
            try {
                val outputDir = item.destinationDir ?: settings.value.downloadDirectory.ifBlank {
                    getPlatformActions().getDefaultDownloadDirectory()
                }
                val formatId = item.formatId
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
                        val shouldUpdateMetrics: Boolean
                        synchronized(lastMetricUpdateTimes) {
                            val lastUpdate = lastMetricUpdateTimes[item.id] ?: 0L
                            if (now - lastUpdate >= 600L || progress >= 99f || (speed != null && lastUpdate == 0L)) {
                                lastMetricUpdateTimes[item.id] = now
                                shouldUpdateMetrics = true
                            } else {
                                shouldUpdateMetrics = false
                            }
                        }

                        if (shouldUpdateMetrics) {
                            repository.updateProgress(
                                id = item.id,
                                progress = progress,
                                speed = speed,
                                eta = eta,
                                downloaded = downloaded,
                                total = total
                            )
                        } else {
                            repository.updateProgress(
                                id = item.id,
                                progress = progress,
                                speed = null,
                                eta = null,
                                downloaded = null,
                                total = null
                            )
                        }
                    }
                )

                repository.clearProgress(item.id)

                result.fold(
                    onSuccess = { filePaths ->
                        val primaryPath = filePaths.firstOrNull()
                        val updated = repository.downloads.value.find { it.id == item.id }
                        if (updated != null) {
                            repository.addOrUpdate(
                                updated.copy(
                                    status = DownloadStatus.COMPLETED,
                                    progressPercent = 100f,
                                    outputPath = primaryPath,
                                    outputPaths = filePaths,
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
            } finally {
                stateMutex.withLock {
                    activeJobs.remove(item.id)
                    synchronized(lastMetricUpdateTimes) {
                        lastMetricUpdateTimes.remove(item.id)
                    }
                }
                triggerQueueProcessing()
            }
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
