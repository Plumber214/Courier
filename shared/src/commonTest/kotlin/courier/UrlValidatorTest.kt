package courier

import courier.engine.UrlValidator
import courier.model.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UrlValidatorTest {

    @Test
    fun testExtractUrlFromText() {
        val raw = "Check this awesome video https://youtu.be/dQw4w9WgXcQ it is cool!"
        val extracted = UrlValidator.extractUrl(raw)
        assertEquals("https://youtu.be/dQw4w9WgXcQ", extracted)
    }

    @Test
    fun testCleanUrlTrailingPunctuation() {
        val raw = "https://www.youtube.com/watch?v=dQw4w9WgXcQ."
        val cleaned = UrlValidator.cleanUrl(raw)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", cleaned)
    }

    @Test
    fun testPlatformDetection() {
        assertEquals(Platform.YOUTUBE, Platform.fromUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(Platform.YOUTUBE, Platform.fromUrl("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(Platform.YOUTUBE, Platform.fromUrl("https://youtube.com/shorts/3xyz123"))

        assertEquals(Platform.TIKTOK, Platform.fromUrl("https://www.tiktok.com/@user/video/71234567890"))
        assertEquals(Platform.TIKTOK, Platform.fromUrl("https://vm.tiktok.com/ZM8abc123/"))

        assertEquals(Platform.INSTAGRAM, Platform.fromUrl("https://www.instagram.com/reel/C2xyz123/"))
        assertEquals(Platform.INSTAGRAM, Platform.fromUrl("https://instagram.com/p/C2xyz123/"))

        assertEquals(Platform.FACEBOOK, Platform.fromUrl("https://www.facebook.com/watch/?v=123456789"))
        assertEquals(Platform.FACEBOOK, Platform.fromUrl("https://fb.watch/abc123xyz/"))
        assertEquals(Platform.FACEBOOK, Platform.fromUrl("https://facebook.com/reel/123456789"))

        assertEquals(Platform.OTHER, Platform.fromUrl("https://vimeo.com/123456"))
    }

    @Test
    fun testSupportedVideoUrl() {
        assertTrue(UrlValidator.isSupportedVideoUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(UrlValidator.isSupportedVideoUrl("https://www.tiktok.com/@user/video/71234567890"))
        assertTrue(UrlValidator.isSupportedVideoUrl("https://www.instagram.com/reel/C2xyz123/"))
        assertTrue(UrlValidator.isSupportedVideoUrl("https://fb.watch/abc123xyz/"))
        assertTrue(UrlValidator.isSupportedVideoUrl("https://twitter.com/user/status/123"))
        assertFalse(UrlValidator.isSupportedVideoUrl("not a valid url at all"))
    }
}
