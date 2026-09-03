package courier.engine

import kotlinx.coroutines.flow.StateFlow

interface BinaryManager {
    val isReady: StateFlow<Boolean>
    val isDownloading: StateFlow<Boolean>
    val downloadProgress: StateFlow<Float>
    val statusMessage: StateFlow<String>
    val errorMessage: StateFlow<String?>

    /**
     * Whether FFmpeg is present.
     *
     * Separate from [isReady] because the two failures are different: without
     * yt-dlp nothing works at all, whereas without FFmpeg progressive downloads
     * still succeed and only merging and audio extraction fail. Reporting the
     * second as "Engine Ready" — which is what happened before v1.7.0 — sends
     * the user into a download that fails for reasons the UI never mentioned.
     */
    val isMergerAvailable: StateFlow<Boolean>

    suspend fun ensureBinariesReady(): Result<Unit>
    suspend fun updateBinaries(): Result<String>
    fun getBinaryVersion(): String
}

expect fun createBinaryManager(): BinaryManager
