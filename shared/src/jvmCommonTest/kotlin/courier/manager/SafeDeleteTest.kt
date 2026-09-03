package courier.manager

import courier.data.DownloadRepository
import courier.data.SettingsRepository
import courier.model.DownloadItem
import courier.model.DownloadStatus
import courier.model.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Removing a download from the list must not delete the user's media.
 *
 * Until v1.7.0 `removeDownload` defaulted to `deleteDiskFile = true` and the
 * Downloads list passed true unconditionally, so the trash icon on a finished
 * download deleted the file with no confirmation and no undo. Combined with the
 * engine's newest-file-in-the-directory fallback, the file it deleted was not
 * necessarily even the one it downloaded.
 */
class SafeDeleteTest {

    private val tempFiles = mutableListOf<File>()
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun cleanUp() {
        scopes.forEach { runCatching { it.cancel() } }
        tempFiles.forEach { runCatching { it.delete() } }
    }

    private fun realFile(): File {
        val f = File.createTempFile("courier-safe-delete-", ".mp4").also { tempFiles += it }
        f.writeText("pretend media")
        return f
    }

    private fun managerWith(item: DownloadItem): Pair<DownloadManager, DownloadRepository> {
        val repository = DownloadRepository()
        repository.saveDownloads(listOf(item))
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob()).also { scopes += it }
        val manager = DownloadManager(
            engine = FakeTestDownloadEngine(),
            repository = repository,
            settingsRepository = SettingsRepository(),
            binaryManager = FakeTestBinaryManager(),
            scope = scope
        )
        return manager to repository
    }

    private fun completedItem(id: String, file: File) = DownloadItem(
        id = id,
        url = "https://example.com/$id",
        title = "Item $id",
        platform = Platform.YOUTUBE,
        status = DownloadStatus.COMPLETED,
        progressPercent = 100f,
        outputPath = file.absolutePath,
        outputPaths = listOf(file.absolutePath)
    )

    @Test
    fun `removing from the list leaves the file on disk`() = runBlocking {
        val file = realFile()
        val (manager, repository) = managerWith(completedItem("keep-file", file))

        manager.removeDownload("keep-file")
        delay(600)

        assertTrue(
            repository.downloads.value.none { it.id == "keep-file" },
            "The row should be gone from the history"
        )
        assertTrue(
            file.exists(),
            "Removing a download from the list deleted the user's media"
        )
    }

    @Test
    fun `deleting explicitly does remove the file`() = runBlocking {
        val file = realFile()
        val (manager, repository) = managerWith(completedItem("delete-file", file))

        manager.removeDownload("delete-file", deleteDiskFile = true)
        delay(600)

        assertTrue(repository.downloads.value.none { it.id == "delete-file" })
        assertFalse(file.exists(), "An explicit delete did not remove the file")
    }

    @Test
    fun `every recorded output is deleted, not only the primary one`() = runBlocking {
        // A gallery download writes several files; deleting must not leave
        // orphans behind.
        val first = realFile()
        val second = realFile()
        val item = DownloadItem(
            id = "gallery",
            url = "https://example.com/gallery",
            title = "Carousel",
            platform = Platform.INSTAGRAM,
            status = DownloadStatus.COMPLETED,
            outputPath = first.absolutePath,
            outputPaths = listOf(first.absolutePath, second.absolutePath)
        )
        val (manager, _) = managerWith(item)

        manager.removeDownload("gallery", deleteDiskFile = true)
        delay(600)

        assertFalse(first.exists())
        assertFalse(second.exists(), "A secondary output file was left behind")
    }
}
