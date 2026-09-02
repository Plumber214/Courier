package courier.link

import java.io.Reader

/**
 * Thrown when a peer sends more than the permitted number of characters without
 * terminating the line. Callers must treat this as fatal to the connection —
 * a peer that ignores the frame limit is either broken or hostile.
 */
class PacketTooLargeException(charsRead: Int) :
    Exception("Peer exceeded the $charsRead character frame limit without sending a newline")

/**
 * Reads one newline-terminated line, refusing to buffer past [maxChars].
 *
 * [java.io.BufferedReader.readLine] grows its internal buffer without bound
 * until it finds a newline or hits end of stream. Checking the length of the
 * returned string is therefore too late: the allocation has already happened,
 * and a peer streaming bytes with no newline exhausts the heap first. That is
 * CVE-2020-26164's resource-exhaustion issue, and it is reachable here before
 * authentication — [LinkServer] reads the identity packet from an unpaired,
 * pre-TLS peer.
 *
 * This reads through the (buffered) reader a character at a time against a hard
 * cap instead, so the worst case is bounded regardless of what the peer sends.
 *
 * The cap counts characters, not bytes. A UTF-8 character occupies at least one
 * byte, so the character count never exceeds the byte count on the wire and the
 * limit is conservative in the direction that matters.
 *
 * Returns null at end of stream, mirroring `readLine()`. Handles `\n`, `\r\n`
 * and a bare `\r` as terminators.
 */
fun Reader.readLineBounded(maxChars: Int = LinkConstants.MAX_PACKET_SIZE_BYTES): String? {
    val builder = StringBuilder()
    while (true) {
        val next = read()
        if (next == -1) {
            return if (builder.isEmpty()) null else builder.toString()
        }
        val ch = next.toChar()
        if (ch == '\n') {
            return builder.toString()
        }
        if (ch == '\r') {
            // Swallow the \n of a \r\n pair; a bare \r still terminates.
            mark(1)
            val peek = read()
            if (peek != -1 && peek.toChar() != '\n') {
                reset()
            }
            return builder.toString()
        }
        if (builder.length >= maxChars) {
            throw PacketTooLargeException(maxChars)
        }
        builder.append(ch)
    }
}
