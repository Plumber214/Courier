package courier.link

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutboxHardeningTest {

    @Test
    fun `sequence generator seeds from highest persisted seq across restarts`() = runBlocking<Unit> {
        val fileName = "test_outbox_seq_${System.nanoTime()}.json"
        val outbox1 = Outbox(fileNameOverride = fileName)

        val packet = LinkPacket(
            type = LinkConstants.TYPE_DOWNLOAD_REQUEST,
            body = buildJsonObject { put("url", "https://youtube.com/watch?v=123") }
        )

        val seq1 = outbox1.enqueue("peer-1", packet)
        assertTrue(seq1 > 0L)

        // Artificially inject a high seq item in the file (e.g. simulated future timestamp / monotonic clock)
        val futureSeq = seq1 + 1_000_000_000L
        val highSeqItem = OutboxItem(
            seq = futureSeq,
            targetDeviceId = "peer-1",
            packet = packet
        )
        courier.platform.saveTextFile(fileName, kotlinx.serialization.json.Json.encodeToString<List<OutboxItem>>(listOf(highSeqItem)))

        // Restart outbox from file
        val outbox2 = Outbox(fileNameOverride = fileName)

        // New sequence must be strictly greater than futureSeq, preventing replay discards
        val seq2 = outbox2.enqueue("peer-1", packet)
        assertTrue(seq2 > futureSeq, "Expected new seq ($seq2) to be strictly greater than max persisted seq ($futureSeq)")
    }

    @Test
    fun `retries are bounded by MAX_OUTBOX_ATTEMPTS and terminate to failed state`() = runBlocking<Unit> {
        val fileName = "test_outbox_retries_${System.nanoTime()}.json"
        val outbox = Outbox(fileNameOverride = fileName)

        val packet = LinkPacket(
            type = LinkConstants.TYPE_DOWNLOAD_REQUEST,
            body = buildJsonObject { put("url", "https://youtube.com/watch?v=abc") }
        )

        val seq = outbox.enqueue("peer-2", packet)
        assertEquals(1, outbox.getPendingForDevice("peer-2").size)

        // Attempt 1 to 4
        for (i in 1..4) {
            val canRetry = outbox.markAttempt(seq)
            assertTrue(canRetry, "Attempt $i should still allow retry")
        }
        assertEquals(1, outbox.getPendingForDevice("peer-2").size)

        // Attempt 5 (terminal)
        val canRetry5 = outbox.markAttempt(seq)
        assertFalse(canRetry5, "Attempt 5 must terminate and mark item as failed")

        // Once failed, item is no longer returned in getPendingForDevice (will not retry forever)
        val pending = outbox.getPendingForDevice("peer-2")
        assertEquals(0, pending.size, "Failed item should not be in pending list")

        // Persisted state retains item with isFailed = true
        val allItems = outbox.getAllItems()
        assertEquals(1, allItems.size)
        assertTrue(allItems.first().isFailed)
        assertEquals(5, allItems.first().attempts)
    }
}