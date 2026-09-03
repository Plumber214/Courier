package courier.link

import courier.model.DownloadItem
import courier.model.DownloadStatus
import courier.model.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Status streaming back to the requesting device.
 *
 * Before v1.7.0 this collected the whole downloads list, so one progress tick on
 * any download emitted a packet about this one; and on completion the job was
 * removed from the watcher map without being cancelled, so the collector ran for
 * the life of the process.
 */
class RemoteStatusStreamTest {

    private fun item(
        id: String,
        status: DownloadStatus = DownloadStatus.DOWNLOADING,
        percent: Float = 0f,
        speed: String? = null
    ) = DownloadItem(
        id = id,
        url = "https://example.com/$id",
        title = "Item $id",
        platform = Platform.YOUTUBE,
        progressPercent = percent,
        status = status,
        speedFormatted = speed
    )

    private fun CoroutineScope.collectInto(
        downloads: MutableStateFlow<List<DownloadItem>>,
        itemId: String,
        into: MutableList<RemoteStatusSnapshot>
    ): Job = launch(Dispatchers.Unconfined) {
        remoteStatusStream(downloads, itemId).collect { into += it }
    }

    @Test
    fun `progress on an unrelated download produces no updates for this one`() = runBlocking {
        val downloads = MutableStateFlow(
            listOf(item("mine", percent = 10f), item("theirs", percent = 0f))
        )
        val collected = mutableListOf<RemoteStatusSnapshot>()
        val job = collectInto(downloads, "mine", collected)

        // Ten ticks on the other download, none on ours.
        repeat(10) { i ->
            downloads.value = listOf(item("mine", percent = 10f), item("theirs", percent = i * 10f))
        }
        job.cancel()

        assertEquals(
            1, collected.size,
            "Unrelated downloads generated traffic for this item: $collected"
        )
    }

    @Test
    fun `sub-percent progress changes are collapsed`() = runBlocking {
        val downloads = MutableStateFlow(listOf(item("a", percent = 0f)))
        val collected = mutableListOf<RemoteStatusSnapshot>()
        val job = collectInto(downloads, "a", collected)

        // The engine reports far finer than a remote progress bar can show.
        listOf(0.1f, 0.4f, 0.9f, 1.0f, 1.2f, 1.8f, 2.0f).forEach { p ->
            downloads.value = listOf(item("a", percent = p))
        }
        job.cancel()

        assertEquals(
            listOf(0, 1, 2), collected.map { it.percent },
            "Expected one update per whole percent"
        )
    }

    @Test
    fun `a change in speed is still worth sending`() = runBlocking {
        val downloads = MutableStateFlow(listOf(item("a", percent = 5f, speed = "1.0 MB/s")))
        val collected = mutableListOf<RemoteStatusSnapshot>()
        val job = collectInto(downloads, "a", collected)

        downloads.value = listOf(item("a", percent = 5f, speed = "2.4 MB/s"))
        job.cancel()

        assertEquals(listOf("1.0 MB/s", "2.4 MB/s"), collected.map { it.speed })
    }

    @Test
    fun `the stream ends after the terminal state, having sent it`() = runBlocking {
        val downloads = MutableStateFlow(listOf(item("a", percent = 50f)))

        // If the stream did not complete, toList would hang until the timeout.
        val result = withTimeout(5_000) {
            coroutineScope {
                val collector = async(Dispatchers.Unconfined) {
                    remoteStatusStream(downloads, "a").toList()
                }
                downloads.value =
                    listOf(item("a", percent = 100f, status = DownloadStatus.COMPLETED))
                // Anything after the terminal state must not be picked up.
                downloads.value =
                    listOf(item("a", percent = 100f, status = DownloadStatus.QUEUED))
                collector.await()
            }
        }

        assertEquals(DownloadStatus.COMPLETED, result.last().status, "Terminal state was not sent")
        assertTrue(
            result.none { it.status == DownloadStatus.QUEUED },
            "Stream kept running after the download finished: $result"
        )
    }

    @Test
    fun `failure and cancellation also end the stream`() = runBlocking {
        for (terminal in listOf(DownloadStatus.FAILED, DownloadStatus.CANCELLED)) {
            val downloads = MutableStateFlow(listOf(item("a")))
            val result = withTimeout(5_000) {
                coroutineScope {
                    val collector = async(Dispatchers.Unconfined) {
                        remoteStatusStream(downloads, "a").toList()
                    }
                    downloads.value = listOf(item("a", status = terminal))
                    collector.await()
                }
            }
            assertEquals(terminal, result.last().status, "$terminal did not end the stream")
        }
    }
}
