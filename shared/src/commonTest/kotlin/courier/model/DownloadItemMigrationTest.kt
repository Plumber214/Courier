package courier.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadItemMigrationTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Test
    fun testLegacyDownloadsJsonDeserializesWithoutNewFields() {
        // Sample legacy JSON from Courier v1.3.0 before Stage 2 schema changes
        val legacyJson = """
        [
            {
                "id": "dl_1725190000000_abc12345",
                "url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                "title": "Rick Astley - Never Gonna Give You Up",
                "thumbnailUrl": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
                "platform": "YOUTUBE",
                "progressPercent": 100.0,
                "status": "COMPLETED",
                "outputPath": "C:\\Downloads\\Courier\\Rick Astley.mp4",
                "isAudioOnly": false,
                "formatLabel": "1080p60",
                "mediaType": "VIDEO",
                "selectedGalleryIndices": [],
                "galleryVideoIndices": [],
                "createdAtEpochMs": 1725190000000,
                "outputProfile": "EDITING_NATIVE",
                "transcodeCodec": "H264",
                "selectedVcodec": "avc1.64002a"
            }
        ]
        """.trimIndent()

        val items = json.decodeFromString(ListSerializer(DownloadItem.serializer()), legacyJson)
        assertEquals(1, items.size)
        val item = items.first()

        assertEquals("dl_1725190000000_abc12345", item.id)
        assertEquals("Rick Astley - Never Gonna Give You Up", item.title)
        assertEquals(DownloadStatus.COMPLETED, item.status)

        // Verify that newly added fields safely default to null / empty / 0
        assertNull(item.formatId, "formatId should default to null on legacy items")
        assertNull(item.destinationDir, "destinationDir should default to null on legacy items")
        assertTrue(item.outputPaths.isEmpty(), "outputPaths should default to emptyList()")
        assertNull(item.partialPath, "partialPath should default to null on legacy items")
        assertEquals(0, item.resumeAttempts, "resumeAttempts should default to 0")
    }

    @Test
    fun testDownloadItemPreservesNewSchemaFields() {
        val item = DownloadItem(
            id = "test_item_1",
            url = "https://instagram.com/p/test",
            title = "Test Gallery",
            status = DownloadStatus.QUEUED,
            formatId = "1080p",
            destinationDir = "/custom/downloads",
            outputPaths = listOf("/custom/downloads/photo1.jpg", "/custom/downloads/photo2.jpg"),
            partialPath = "/custom/downloads/photo1.jpg.part",
            resumeAttempts = 2
        )

        val serialized = json.encodeToString(DownloadItem.serializer(), item)
        val deserialized = json.decodeFromString(DownloadItem.serializer(), serialized)

        assertEquals("1080p", deserialized.formatId)
        assertEquals("/custom/downloads", deserialized.destinationDir)
        assertEquals(2, deserialized.outputPaths.size)
        assertEquals("/custom/downloads/photo1.jpg", deserialized.outputPaths[0])
        assertEquals("/custom/downloads/photo2.jpg", deserialized.outputPaths[1])
        assertEquals("/custom/downloads/photo1.jpg.part", deserialized.partialPath)
        assertEquals(2, deserialized.resumeAttempts)
    }
}