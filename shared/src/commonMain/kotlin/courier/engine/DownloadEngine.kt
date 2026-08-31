package courier.engine

import courier.model.DownloadItem
import courier.model.VideoInfo

interface DownloadEngine {
    suspend fun fetchVideoInfo(url: String, cookieBrowser: String? = null): Result<VideoInfo>
    
    suspend fun downloadVideo(
        item: DownloadItem,
        formatId: String?,
        outputDir: String,
        cookieBrowser: String? = null,
        onProgress: (progress: Float, speed: String?, eta: String?, downloaded: String?, total: String?) -> Unit
    ): Result<String>
    
    fun cancelDownload(id: String)
    suspend fun updateEngine(): Result<String>
}

expect fun createDownloadEngine(): DownloadEngine
