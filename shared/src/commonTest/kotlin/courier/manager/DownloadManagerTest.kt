package courier.manager

import courier.data.DownloadRepository
import courier.data.SettingsRepository
import courier.engine.BinaryManager
import courier.engine.DownloadEngine
import courier.model.DownloadItem
import courier.model.Platform
import courier.model.VideoInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeTestDownloadEngine : DownloadEngine {
    val downloadedItems = mutableListOf<DownloadItem>()
    val activeDownloads = mutableListOf<String>()
    var onDownloadBlock: (suspend (DownloadItem, (Float, String?, String?, String?, String?) -> Unit) -> Result<List<String>>)? = null

    override suspend fun fetchVideoInfo(url: String, cookieBrowser: String?): Result<VideoInfo> {
        return Result.success(VideoInfo(id = "test_id", url = url, title = "Test Video", platform = Platform.YOUTUBE))
    }

    override suspend fun downloadVideo(
        item: DownloadItem,
        formatId: String?,
        outputDir: String,
        cookieBrowser: String?,
        onProgress: (progress: Float, speed: String?, eta: String?, downloaded: String?, total: String?) -> Unit
    ): Result<List<String>> {
        synchronized(activeDownloads) {
            activeDownloads.add(item.id)
        }
        synchronized(downloadedItems) {
            downloadedItems.add(item)
        }
        val custom = onDownloadBlock
        val res = if (custom != null) {
            custom(item, onProgress)
        } else {
            onProgress(50f, "5.2 MB/s", "00:15", "50 MB", "100 MB")
            onProgress(100f, "5.2 MB/s", "00:00", "100 MB", "100 MB")
            Result.success(listOf("$outputDir/${item.title}.mp4"))
        }
        synchronized(activeDownloads) {
            activeDownloads.remove(item.id)
        }
        return res
    }

    override fun cancelDownload(id: String) {
        synchronized(activeDownloads) {
            activeDownloads.remove(id)
        }
    }

    override suspend fun updateEngine(): Result<String> {
        return Result.success("Fake engine up to date")
    }
}

class FakeTestBinaryManager : BinaryManager {
    override val isReady: StateFlow<Boolean> = MutableStateFlow(true)
    override val isDownloading: StateFlow<Boolean> = MutableStateFlow(false)
    override val downloadProgress: StateFlow<Float> = MutableStateFlow(1f)
    override val statusMessage: StateFlow<String> = MutableStateFlow("Ready")
    override val errorMessage: StateFlow<String?> = MutableStateFlow(null)

    override suspend fun ensureBinariesReady(): Result<Unit> = Result.success(Unit)
    override suspend fun updateBinaries(): Result<String> = Result.success("Updated")
    override fun getBinaryVersion(): String = "1.0.0"
}

class DownloadManagerTest {

    @Test
    fun testProgressCarryingSpeedProducesNonNullSpeedFormattedOnItem() {
        runBlocking {
            val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val fakeEngine = FakeTestDownloadEngine()
            val repository = DownloadRepository()
            val settingsRepo = SettingsRepository()
            val binaryManager = FakeTestBinaryManager()
            val manager = DownloadManager(
                engine = fakeEngine,
                repository = repository,
                settingsRepository = settingsRepo,
                binaryManager = binaryManager,
                scope = testScope
            )

            repository.saveDownloads(emptyList())

            fakeEngine.onDownloadBlock = { item, onProgress ->
                onProgress(10f, "8.5 MB/s", "00:30", "10 MB", "100 MB")
                delay(50)
                Result.success(listOf("output.mp4"))
            }

            manager.enqueueDownload(
                url = "https://youtube.com/watch?v=speedtest",
                videoInfo = VideoInfo(id = "vid_1", url = "https://youtube.com/watch?v=speedtest", title = "Speed Test", platform = Platform.YOUTUBE),
                format = null,
                isAudioOnly = false
            )

            delay(100)

            val item = manager.downloads.value.firstOrNull()
            assertNotNull(item)
            testScope.cancel()
        }
    }

    @Test
    fun testMultipleEnqueuesNeverExceedMaxConcurrentDownloads() {
        runBlocking {
            val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val fakeEngine = FakeTestDownloadEngine()
            val repository = DownloadRepository()
            val settingsRepo = SettingsRepository()
            val binaryManager = FakeTestBinaryManager()
            val manager = DownloadManager(
                engine = fakeEngine,
                repository = repository,
                settingsRepository = settingsRepo,
                binaryManager = binaryManager,
                scope = testScope
            )

            repository.saveDownloads(emptyList())
            settingsRepo.updateSettings(settingsRepo.settings.value.copy(maxConcurrentDownloads = 2))

            var peakConcurrent = 0
            val downloadGate = CompletableDeferred<Unit>()

            fakeEngine.onDownloadBlock = { item, _ ->
                synchronized(fakeEngine.activeDownloads) {
                    if (fakeEngine.activeDownloads.size > peakConcurrent) {
                        peakConcurrent = fakeEngine.activeDownloads.size
                    }
                }
                downloadGate.await()
                Result.success(listOf("file_${item.id}.mp4"))
            }

            for (i in 1..10) {
                manager.enqueueDownload(
                    url = "https://youtube.com/watch?v=item_$i",
                    videoInfo = VideoInfo(id = "vid_$i", url = "https://youtube.com/watch?v=item_$i", title = "Item $i", platform = Platform.YOUTUBE),
                    format = null,
                    isAudioOnly = false
                )
            }

            delay(150)
            assertTrue(peakConcurrent <= 2, "Concurrent downloads ($peakConcurrent) exceeded max concurrent limit of 2")

            downloadGate.complete(Unit)
            delay(200)
            testScope.cancel()
        }
    }

    @Test
    fun testRemoveDuringCancelLeavesItemAbsentFromHistory() {
        runBlocking {
            val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val fakeEngine = FakeTestDownloadEngine()
            val repository = DownloadRepository()
            val settingsRepo = SettingsRepository()
            val binaryManager = FakeTestBinaryManager()
            val manager = DownloadManager(
                engine = fakeEngine,
                repository = repository,
                settingsRepository = settingsRepo,
                binaryManager = binaryManager,
                scope = testScope
            )

            repository.saveDownloads(emptyList())

            val downloadBlockGate = CompletableDeferred<Unit>()
            fakeEngine.onDownloadBlock = { _, _ ->
                try {
                    downloadBlockGate.await()
                    Result.success(listOf("removed.mp4"))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            manager.enqueueDownload(
                url = "https://youtube.com/watch?v=toremove",
                videoInfo = VideoInfo(id = "vid_rem", url = "https://youtube.com/watch?v=toremove", title = "To Remove", platform = Platform.YOUTUBE),
                format = null,
                isAudioOnly = false
            )

            delay(50)
            val item = manager.downloads.value.first()

            manager.removeDownload(item.id, deleteDiskFile = false)
            downloadBlockGate.complete(Unit)
            delay(100)

            val remaining = manager.downloads.value.find { it.id == item.id }
            assertNull(remaining, "Removed item must not appear in history as CANCELLED or any other status")
            testScope.cancel()
        }
    }
}