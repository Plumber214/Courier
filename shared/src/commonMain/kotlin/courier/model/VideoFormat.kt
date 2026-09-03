package courier.model

import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

@Serializable
data class VideoFormat(
    val formatId: String,
    val qualityLabel: String,
    val resolution: String? = null,
    val ext: String = "mp4",
    val fileSizeBytes: Long? = null,
    val fps: Int? = null,
    val vcodec: String? = null,
    val acodec: String? = null,
    val isAudioOnly: Boolean = false
) {
    /**
     * True when this rendition's video codec imports directly into Premiere,
     * Resolve and Final Cut. AV1 and VP9 do not, and on YouTube they are the
     * only codecs available above 1080p.
     */
    val isEditorFriendly: Boolean
        get() {
            val v = vcodec?.lowercase() ?: return false
            return v.startsWith("avc1") || v.startsWith("h264") ||
                v.startsWith("hvc1") || v.startsWith("hev1")
        }

    /**
     * The rendition's size, ready to render, or null when yt-dlp did not report
     * one — which it often does not for a merged format.
     *
     * [fileSizeBytes] was parsed out of the format list and then never shown
     * anywhere, so the picker asked people to choose a resolution with no idea
     * what it would cost them.
     */
    val formattedFileSize: String?
        get() {
            val bytes = fileSizeBytes ?: return null
            if (bytes <= 0L) return null
            if (bytes < 1024L) return "$bytes B"

            val units = listOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble() / 1024.0
            var unitIndex = 0
            while (value >= 1024.0 && unitIndex < units.lastIndex) {
                value /= 1024.0
                unitIndex++
            }

            val unit = units[unitIndex]
            // One decimal below 100, none above: "1.4 GB" is useful, "847.3 MB"
            // is three digits of noise on a number that is an estimate anyway.
            return if (value >= 100.0) {
                "${value.roundToLong()} $unit"
            } else {
                val tenths = (value * 10.0).roundToLong()
                "${tenths / 10}.${tenths % 10} $unit"
            }
        }

    val displayLabel: String
        get() {
            return if (isAudioOnly) {
                if (qualityLabel.isNotBlank()) qualityLabel else "Audio Only (${ext.uppercase()})"
            } else {
                val res = if (!resolution.isNullOrBlank()) resolution else qualityLabel
                val fpsText = if (fps != null && fps > 30) " ${fps}fps" else ""
                val extText = if (ext.isNotBlank()) " (${ext.uppercase()})" else ""
                "$res$fpsText$extText"
            }
        }
}
