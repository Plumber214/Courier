package courier.share

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A link shared into Courier has to arrive even when the app is already open.
 *
 * The old route wrote the shared link to the system clipboard and relied on
 * HomeScreen's `LaunchedEffect(Unit)` to notice it. That effect runs once at
 * composition and does not re-fire on `onNewIntent` — and MainActivity is
 * `singleTask`, so every share after the first took that path and did nothing,
 * having overwritten whatever the user had copied.
 */
class IncomingLinksTest {

    @BeforeTest
    fun reset() = IncomingLinks.clear()

    @AfterTest
    fun cleanUp() = IncomingLinks.clear()

    @Test
    fun `a link offered before anything observes it is still delivered`() {
        // MainActivity.onCreate handles the intent before setContent runs, so
        // the value has to survive until a collector exists.
        IncomingLinks.offer("https://youtu.be/first")

        assertEquals("https://youtu.be/first", IncomingLinks.pending.value)
        assertEquals("https://youtu.be/first", IncomingLinks.consume())
    }

    @Test
    fun `a link is consumed once, not replayed`() {
        IncomingLinks.offer("https://youtu.be/once")

        assertEquals("https://youtu.be/once", IncomingLinks.consume())
        assertNull(IncomingLinks.consume(), "A share must not re-open on the next recomposition")
        assertNull(IncomingLinks.pending.value)
    }

    @Test
    fun `a second share into a running app replaces the first`() {
        IncomingLinks.offer("https://youtu.be/first")
        assertEquals("https://youtu.be/first", IncomingLinks.consume())

        // This is the case that silently did nothing before v1.7.0.
        IncomingLinks.offer("https://youtu.be/second")
        assertEquals(
            "https://youtu.be/second", IncomingLinks.consume(),
            "A share into an already-running Courier was dropped"
        )
    }

    @Test
    fun `blank offers are ignored`() {
        IncomingLinks.offer("   ")
        assertNull(IncomingLinks.pending.value)

        IncomingLinks.offer("https://youtu.be/real")
        IncomingLinks.offer("")
        assertEquals(
            "https://youtu.be/real", IncomingLinks.pending.value,
            "A blank offer must not clear a real pending link"
        )
    }

    @Test
    fun `offers are trimmed`() {
        IncomingLinks.offer("  https://youtu.be/padded  ")
        assertEquals("https://youtu.be/padded", IncomingLinks.consume())
    }
}
