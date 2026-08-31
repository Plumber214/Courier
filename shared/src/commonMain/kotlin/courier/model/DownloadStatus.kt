package courier.model

import kotlinx.serialization.Serializable

@Serializable
enum class DownloadStatus(val label: String) {
    QUEUED("Queued"),
    FETCHING_INFO("Analyzing Link..."),
    DOWNLOADING("Downloading"),
    MERGING("Processing"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled")
}
