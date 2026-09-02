package courier.link

import java.io.BufferedReader
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the Stage I hardening pass.
 *
 * Each of these covers a defect that shipped in v1.5.0 because nothing asserted
 * the behaviour. They are written against the specific failure, not the general
 * feature.
 */
class LinkHardeningTest {

    // --- Bounded frame reads (CVE-2020-26164 resource exhaustion) ---

    @Test
    fun `reads a normal line`() {
        val reader = BufferedReader(StringReader("{\"type\":\"courier.ping\"}\n"))
        assertEquals("{\"type\":\"courier.ping\"}", reader.readLineBounded())
    }

    @Test
    fun `returns null at end of stream`() {
        assertNull(BufferedReader(StringReader("")).readLineBounded())
    }

    @Test
    fun `handles CRLF without leaking the newline into the next frame`() {
        val reader = BufferedReader(StringReader("first\r\nsecond\n"))
        assertEquals("first", reader.readLineBounded())
        assertEquals("second", reader.readLineBounded())
    }

    @Test
    fun `throws before buffering past the cap when the peer never sends a newline`() {
        // The whole point: a peer that streams forever must be stopped while
        // reading, not by inspecting the returned string afterwards.
        val endless = BufferedReader(StringReader("x".repeat(5_000)))
        assertFailsWith<PacketTooLargeException> {
            endless.readLineBounded(maxChars = 64)
        }
    }

    @Test
    fun `accepts a frame exactly at the cap`() {
        val exact = "y".repeat(64)
        val reader = BufferedReader(StringReader("$exact\n"))
        assertEquals(exact, reader.readLineBounded(maxChars = 64))
    }

    // --- Reconnect backoff ---

    @Test
    fun `backoff doubles toward the cap`() {
        var backoff = nextBackoffMs(0L)
        assertEquals(LinkConstants.RECONNECT_BACKOFF_MIN_MS, backoff)
        backoff = nextBackoffMs(backoff)
        assertEquals(LinkConstants.RECONNECT_BACKOFF_MIN_MS * 2, backoff)
    }

    @Test
    fun `backoff never exceeds the cap`() {
        var backoff = 1L
        repeat(50) { backoff = nextBackoffMs(backoff) }
        assertEquals(LinkConstants.RECONNECT_BACKOFF_MAX_MS, backoff)
    }

    @Test
    fun `a cleared backoff restarts at the minimum rather than staying pinned`() {
        // The v1.5.0 bug: backoff only ever grew, so after roughly a minute of
        // uptime every retry sat at the 30s cap forever, including immediately
        // after a successful connect. Clearing must genuinely reset it.
        var backoff = 0L
        repeat(20) { backoff = nextBackoffMs(backoff) }
        assertEquals(LinkConstants.RECONNECT_BACKOFF_MAX_MS, backoff)

        val afterSuccessfulConnect = nextBackoffMs(0L)
        assertEquals(LinkConstants.RECONNECT_BACKOFF_MIN_MS, afterSuccessfulConnect)
        assertTrue(
            afterSuccessfulConnect < LinkConstants.RECONNECT_BACKOFF_MAX_MS,
            "A reset backoff must not remain at the cap"
        )
    }

    @Test
    fun `reconnect tick is short enough to meet the five second recovery target`() {
        assertTrue(
            LinkConstants.RECONNECT_TICK_MS + LinkConstants.RECONNECT_BACKOFF_MIN_MS <= 5_000L,
            "A device that becomes reachable must be retried within 5s"
        )
    }

    // --- Replay protection ---

    @Test
    fun `sequence zero is never treated as a replay`() {
        val guard = ReplayGuard()
        assertFalse(guard.isReplay("unsequenced-peer", 0L))
    }

    @Test
    fun `a recorded sequence is rejected on redelivery`() {
        val guard = ReplayGuard()
        val peer = "replay-test-${System.nanoTime()}"
        val seq = System.currentTimeMillis()

        assertFalse(guard.isReplay(peer, seq), "First delivery must be accepted")
        guard.record(peer, seq)
        assertTrue(guard.isReplay(peer, seq), "Redelivery of the same seq must be dropped")
        assertTrue(guard.isReplay(peer, seq - 1), "An older seq must also be dropped")
        assertFalse(guard.isReplay(peer, seq + 1), "A newer seq must still be accepted")

        guard.forget(peer)
    }

    @Test
    fun `marks are per device`() {
        val guard = ReplayGuard()
        val a = "peer-a-${System.nanoTime()}"
        val b = "peer-b-${System.nanoTime()}"
        val seq = System.currentTimeMillis()

        guard.record(a, seq)
        assertTrue(guard.isReplay(a, seq))
        assertFalse(guard.isReplay(b, seq), "One peer's mark must not suppress another's packets")

        guard.forget(a)
        guard.forget(b)
    }

    @Test
    fun `forgetting a device clears its mark so a re-pair is not swallowed`() {
        val guard = ReplayGuard()
        val peer = "repair-test-${System.nanoTime()}"
        val seq = System.currentTimeMillis()

        guard.record(peer, seq)
        assertTrue(guard.isReplay(peer, seq))

        guard.forget(peer)
        assertFalse(guard.isReplay(peer, seq), "After unpairing, an old seq must no longer be suppressed")
    }

    // --- Connection caps ---

    @Test
    fun `per-IP cap is lower than the global cap`() {
        // Otherwise one host can fill every slot and lock out paired devices.
        assertTrue(
            LinkConstants.MAX_CONNECTIONS_PER_IP < LinkConstants.MAX_CONCURRENT_CONNECTIONS,
            "A single source must not be able to consume the whole connection budget"
        )
    }
}
