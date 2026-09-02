package courier.link

import courier.platform.readTextFile
import courier.platform.saveTextFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class OutboxItem(
    val seq: Long,
    val targetDeviceId: String,
    val packet: LinkPacket,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val lastAttemptEpochMs: Long = 0L
)

class Outbox {

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val seqGenerator = AtomicLong(System.currentTimeMillis())
    private var items = mutableListOf<OutboxItem>()

    init {
        loadOutbox()
    }

    private fun loadOutbox() {
        val raw = readTextFile(OUTBOX_FILENAME)
        if (!raw.isNullOrBlank()) {
            try {
                items = json.decodeFromString<List<OutboxItem>>(raw).toMutableList()
            } catch (e: Exception) {
                println("Failed to parse outbox: ${e.message}")
            }
        }
    }

    private fun saveOutboxLocked() {
        try {
            val raw = json.encodeToString(items)
            saveTextFile(OUTBOX_FILENAME, raw)
        } catch (e: Exception) {
            println("Failed to persist outbox: ${e.message}")
        }
    }

    suspend fun enqueue(targetDeviceId: String, packet: LinkPacket): Long = mutex.withLock {
        val seq = seqGenerator.incrementAndGet()
        val item = OutboxItem(
            seq = seq,
            targetDeviceId = targetDeviceId,
            packet = packet
        )
        items.add(item)
        saveOutboxLocked()
        seq
    }

    suspend fun acknowledge(targetDeviceId: String, ackSeq: Long) = mutex.withLock {
        val removed = items.removeAll { it.targetDeviceId == targetDeviceId && it.seq <= ackSeq }
        if (removed) {
            saveOutboxLocked()
        }
    }

    suspend fun getPendingForDevice(targetDeviceId: String): List<OutboxItem> = mutex.withLock {
        items.filter { it.targetDeviceId == targetDeviceId }.toList()
    }

    suspend fun markAttempt(seq: Long) = mutex.withLock {
        val index = items.indexOfFirst { it.seq == seq }
        if (index >= 0) {
            val current = items[index]
            items[index] = current.copy(
                attempts = current.attempts + 1,
                lastAttemptEpochMs = System.currentTimeMillis()
            )
            saveOutboxLocked()
        }
    }

    companion object {
        private const val OUTBOX_FILENAME = "courier_link_outbox.json"
    }
}