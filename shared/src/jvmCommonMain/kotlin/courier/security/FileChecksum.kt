package courier.security

import java.io.File
import java.security.MessageDigest

/**
 * SHA-256 verification for artifacts this app downloads and then executes.
 *
 * Courier fetches three things it later runs or loads: the yt-dlp binary, the
 * FFmpeg archive, and its own update jar. HTTPS covers the transport; nothing
 * covered the contents. This closes that gap.
 *
 * Hashes are computed by streaming, because the FFmpeg archive is ~170 MB and
 * reading it into a ByteArray to reuse [courier.link.CertificateStore.computeSha256]
 * would allocate all of it at once.
 */
object FileChecksum {

    /** Lowercase hex SHA-256 of [file], streamed in 64 KB blocks. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * True when [file] hashes to [expectedHex].
     *
     * Comparison is case-insensitive and whitespace-tolerant because published
     * sums files vary in both. A blank expectation is never a match — "no hash
     * to check against" must not read as "verified".
     */
    fun matches(file: File, expectedHex: String?): Boolean {
        val expected = expectedHex?.trim()?.lowercase() ?: return false
        if (expected.length != 64 || !expected.all { it in "0123456789abcdef" }) return false
        return sha256(file).equals(expected, ignoreCase = true)
    }

    /**
     * Pulls one file's hash out of a `sha256sum`-format listing.
     *
     * Both publishers Courier depends on use this layout — yt-dlp's
     * `SHA2-256SUMS` and FFmpeg-Builds' `checksums.sha256`:
     *
     * ```
     * 66674953fe251b89…  yt-dlp.exe
     * a8f91bd414525…     yt-dlp_x86.exe
     * ```
     *
     * The name is matched exactly rather than by prefix, so a listing that
     * contains both `yt-dlp.exe` and `yt-dlp_x86.exe` cannot return the wrong
     * line. A leading `*` (binary-mode marker) is tolerated.
     */
    fun findInSumsFile(sumsText: String, fileName: String): String? {
        for (rawLine in sumsText.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            val separator = line.indexOfFirst { it == ' ' || it == '\t' }
            if (separator <= 0) continue

            val hash = line.substring(0, separator).trim()
            val name = line.substring(separator).trim().removePrefix("*").trim()

            if (name.equals(fileName, ignoreCase = true) && hash.length == 64) {
                return hash.lowercase()
            }
        }
        return null
    }
}
