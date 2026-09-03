package courier.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `fileSizeBytes` was parsed out of yt-dlp's format list and then never shown,
 * so the picker asked people to choose a resolution without telling them what
 * it would cost.
 */
class FormattedFileSizeTest {

    private fun sizeOf(bytes: Long?) =
        VideoFormat("f", "label", fileSizeBytes = bytes).formattedFileSize

    @Test
    fun `an absent size renders nothing rather than a zero`() {
        // yt-dlp routinely reports no size for a merged format. "0 B" next to
        // 1080p would be worse than saying nothing.
        assertNull(sizeOf(null))
        assertNull(sizeOf(0L))
        assertNull(sizeOf(-1L))
    }

    @Test
    fun `bytes below a kilobyte are exact`() {
        assertEquals("512 B", sizeOf(512L))
        assertEquals("1023 B", sizeOf(1023L))
    }

    @Test
    fun `sizes carry one decimal below a hundred`() {
        assertEquals("1.0 KB", sizeOf(1024L))
        assertEquals("1.5 KB", sizeOf(1536L))
        assertEquals("1.4 GB", sizeOf(1_500_000_000L))
    }

    @Test
    fun `sizes above a hundred drop the decimal`() {
        // "847.3 MB" is three digits of noise on a number yt-dlp itself calls
        // approximate.
        assertEquals("150 MB", sizeOf(157_286_400L))
        assertEquals("500 MB", sizeOf(524_288_000L))
    }

    @Test
    fun `the unit climbs with the number`() {
        assertEquals("1.0 MB", sizeOf(1024L * 1024))
        assertEquals("1.0 GB", sizeOf(1024L * 1024 * 1024))
        assertEquals("1.0 TB", sizeOf(1024L * 1024 * 1024 * 1024))
        // Nothing above TB, so a larger number stays in TB rather than
        // running off the end of the unit list.
        assertEquals("1024 TB", sizeOf(1024L * 1024 * 1024 * 1024 * 1024))
    }
}
