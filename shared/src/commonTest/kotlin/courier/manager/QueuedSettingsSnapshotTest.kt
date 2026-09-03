package courier.manager

import courier.data.DownloadRepository
import courier.data.SettingsRepository
import courier.model.Platform
import courier.model.VideoInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The media options a download runs with are the ones it was created with.
 *
 * The same rule `outputProfile` already follows: changing a setting while
 * something is queued must not alter it, and a retry after a restart has to
 * reproduce the original request rather than whatever the settings say now.
 */
class QueuedSettingsSnapshotTest {

    @Test
    fun `enqueue captures the media options in force at the time`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val repository = DownloadRepository()
        repository.saveDownloads(emptyList())
        val settingsRepo = SettingsRepository()
        settingsRepo.updateSettings(
            settingsRepo.settings.value.copy(
                writeSubtitles = true,
                subtitleLanguages = listOf("en", "ja"),
                embedChapters = true,
                embedThumbnail = false,
                embedMetadata = true
            )
        )

        val manager = DownloadManager(
            engine = FakeTestDownloadEngine(),
            repository = repository,
            settingsRepository = settingsRepo,
            binaryManager = FakeTestBinaryManager(),
            scope = scope
        )

        manager.enqueueDownload(
            url = "https://youtube.com/watch?v=captured",
            videoInfo = VideoInfo(
                id = "captured",
                url = "https://youtube.com/watch?v=captured",
                title = "Captured",
                platform = Platform.YOUTUBE
            )
        )
        delay(100)

        val item = repository.downloads.value.first()
        assertTrue(item.writeSubtitles)
        assertEquals(listOf("en", "ja"), item.subtitleLanguages)
        assertTrue(item.embedChapters)
        assertFalse(item.embedThumbnail)
        assertTrue(item.embedMetadata)

        scope.cancel()
    }

    @Test
    fun `changing the settings does not alter an item already queued`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val engine = FakeTestDownloadEngine()
        val repository = DownloadRepository()
        repository.saveDownloads(emptyList())
        val settingsRepo = SettingsRepository()
        settingsRepo.updateSettings(
            settingsRepo.settings.value.copy(
                maxConcurrentDownloads = 1,
                writeSubtitles = true,
                embedMetadata = true
            )
        )

        val gate = CompletableDeferred<Unit>()
        engine.onDownloadBlock = { _, _ ->
            gate.await()
            Result.success(listOf("done.mp4"))
        }

        val manager = DownloadManager(
            engine = engine,
            repository = repository,
            settingsRepository = settingsRepo,
            binaryManager = FakeTestBinaryManager(),
            scope = scope
        )

        // One slot, so the second enqueue waits behind the first.
        manager.enqueueDownload(url = "https://youtube.com/watch?v=first")
        manager.enqueueDownload(url = "https://youtube.com/watch?v=second")
        delay(150)

        settingsRepo.updateSettings(
            settingsRepo.settings.value.copy(writeSubtitles = false, embedMetadata = false)
        )
        delay(100)

        assertTrue(
            repository.downloads.value.all { it.writeSubtitles && it.embedMetadata },
            "A queued download picked up a setting changed after it was created"
        )

        gate.complete(Unit)
        delay(200)
        scope.cancel()
    }
}
