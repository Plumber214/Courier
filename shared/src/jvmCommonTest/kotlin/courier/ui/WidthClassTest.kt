package courier.ui

import courier.ui.layout.EXPANDED_MIN_DP
import courier.ui.layout.MEDIUM_MIN_DP
import courier.ui.layout.WidthClass
import courier.ui.layout.widthClassFor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The layout has to read available width, not the operating system.
 *
 * Until v1.7.0 the only adaptive decision in the tree was `isAndroid()`, so a
 * desktop window dragged narrow kept the wide layout — a 100 dp thumbnail, two
 * gaps and up to three 40 dp buttons — and a tablet in landscape got the phone
 * one.
 */
class WidthClassTest {

    @Test
    fun `a phone in portrait is compact`() {
        assertEquals(WidthClass.COMPACT, widthClassFor(360))
        assertEquals(WidthClass.COMPACT, widthClassFor(412))
    }

    @Test
    fun `a narrow desktop window is compact too`() {
        // The point of the change: this is a window question, not an OS one.
        assertEquals(WidthClass.COMPACT, widthClassFor(480))
    }

    @Test
    fun `a tablet or ordinary desktop window is medium`() {
        assertEquals(WidthClass.MEDIUM, widthClassFor(MEDIUM_MIN_DP))
        assertEquals(WidthClass.MEDIUM, widthClassFor(800))
        assertEquals(WidthClass.MEDIUM, widthClassFor(EXPANDED_MIN_DP - 1))
    }

    @Test
    fun `a wide window is expanded`() {
        assertEquals(WidthClass.EXPANDED, widthClassFor(EXPANDED_MIN_DP))
        assertEquals(WidthClass.EXPANDED, widthClassFor(2560))
    }

    @Test
    fun `the boundaries do not overlap or leave a gap`() {
        assertEquals(WidthClass.COMPACT, widthClassFor(MEDIUM_MIN_DP - 1))
        assertEquals(WidthClass.MEDIUM, widthClassFor(MEDIUM_MIN_DP))
        assertEquals(WidthClass.MEDIUM, widthClassFor(EXPANDED_MIN_DP - 1))
        assertEquals(WidthClass.EXPANDED, widthClassFor(EXPANDED_MIN_DP))
    }

    @Test
    fun `a degenerate width is still classified, not crashed on`() {
        // BoxWithConstraints can report zero during the first frame of a
        // resize, and on Android before the window is attached.
        assertEquals(WidthClass.COMPACT, widthClassFor(0))
        assertEquals(WidthClass.COMPACT, widthClassFor(-1))
    }
}
