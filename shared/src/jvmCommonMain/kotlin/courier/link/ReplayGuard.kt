package courier.link

import courier.platform.readTextFile
import courier.platform.saveTextFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Highest packet sequence number accepted from each peer, persisted across
 * restarts.
 *
 * The sender's [Outbox] retries anything it has not seen acked, including
 * across a restart of either side. If the receiver holds its high-water marks
 * only in memory, a restart resets them to zero and every outstanding retry is
 * reprocessed as new — a download queued before the restart is enqueued twice.
 *
 * At-least-once delivery is only effectively-once if this survives the process.
 */
class ReplayGuard {

    private val json = Json { ignoreUnknownKeys = true }
    private val marks = ConcurrentHashMap<String, Long>()

    init {
        val raw = readTextFile(REPLAY_GUARD_FILENAME)
        if (!raw.isNullOrBlank()) {
            try {
                marks.putAll(json.decodeFromString<Map<String, Long>>(raw))
            } catch (e: Exception) {
                println("Failed to parse replay guard: ${e.message}")
            }
        }
    }

    /** The highest sequence accepted from [deviceId], or 0 if none yet. */
    fun highestSeq(deviceId: String): Long = marks[deviceId] ?: 0L

    /**
     * Returns true when [seq] has already been handled and the caller should
     * drop it as a replay. Sequence 0 means "unsequenced" and is never treated
     * as a duplicate.
     */
    fun isReplay(deviceId: String, seq: Long): Boolean =
        seq > 0L && seq <= highestSeq(deviceId)

    /** Records [seq] as accepted from [deviceId] and persists the new mark. */
    fun record(deviceId: String, seq: Long) {
        if (seq <= 0L) return
        val previous = marks[deviceId] ?: 0L
        if (seq <= previous) return
        marks[deviceId] = seq
        persist()
    }

    /** Drops the mark for a device that has been unpaired. */
    fun forget(deviceId: String) {
        if (marks.remove(deviceId) != null) {
            persist()
        }
    }

    private fun persist() {
        try {
            saveTextFile(REPLAY_GUARD_FILENAME, json.encodeToString(marks.toMap()))
        } catch (e: Exception) {
            println("Failed to persist replay guard: ${e.message}")
        }
    }

    companion object {
        private const val REPLAY_GUARD_FILENAME = "courier_link_replay_guard.json"
    }
}
