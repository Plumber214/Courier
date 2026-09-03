package courier.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The audio format choice has to actually reach yt-dlp.
 *
 * Both engines hard-coded `--audio-format mp3`, so the picker's "Best Audio
 * Quality (M4A / Original)" option produced an MP3. The choice was collected,
 * displayed on the download card, and then ignored.
 */
class AudioFormatArgsTest {

    private fun targetOf(args: List<String>): String {
        val i = args.indexOf("--audio-format")
        assertTrue(i >= 0 && i + 1 < args.size, "No --audio-format in $args")
        return args[i + 1]
    }

    @Test
    fun `choosing MP3 re-encodes to MP3`() {
        assertEquals("mp3", targetOf(FormatSelector.audioArgs("mp3")))
        assertEquals("mp3", targetOf(FormatSelector.audioArgs("MP3")))
    }

    @Test
    fun `choosing the original stream does not force MP3`() {
        assertEquals(
            "best", targetOf(FormatSelector.audioArgs("bestaudio")),
            "Selecting the original stream still produced an MP3"
        )
    }

    @Test
    fun `an unknown or absent selection keeps the original stream`() {
        // Remote requests and older queued items may carry no audio format at
        // all. Preserving the source beats silently re-encoding it.
        assertEquals("best", targetOf(FormatSelector.audioArgs(null)))
        assertEquals("best", targetOf(FormatSelector.audioArgs("")))
        assertEquals("best", targetOf(FormatSelector.audioArgs("something-else")))
    }

    @Test
    fun `both modes still extract audio from a best-audio selector`() {
        for (id in listOf("mp3", "bestaudio", null)) {
            val args = FormatSelector.audioArgs(id)
            assertTrue(args.contains("-x"), "Missing -x for $id")
            val f = args.indexOf("-f")
            assertTrue(f >= 0, "Missing -f for $id")
            assertEquals("bestaudio/best", args[f + 1])
        }
    }
}
