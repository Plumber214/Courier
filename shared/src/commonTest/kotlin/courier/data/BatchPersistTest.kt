package courier.data

import courier.model.DownloadItem
import courier.model.DownloadStatus
import courier.model.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bulk history transitions.
 *
 * Cancel All previously called addOrUpdate once per download, and each call
 * rewrote the whole history file — serialise, fsync, backup copy. [updateAll]
 * exists so one logical change is one write.
 */
class BatchPersistTest {

    private fun item(id: String, status: DownloadStatus) = DownloadItem(
        id = id,
        url = "https://example.com/$id",
        title = "Item $id",
        platform = Platform.YOUTUBE,
        status = status
    )

    @Test
    fun `updateAll applies the transform to every item and survives a reload`() {
        val repo = DownloadRepository()
        repo.saveDownloads(
            listOf(
                item("a", DownloadStatus.DOWNLOADING),
                item("b", DownloadStatus.QUEUED),
                item("c", DownloadStatus.COMPLETED)
            )
        )

        val cancelling = setOf("a", "b")
        repo.updateAll { existing ->
            if (existing.id in cancelling && !existing.isFinished) {
                existing.copy(status = DownloadStatus.CANCELLED, errorMessage = "Cancelled by user")
            } else {
                existing
            }
        }

        val byId = repo.downloads.value.associateBy { it.id }
        assertEquals(DownloadStatus.CANCELLED, byId.getValue("a").status)
        assertEquals(DownloadStatus.CANCELLED, byId.getValue("b").status)
        assertEquals(
            DownloadStatus.COMPLETED, byId.getValue("c").status,
            "A finished download must not be swept up by the batch"
        )

        // The single write really happened: a fresh repository reads it back.
        val reloaded = DownloadRepository().downloads.value.associateBy { it.id }
        assertEquals(3, reloaded.size)
        assertEquals(DownloadStatus.CANCELLED, reloaded.getValue("a").status)
        assertEquals("Cancelled by user", reloaded.getValue("b").errorMessage)
        assertEquals(DownloadStatus.COMPLETED, reloaded.getValue("c").status)

        repo.saveDownloads(emptyList())
    }

    @Test
    fun `addOrUpdate commits the item it was given and persists the committed list`() {
        val repo = DownloadRepository()
        repo.saveDownloads(listOf(item("x", DownloadStatus.QUEUED)))

        repo.addOrUpdate(item("x", DownloadStatus.DOWNLOADING))
        repo.addOrUpdate(item("y", DownloadStatus.QUEUED))

        assertEquals(2, repo.downloads.value.size)

        val reloaded = DownloadRepository().downloads.value.associateBy { it.id }
        assertEquals(DownloadStatus.DOWNLOADING, reloaded.getValue("x").status)
        assertTrue(reloaded.containsKey("y"), "A newly added item was not persisted")

        repo.saveDownloads(emptyList())
    }
}
