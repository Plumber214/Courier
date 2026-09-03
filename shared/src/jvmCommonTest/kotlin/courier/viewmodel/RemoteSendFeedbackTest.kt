package courier.viewmodel

import courier.link.SendDownloadResult
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Sending a download to a paired device closed the picker and said nothing.
 *
 * `sendDownloadRequest` returned only a sequence number, which it produces
 * whether the packet went out over a live link or was merely written to the
 * outbox — so the caller could not have distinguished the two even if it had
 * wanted to report them.
 */
class RemoteSendFeedbackTest {

    @Test
    fun `a delivered request names the device it went to`() {
        val message = describeRemoteSend(SendDownloadResult.Sent(7L, "Studio Desktop"))
        assertTrue(message.contains("Studio Desktop"), message)
    }

    @Test
    fun `a queued request does not claim it was sent`() {
        val message = describeRemoteSend(SendDownloadResult.Queued(7L, "Pixel"))

        assertTrue(message.contains("Pixel"), message)
        assertTrue(
            message.contains("queued", ignoreCase = true),
            "A queued request has to say so: $message"
        )
        // The outbox is durable, so this is not a failure — but it must not
        // read like the download has started on the other device.
        assertFalse(
            message.contains("Sent to", ignoreCase = true),
            "A queued request was described as sent: $message"
        )
    }

    @Test
    fun `an unpaired target reports that nothing was sent`() {
        val message = describeRemoteSend(SendDownloadResult.UnknownDevice("abc123"))

        assertTrue(
            message.contains("nothing was sent", ignoreCase = true),
            "Silence here would look identical to success: $message"
        )
        // The raw device id is not something the user has ever seen.
        assertFalse(message.contains("abc123"), message)
    }
}
