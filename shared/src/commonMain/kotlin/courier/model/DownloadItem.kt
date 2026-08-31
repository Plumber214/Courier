package courier.model

import kotlinx.serialization.Serializable

@Serializable
data class DownloadItem(
    val id: String,
    val url: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val platform: Platform = Platform.OTHER,
    val progressPercent: Float = 0f,
    val speedFormatted: String? = null,
    val etaFormatted: String? = null,
    val totalSizeFormatted: String? = null,
    val downloadedSizeFormatted: String? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val errorMessage: String? = null,
    val outputPath: String? = null,
    val isAudioOnly: Boolean = false,
    val formatLabel: String? = null,
    val createdAtEpochMs: Long = 0L
) {
    val isFinished: Boolean
        get() = status == DownloadStatus.COMPLETED || status == DownloadStatus.FAILED || status == DownloadStatus.CANCELLED

    val isActive: Boolean
        get() = status == DownloadStatus.DOWNLOADING || status == DownloadStatus.MERGING || status == DownloadStatus.FETCHING_INFO
}
