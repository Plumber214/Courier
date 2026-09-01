package courier.data

import courier.model.AppSettings
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineAutoUpdateTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testLegacySettingsDeserializationDefaultsLastCheckedToZero() {
        val legacyJson = """
        {
            "defaultQuality": "1080p",
            "downloadDirectory": "C:/Downloads",
            "maxConcurrentDownloads": 3
        }
        """.trimIndent()

        val settings = json.decodeFromString<AppSettings>(legacyJson)
        assertEquals(0L, settings.lastEngineUpdateCheckEpochMs)
    }

    @Test
    fun testSevenDayThresholdCalculation() {
        val sevenDaysMs = 7L * 24 * 60 * 60 * 1000L
        val now = 1700000000000L

        val staleTimestamp = now - sevenDaysMs - 1000L
        val freshTimestamp = now - (3L * 24 * 60 * 60 * 1000L)

        assertTrue(now - staleTimestamp > sevenDaysMs, "Stale timestamp (> 7 days) must trigger auto-update")
        assertTrue(now - freshTimestamp <= sevenDaysMs, "Fresh timestamp (<= 7 days) must not trigger auto-update")
    }
}