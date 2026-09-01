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
        val primaryContent = readTextFile(historyFileName)
        if (!primaryContent.isNullOrBlank()) {
            try {
                return json.decodeFromString(ListSerializer(DownloadItem.serializer()), primaryContent)
            } catch (e: Exception) {
                println("Failed to decode primary downloads JSON: ${e.message}, attempting backup recovery")
            }
        }
        val backupContent = readTextFile("$historyFileName.bak")
        if (!backupContent.isNullOrBlank()) {
            try {
                return json.decodeFromString(ListSerializer(DownloadItem.serializer()), backupContent)
            } catch (_: Exception) {}
        }
        return emptyList()
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
            val prev = current[id]
            current + (id to DownloadProgress(
                progressPercent = progress,
                speedFormatted = speed ?: prev?.speedFormatted,
                etaFormatted = eta ?: prev?.etaFormatted,
                downloadedSizeFormatted = downloaded ?: prev?.downloadedSizeFormatted,
                totalSizeFormatted = total ?: prev?.totalSizeFormatted
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
