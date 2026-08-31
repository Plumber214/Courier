package courier.model

import kotlinx.serialization.Serializable

@Serializable
data class VideoInfo(
    val id: String,
    val url: String,
    val title: String,
    val uploader: String? = null,
    val durationSeconds: Long? = null,
    val thumbnailUrl: String? = null,
    val platform: Platform = Platform.OTHER,
    val formats: List<VideoFormat> = emptyList(),
    val directVideoUrl: String? = null
) {
    val formattedDuration: String
        get() {
            val totalSec = durationSeconds ?: return ""
            val hours = totalSec / 3600
            val minutes = (totalSec % 3600) / 60
            val seconds = totalSec % 60
            val minStr = if (hours > 0 && minutes < 10) "0$minutes" else "$minutes"
            val secStr = if (seconds < 10) "0$seconds" else "$seconds"
            return if (hours > 0) "$hours:$minStr:$secStr" else "$minutes:$secStr"
        }
}
