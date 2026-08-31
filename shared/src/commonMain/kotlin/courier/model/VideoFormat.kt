package courier.model

import kotlinx.serialization.Serializable

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
