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
    val mediaType: MediaType = MediaType.VIDEO,
    val selectedGalleryIndices: List<Int> = emptyList(),
    /**
     * Which of [selectedGalleryIndices] are videos rather than photos.
     *
     * Instagram carousels mix the two. Photos need `--write-thumbnail
     * --skip-download`, videos need normal format selection, and applying the
     * photo flags to a whole mixed carousel saves each video's thumbnail as a
     * still and skips the video entirely. The engine splits on this and runs
     * one yt-dlp pass per kind.
     */
    val galleryVideoIndices: List<Int> = emptyList(),
    val galleryCount: Int? = null,
    val createdAtEpochMs: Long = 0L,

    // Captured at enqueue time rather than read from settings at download time,
    // so changing the setting mid-queue cannot alter an in-flight download and
    // a retry after restart reproduces the original request exactly.
    val outputProfile: OutputProfile = OutputProfile.EDITING_NATIVE,
    val transcodeCodec: TranscodeCodec = TranscodeCodec.H264,
    /** vcodec of the chosen rendition, when known — lets the engine skip a pointless re-encode. */
    val selectedVcodec: String? = null,
    val formatId: String? = null,
    val destinationDir: String? = null,
    val outputPaths: List<String> = emptyList(),
    val partialPath: String? = null,
    val resumeAttempts: Int = 0
) {
    val isFinished: Boolean
        get() = status == DownloadStatus.COMPLETED || status == DownloadStatus.FAILED || status == DownloadStatus.CANCELLED

    val isActive: Boolean
        get() = status == DownloadStatus.DOWNLOADING || status == DownloadStatus.MERGING || status == DownloadStatus.FETCHING_INFO
}
