package courier.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What Courier is willing to volunteer about, and what it does with a playlist.
 *
 * [UrlValidator.isSupportedVideoUrl] drives the clipboard banner, so a false
 * positive means the app announces that it noticed something the user copied.
 * Until v1.7.0 the check ended in `startsWith("http")`, so it fired on
 * every URL — a bank page, a ticket, an internal document.
 */
class UrlPrecisionTest {

    @Test
    fun `known platforms and video sites are still recognised`() {
        val supported = listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://www.tiktok.com/@user/video/71234567890",
            "https://www.instagram.com/reel/C2xyz123/",
            "https://fb.watch/abc123xyz/",
            "https://twitter.com/user/status/123",
            "https://x.com/user/status/123",
            "https://vimeo.com/123456",
            "https://www.twitch.tv/videos/123456",
            "https://old.reddit.com/r/videos/comments/abc/title/",
            "https://soundcloud.com/artist/track"
        )
        for (url in supported) {
            assertTrue(UrlValidator.isSupportedVideoUrl(url), "Should be recognised: $url")
        }
    }

    @Test
    fun `an ordinary link is not announced as a video`() {
        val notMedia = listOf(
            "https://mybank.example.com/accounts/12345",
            "https://docs.google.com/document/d/abc/edit",
            "https://news.ycombinator.com/item?id=1",
            "https://github.com/anthropics/anthropic-sdk-python",
            "https://en.wikipedia.org/wiki/Kotlin",
            "not a valid url at all"
        )
        for (url in notMedia) {
            assertFalse(
                UrlValidator.isSupportedVideoUrl(url),
                "Courier should not volunteer about: $url"
            )
        }
    }

    /**
     * The old check used `contains`, which matches far more than the host.
     */
    @Test
    fun `a lookalike host does not match a supported one`() {
        assertFalse(
            UrlValidator.isSupportedVideoUrl("https://x.com.phishing.example/watch"),
            "A supported host as a prefix of another domain must not match"
        )
        assertFalse(
            UrlValidator.isSupportedVideoUrl("https://notx.com/status/1"),
            "A supported host as a suffix of another label must not match"
        )
        assertFalse(
            UrlValidator.isSupportedVideoUrl("https://example.com/vimeo.com/123"),
            "A supported host appearing in the path must not match"
        )
        // A real subdomain of a supported host still matches.
        assertTrue(UrlValidator.isSupportedVideoUrl("https://player.vimeo.com/video/123"))
    }

    @Test
    fun `a direct media file is recognised`() {
        assertTrue(UrlValidator.isSupportedVideoUrl("https://cdn.example.com/clip.mp4"))
        assertTrue(UrlValidator.isSupportedVideoUrl("https://cdn.example.com/stream.m3u8?token=abc"))
        assertFalse(UrlValidator.isSupportedVideoUrl("https://cdn.example.com/report.pdf"))
    }

    @Test
    fun `hostOf strips scheme, userinfo, port and www`() {
        assertEquals("youtube.com", UrlValidator.hostOf("https://www.youtube.com/watch?v=1"))
        assertEquals("example.com", UrlValidator.hostOf("http://example.com:8080/path"))
        assertEquals("example.com", UrlValidator.hostOf("https://user:pw@example.com/path"))
        assertNull(UrlValidator.hostOf("not-a-url"))
    }

    @Test
    fun `a playlist link is detected so the picker can offer a choice`() {
        assertEquals(
            "PLabc123",
            UrlValidator.playlistIdOf("https://www.youtube.com/watch?v=xyz&list=PLabc123")
        )
        assertEquals(
            "PLabc123",
            UrlValidator.playlistIdOf("https://www.youtube.com/watch?list=PLabc123&v=xyz")
        )
    }

    @Test
    fun `a plain video link carries no playlist`() {
        assertNull(UrlValidator.playlistIdOf("https://www.youtube.com/watch?v=xyz"))
        assertNull(UrlValidator.playlistIdOf("https://youtu.be/xyz"))
        assertNull(UrlValidator.playlistIdOf("https://www.youtube.com/watch?v=xyz&list="))
    }

    /**
     * A mix is an endless radio-style feed, not a finite list, so offering to
     * download "all" of one would be a mistake.
     */
    @Test
    fun `an auto-generated mix is not offered as a playlist`() {
        assertNull(UrlValidator.playlistIdOf("https://www.youtube.com/watch?v=xyz&list=RDabc123"))
        assertNull(UrlValidator.playlistIdOf("https://www.youtube.com/watch?v=xyz&list=RDMMxyz"))
    }
}
