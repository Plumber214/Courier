package courier.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Subtitles, chapters, cover art and metadata.
 *
 * Every one of these is an FFmpeg post-processing step, and a post-processor
 * that cannot run fails the download *after* every byte has been fetched. What
 * this covers is mostly the shapes in which that would happen.
 */
class MediaOptionArgsTest {

    private fun args(
        subs: Boolean = false,
        langs: List<String> = listOf("en"),
        chapters: Boolean = false,
        thumbnail: Boolean = false,
        metadata: Boolean = false,
        audioOnly: Boolean = false,
        merger: Boolean = true
    ) = FormatSelector.mediaOptionArgs(
        writeSubtitles = subs,
        subtitleLanguages = langs,
        embedChapters = chapters,
        embedThumbnail = thumbnail,
        embedMetadata = metadata,
        isAudioOnly = audioOnly,
        mergerAvailable = merger
    )

    @Test
    fun `everything off adds nothing`() {
        assertEquals(emptyList(), args())
    }

    @Test
    fun `subtitles are embedded when FFmpeg is present`() {
        val a = args(subs = true)

        assertTrue(a.contains("--embed-subs"), a.toString())
        // --embed-subs implies writing them and then removes the sidecar
        // files, which is what "embed" should mean. Passing --write-subs as
        // well would leave .vtt files beside every video.
        assertFalse(a.contains("--write-subs"), a.toString())
        assertEquals("en", a[a.indexOf("--sub-langs") + 1])
    }

    @Test
    fun `subtitles fall back to sidecar files without FFmpeg`() {
        val a = args(subs = true, merger = false)

        assertTrue(
            a.contains("--write-subs"),
            "Without a merger, writing the files is still possible: $a"
        )
        assertFalse(
            a.contains("--embed-subs"),
            "Embedding needs FFmpeg; asking for it would fail the download at the end: $a"
        )
    }

    @Test
    fun `auto-generated captions are requested too`() {
        // Most YouTube videos publish no author-written subtitles at all, so
        // without this the toggle appears to do nothing on the majority of
        // downloads.
        assertTrue(args(subs = true).contains("--write-auto-subs"))
    }

    @Test
    fun `an empty language list still asks for one language`() {
        // Otherwise yt-dlp is handed an empty --sub-langs and quietly fetches
        // nothing, which looks identical to the video having no subtitles.
        val a = args(subs = true, langs = emptyList())
        assertEquals("en", a[a.indexOf("--sub-langs") + 1])

        val blanks = args(subs = true, langs = listOf("  ", ""))
        assertEquals("en", blanks[blanks.indexOf("--sub-langs") + 1])
    }

    @Test
    fun `languages are de-duplicated and trimmed`() {
        val a = args(subs = true, langs = listOf(" en ", "es", "en"))
        assertEquals("en,es", a[a.indexOf("--sub-langs") + 1])
    }

    @Test
    fun `no post-processing is requested without FFmpeg`() {
        val a = args(chapters = true, thumbnail = true, metadata = true, merger = false)
        assertEquals(emptyList(), a, "These would fail after the whole file had downloaded")
    }

    @Test
    fun `audio downloads skip subtitles and chapters but keep cover art`() {
        val a = args(
            subs = true,
            chapters = true,
            thumbnail = true,
            metadata = true,
            audioOnly = true
        )

        // No audio container yt-dlp writes carries either, so these would only
        // leave stray subtitle files behind.
        assertFalse(a.contains("--embed-subs"), a.toString())
        assertFalse(a.contains("--sub-langs"), a.toString())
        assertFalse(a.contains("--embed-chapters"), a.toString())

        // Cover art and tags are exactly what an audio file wants.
        assertTrue(a.contains("--embed-thumbnail"), a.toString())
        assertTrue(a.contains("--embed-metadata"), a.toString())
    }

    @Test
    fun `each toggle only adds its own flag`() {
        assertEquals(listOf("--embed-chapters"), args(chapters = true))
        assertEquals(listOf("--embed-thumbnail"), args(thumbnail = true))
        assertEquals(listOf("--embed-metadata"), args(metadata = true))
    }

    @Test
    fun `--sub-langs is the only option carrying a value`() {
        // The Android engine pairs these into YoutubeDLRequest.addOption by
        // assuming a token that does not start with "--" is the previous
        // option's argument. That holds only while this is true.
        val a = args(subs = true, chapters = true, thumbnail = true, metadata = true)
        for ((i, token) in a.withIndex()) {
            if (!token.startsWith("--")) {
                assertEquals(
                    "--sub-langs", a[i - 1],
                    "Unexpected bare value '$token' follows ${a.getOrNull(i - 1)}"
                )
            }
        }
    }
}
