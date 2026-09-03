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
    val lastAttemptEpochMs: Long = 0L,
    val isFailed: Boolean = false
)

class Outbox(private val fileNameOverride: String? = null) {

    private val fileName: String get() = fileNameOverride ?: OUTBOX_FILENAME

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val mutex = Mutex()
    private val seqGenerator = AtomicLong(System.currentTimeMillis())
    private var items = mutableListOf<OutboxItem>()

    init {
        loadOutbox()
    }

    private fun loadOutbox() {
        val raw = readTextFile(fileName)
        if (!raw.isNullOrBlank()) {
            try {
                items = json.decodeFromString<List<OutboxItem>>(raw).toMutableList()
            } catch (e: Exception) {
                println("Failed to parse outbox: ${e.message}")
            }
        }
        // Seed seqGenerator from highest persisted seq (Stage 6.2, §0.9)
        val maxPersistedSeq = items.maxOfOrNull { it.seq } ?: 0L
        val now = System.currentTimeMillis()
        seqGenerator.set(maxOf(maxPersistedSeq, now))
    }

    private fun saveOutboxLocked() {
        try {
            val raw = json.encodeToString(items)
            saveTextFile(fileName, raw)
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
        items.filter { it.targetDeviceId == targetDeviceId && !it.isFailed }.toList()
    }

    suspend fun getAllItems(): List<OutboxItem> = mutex.withLock {
        items.toList()
    }

    /**
     * Drops every queued packet for a device that has been unpaired.
     *
     * Queued work outlives the pairing otherwise: the items persist across
     * restarts and would be flushed at a device re-paired under the same id,
     * delivering requests the user made before revoking trust.
     */
    suspend fun forgetDevice(targetDeviceId: String) = mutex.withLock {
        val removed = items.removeAll { it.targetDeviceId == targetDeviceId }
        if (removed) {
            saveOutboxLocked()
        }
    }

    suspend fun markAttempt(seq: Long): Boolean = mutex.withLock {
        val index = items.indexOfFirst { it.seq == seq }
        if (index >= 0) {
            val current = items[index]
            val newAttempts = current.attempts + 1
            val isFailed = newAttempts >= MAX_OUTBOX_ATTEMPTS
            items[index] = current.copy(
                attempts = newAttempts,
                lastAttemptEpochMs = System.currentTimeMillis(),
                isFailed = isFailed
            )
            saveOutboxLocked()
            return@withLock !isFailed
        }
        false
    }

    fun currentHighestSeq(): Long = seqGenerator.get()

    companion object {
        const val MAX_OUTBOX_ATTEMPTS = 5
        private const val OUTBOX_FILENAME = "courier_link_outbox.json"
    }
}