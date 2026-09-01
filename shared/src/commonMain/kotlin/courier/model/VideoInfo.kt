package courier.model

import kotlinx.serialization.Serializable

@Serializable
enum class MediaType {
    VIDEO,
    AUDIO,
    IMAGE,
    GALLERY
}

@Serializable
data class GalleryEntry(
    val index: Int, // 1-based index for yt-dlp --playlist-items
    val id: String,
    val title: String? = null,
    val thumbnailUrl: String? = null,
    val directUrl: String? = null,
    val isVideo: Boolean = false
)

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
    val directVideoUrl: String? = null,
    val mediaType: MediaType = MediaType.VIDEO,
    val galleryEntries: List<GalleryEntry> = emptyList()
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
