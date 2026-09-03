package courier.engine

import courier.model.OutputProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A quality choice made on one device has to survive the trip to another.
 *
 * Before v1.7.0 the receiving side dropped `formatHint` entirely, so picking
 * 1080p on the phone downloaded whatever the desktop's default was.
 */
class RemoteHintFormatTest {

    @Test
    fun `named presets survive the trip intact`() {
        assertEquals("1080p", FormatSelector.presetForRemoteHint("1080p"))
        assertEquals("720p", FormatSelector.presetForRemoteHint("720p"))
        assertEquals("480p", FormatSelector.presetForRemoteHint("480p"))
        assertEquals("360p", FormatSelector.presetForRemoteHint("360p"))
    }

    @Test
    fun `resolution strings from an extracted ladder are understood`() {
        assertEquals("1080p", FormatSelector.presetForRemoteHint("1920x1080"))
        assertEquals("720p", FormatSelector.presetForRemoteHint("1280x720"))
        assertEquals("1080p", FormatSelector.presetForRemoteHint("1080p60"))
        assertEquals("1080p", FormatSelector.presetForRemoteHint("hd1080"))
    }

    @Test
    fun `an unrecognised or absent hint means best available`() {
        assertNull(FormatSelector.presetForRemoteHint(null))
        assertNull(FormatSelector.presetForRemoteHint(""))
        assertNull(FormatSelector.presetForRemoteHint("   "))
        assertNull(FormatSelector.presetForRemoteHint("best"))
        assertNull(FormatSelector.presetForRemoteHint("Highest"))
        assertNull(FormatSelector.presetForRemoteHint("some-nonsense"))
    }

    @Test
    fun `a request above the highest preset is left uncapped`() {
        // The sender asked for more than any preset expresses, so applying a
        // 1080p ceiling would quietly downgrade it.
        assertNull(FormatSelector.presetForRemoteHint("2160p"))
        assertNull(FormatSelector.presetForRemoteHint("3840x2160"))
        assertNull(FormatSelector.presetForRemoteHint("1440p"))
    }

    @Test
    fun `an odd height rounds down to a preset rather than up`() {
        assertEquals("720p", FormatSelector.presetForRemoteHint("900p"))
        assertEquals("480p", FormatSelector.presetForRemoteHint("640x540"))
        assertEquals("360p", FormatSelector.presetForRemoteHint("240p"))
    }

    /**
     * videoFormatArg passes anything containing `+` or `/` through verbatim as a
     * yt-dlp selection expression. A hint arrives from another device, so it
     * must never reach that path.
     */
    @Test
    fun `a hint cannot smuggle a raw yt-dlp format expression to the receiver`() {
        val hostile = "bestvideo[vcodec^=av01]+bestaudio/worst"
        val preset = FormatSelector.presetForRemoteHint(hostile)

        assertTrue(
            preset == null || preset in setOf("1080p", "720p", "480p", "360p"),
            "Normalisation returned something outside the preset set: $preset"
        )

        val arg = FormatSelector.videoFormatArg(preset, OutputProfile.EDITING_NATIVE)
        assertTrue(
            !arg.contains("av01") && !arg.contains("worst"),
            "A peer-supplied expression reached the yt-dlp format argument: $arg"
        )
    }

    @Test
    fun `a normalised preset still produces a height-capped selector`() {
        val arg = FormatSelector.videoFormatArg(
            FormatSelector.presetForRemoteHint("1280x720"),
            OutputProfile.EDITING_NATIVE
        )
        assertTrue(arg.contains("[height<=720]"), "Expected a 720 ceiling, got: $arg")
    }
}
