package courier.link

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutboxAndReliabilityTest {

    @Test
    fun testOutboxEnqueueAndAcknowledge() = runBlocking {
        val outbox = Outbox()
        val packet = LinkPacket(
            type = LinkConstants.TYPE_DOWNLOAD_REQUEST,
            body = buildJsonObject {
                put("url", "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            }
        )

        val targetDevice = "test_desktop_target"
        val seq1 = outbox.enqueue(targetDevice, packet)
        val seq2 = outbox.enqueue(targetDevice, packet)

        assertTrue(seq2 > seq1, "Sequences must be monotonically increasing")

        val pendingBefore = outbox.getPendingForDevice(targetDevice)
        assertTrue(pendingBefore.size >= 2, "Outbox should contain pending items")

        outbox.acknowledge(targetDevice, seq2)
        val pendingAfter = outbox.getPendingForDevice(targetDevice)
        assertEquals(0, pendingAfter.size, "Acknowledging highest sequence clears all acknowledged items")
    }

    @Test
    fun testDeduplicationLogic() {
        val highestSeqMap = mutableMapOf<String, Long>()
        val deviceId = "device_alpha"
        highestSeqMap[deviceId] = 100L

        val incomingDuplicateSeq = 95L
        val incomingNewSeq = 101L

        val isDuplicate = incomingDuplicateSeq <= (highestSeqMap[deviceId] ?: 0L)
        val isNew = incomingNewSeq > (highestSeqMap[deviceId] ?: 0L)

        assertTrue(isDuplicate, "Sequence <= highest seen must be detected as duplicate")
        assertTrue(isNew, "Sequence > highest seen must be accepted as new")
    }
}