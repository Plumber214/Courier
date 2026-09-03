package courier.model

import kotlinx.serialization.Serializable

@Serializable
enum class DownloadStatus(val label: String) {
    QUEUED("Queued"),
    FETCHING_INFO("Analyzing Link..."),
    DOWNLOADING("Downloading"),
    MERGING("Processing"),

    /**
     * Stopped on purpose, with the partial file kept.
     *
     * Distinct from CANCELLED, which discards the attempt: a paused download
     * resumes from its `.part` file, so the bytes already fetched are not
     * fetched again. Before this existed the only way to stop a download was
     * Cancel, and the only way back was Retry from zero.
     */
    PAUSED("Paused"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled")
}
