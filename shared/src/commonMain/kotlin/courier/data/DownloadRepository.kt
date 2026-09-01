package courier.data

import courier.model.DownloadItem
import courier.platform.readTextFile
import courier.platform.saveTextFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

data class DownloadProgress(
    val progressPercent: Float = 0f,
    val speedFormatted: String? = null,
    val etaFormatted: String? = null,
    val downloadedSizeFormatted: String? = null,
    val totalSizeFormatted: String? = null
)

class DownloadRepository {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val historyFileName = "courier_downloads.json"

    private val _downloads = MutableStateFlow<List<DownloadItem>>(loadDownloads())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    private val _progressMap = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val progressMap: StateFlow<Map<String, DownloadProgress>> = _progressMap.asStateFlow()

    private fun loadDownloads(): List<DownloadItem> {
        return try {
            val content = readTextFile(historyFileName)
            if (!content.isNullOrBlank()) {
                json.decodeFromString(ListSerializer(DownloadItem.serializer()), content)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun persistDownloads(items: List<DownloadItem>) {
        try {
            val content = json.encodeToString(ListSerializer(DownloadItem.serializer()), items)
            saveTextFile(historyFileName, content)
        } catch (e: Exception) {
            println("Failed to save downloads: ${e.message}")
        }
    }

    fun saveDownloads(items: List<DownloadItem>) {
        _downloads.update {
            persistDownloads(items)
            items
        }
    }

    fun addOrUpdate(item: DownloadItem, persist: Boolean = true) {
        _downloads.update { current ->
            val mutable = current.toMutableList()
            val index = mutable.indexOfFirst { it.id == item.id }
            if (index >= 0) {
                mutable[index] = item
            } else {
                mutable.add(0, item)
            }
            if (persist) {
                persistDownloads(mutable)
            }
            mutable
        }
    }

    fun updateProgress(
        id: String,
        progress: Float,
        speed: String?,
        eta: String?,
        downloaded: String?,
        total: String?
    ) {
        _progressMap.update { current ->
            current + (id to DownloadProgress(
                progressPercent = progress,
                speedFormatted = speed,
                etaFormatted = eta,
                downloadedSizeFormatted = downloaded,
                totalSizeFormatted = total
            ))
        }
    }

    fun clearProgress(id: String) {
        _progressMap.update { current ->
            current - id
        }
    }

    fun remove(id: String) {
        clearProgress(id)
        _downloads.update { current ->
            val filtered = current.filter { it.id != id }
            persistDownloads(filtered)
            filtered
        }
    }

    fun clearCompleted() {
        _downloads.update { current ->
            val filtered = current.filter { !it.isFinished }
            persistDownloads(filtered)
            filtered
        }
    }
}
