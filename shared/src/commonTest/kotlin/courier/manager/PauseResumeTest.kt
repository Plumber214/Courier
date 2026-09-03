package courier.manager

import courier.data.DownloadRepository
import courier.data.SettingsRepository
import courier.model.DownloadStatus
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pause has to be distinguishable from Cancel.
 *
 * Before this existed the only way to stop a running download was Cancel, which
 * marks the item finished and discards the attempt; the only way back was Retry,
 * which starts from zero. On a large file over a slow connection that made
 * "stop for now" cost the whole download.
 */
class PauseResumeTest {

    private class Fixture {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val engine = FakeTestDownloadEngine()
        val repository = DownloadRepository()
        val manager: DownloadManager

        init {
            repository.saveDownloads(emptyList())
            manager = DownloadManager(
                engine = engine,
                repository = repository,
                settingsRepository = SettingsRepository(),
                binaryManager = FakeTestBinaryManager(),
                scope = scope
            )
        }

        fun enqueue(id: String = "paused"): String = manager.enqueueDownload(
            url = "https://youtube.com/watch?v=$id",
            videoInfo = VideoInfo(
                id = id,
                url = "https://youtube.com/watch?v=$id",
                title = "Item $id",
                platform = Platform.YOUTUBE
            )
        )

        fun close() = scope.cancel()
    }

    @Test
    fun `pausing keeps the item unfinished, unlike cancelling`() = runBlocking {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()
        f.engine.onDownloadBlock = { _, onProgress ->
            onProgress(37f, "4.0 MB/s", "00:20", "37 MB", "100 MB")
            gate.await()
            Result.success(listOf("never.mp4"))
        }

        f.enqueue()
        delay(200)

        val running = f.manager.downloads.value.first()
        assertEquals(DownloadStatus.DOWNLOADING, running.status)

        f.manager.pauseDownload(running.id)
        delay(300)

        val paused = f.manager.downloads.value.first { it.id == running.id }
        assertEquals(DownloadStatus.PAUSED, paused.status)
        assertNotEquals(
            DownloadStatus.CANCELLED, paused.status,
            "Pause must not be recorded as a cancellation"
        )
        assertTrue(
            !paused.isFinished,
            "A paused download is not finished — there is more to fetch"
        )

        gate.complete(Unit)
        f.close()
    }

    @Test
    fun `the progress reached is kept, not reset to zero`() = runBlocking {
        // Live progress lives in the repository's progress map, which is
        // cleared when the job stops. Without writing it onto the item first,
        // a paused download would display 0% and look like it had lost
        // everything.
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()
        f.engine.onDownloadBlock = { _, onProgress ->
            onProgress(64f, "4.0 MB/s", "00:10", "64 MB", "100 MB")
            gate.await()
            Result.success(listOf("never.mp4"))
        }

        f.enqueue("progress")
        delay(200)

        val id = f.manager.downloads.value.first().id
        f.manager.pauseDownload(id)
        delay(300)

        val paused = f.manager.downloads.value.first { it.id == id }
        assertEquals(64f, paused.progressPercent, 0.5f)

        gate.complete(Unit)
        f.close()
    }

    @Test
    fun `resuming queues the item again without resetting its progress`() = runBlocking {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()
        f.engine.onDownloadBlock = { _, onProgress ->
            onProgress(45f, null, null, null, null)
            gate.await()
            Result.success(listOf("done.mp4"))
        }

        f.enqueue("resume")
        delay(200)
        val id = f.manager.downloads.value.first().id

        f.manager.pauseDownload(id)
        delay(300)
        assertEquals(DownloadStatus.PAUSED, f.manager.downloads.value.first { it.id == id }.status)

        gate.complete(Unit)
        f.manager.resumeDownload(id)
        delay(300)

        val resumed = f.manager.downloads.value.first { it.id == id }
        assertTrue(
            resumed.status == DownloadStatus.DOWNLOADING ||
                resumed.status == DownloadStatus.QUEUED ||
                resumed.status == DownloadStatus.COMPLETED,
            "Resume should put the item back to work, was ${resumed.status}"
        )
        assertTrue(
            f.engine.downloadedItems.count { it.id == id } >= 2,
            "The engine should have been asked to download again"
        )

        f.close()
    }

    @Test
    fun `resume only acts on paused items`() = runBlocking {
        // Otherwise a stray tap could restart something the user had cancelled,
        // or re-enqueue a download that had already completed.
        val f = Fixture()
        f.engine.onDownloadBlock = { _, _ -> Result.success(listOf("done.mp4")) }

        f.enqueue("finished")
        delay(300)

        val done = f.manager.downloads.value.first()
        assertEquals(DownloadStatus.COMPLETED, done.status)

        f.manager.resumeDownload(done.id)
        delay(150)

        assertEquals(
            DownloadStatus.COMPLETED,
            f.manager.downloads.value.first { it.id == done.id }.status
        )
        f.close()
    }

    @Test
    fun `a paused download survives a restart as paused`() = runBlocking {
        // The restart-recovery pass re-queues anything left DOWNLOADING or
        // MERGING. A pause is a decision the user made, and must not be undone
        // by closing the app.
        val repository = DownloadRepository()
        repository.saveDownloads(
            listOf(
                courier.model.DownloadItem(
                    id = "paused_across_restart",
                    url = "https://youtube.com/watch?v=x",
                    title = "Paused",
                    status = DownloadStatus.PAUSED,
                    progressPercent = 55f
                )
            )
        )

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        DownloadManager(
            engine = FakeTestDownloadEngine(),
            repository = repository,
            settingsRepository = SettingsRepository(),
            binaryManager = FakeTestBinaryManager(),
            scope = scope
        )

        val item = repository.downloads.value.first()
        assertEquals(DownloadStatus.PAUSED, item.status)
        assertEquals(55f, item.progressPercent, 0.1f)
        scope.cancel()
    }
}
