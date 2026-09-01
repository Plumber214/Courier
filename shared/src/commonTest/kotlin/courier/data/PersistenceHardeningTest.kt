package courier.data

import courier.model.DownloadItem
import courier.model.DownloadStatus
import courier.model.Platform
import courier.platform.deleteFile
import courier.platform.readTextFile
import courier.platform.saveTextFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PersistenceHardeningTest {

    @Test
    fun testAtomicSaveAndBackupRecovery() {
        val testFileName = "test_persistence_downloads.json"
        
        // 1. Clean up test files if lingering
        saveTextFile(testFileName, "")
        
        // 2. Save valid content
        val validJson1 = """
        [
            {
                "id": "dl_test_1",
                "url": "https://youtube.com/watch?v=1",
                "title": "Item 1",
                "status": "COMPLETED",
                "createdAtEpochMs": 1000
            }
        ]
        """.trimIndent()
        saveTextFile(testFileName, validJson1)

        val read1 = readTextFile(testFileName)
        assertNotNull(read1)
        assertTrue(read1.contains("dl_test_1"))

        // 3. Save second valid content (which creates .bak of the first)
        val validJson2 = """
        [
            {
                "id": "dl_test_2",
                "url": "https://youtube.com/watch?v=2",
                "title": "Item 2",
                "status": "COMPLETED",
                "createdAtEpochMs": 2000
            }
        ]
        """.trimIndent()
        saveTextFile(testFileName, validJson2)

        val read2 = readTextFile(testFileName)
        assertNotNull(read2)
        assertTrue(read2.contains("dl_test_2"))

        // 4. Verify backup file exists and has previous version
        val backupContent = readTextFile("$testFileName.bak")
        assertNotNull(backupContent)
        assertTrue(backupContent.contains("dl_test_1"))

        // 5. Clean up
        saveTextFile(testFileName, "")
    }

    @Test
    fun testRepositoryRecoversFromCorruptedPrimaryFileUsingBackup() {
        val testItem = DownloadItem(
            id = "dl_recovered_1",
            url = "https://youtube.com/watch?v=rec",
            title = "Recovered Item",
            status = DownloadStatus.COMPLETED
        )

        // Save a valid repository state
        val repo = DownloadRepository()
        repo.saveDownloads(listOf(testItem))

        // Corrupt primary file
        saveTextFile("courier_downloads.json", "{ truncated corrupted json [[ ...")

        // New repository instance should fall back to backup
        val recoveredRepo = DownloadRepository()
        val downloads = recoveredRepo.downloads.value
        assertEquals(1, downloads.size, "Should recover 1 item from backup file")
        assertEquals("dl_recovered_1", downloads.first().id)
    }
}