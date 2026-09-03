package courier.data

import courier.model.DownloadItem
import courier.platform.readTextFile
import courier.platform.saveTextFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
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

    // ---------------------------------------------------------------------
    // Writes happen after the state settles, never inside the update lambda.
    //
    // MutableStateFlow.update re-invokes its lambda on CAS contention, so a
    // persist call inside it could run the whole serialise + fd.sync() + backup
    // sequence more than once for a single logical change. updateAndGet returns
    // the committed list, which is then written exactly once.
    // ---------------------------------------------------------------------

    fun saveDownloads(items: List<DownloadItem>) {
        _downloads.value = items
        persistDownloads(items)
    }

    fun addOrUpdate(item: DownloadItem, persist: Boolean = true) {
        val updated = _downloads.updateAndGet { current ->
            val mutable = current.toMutableList()
            val index = mutable.indexOfFirst { it.id == item.id }
            if (index >= 0) {
                mutable[index] = item
            } else {
                mutable.add(0, item)
            }
            mutable
        }
        if (persist) {
            persistDownloads(updated)
        }
    }

    /**
     * Applies [transform] to every item and persists once.
     *
     * For bulk transitions such as Cancel All, which previously called
     * [addOrUpdate] per item and so rewrote the entire history file — with an
     * fsync and a backup copy each time — once per affected download.
     */
    fun updateAll(transform: (DownloadItem) -> DownloadItem) {
        val updated = _downloads.updateAndGet { current -> current.map(transform) }
        persistDownloads(updated)
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
        val updated = _downloads.updateAndGet { current -> current.filter { it.id != id } }
        persistDownloads(updated)
    }

    fun clearCompleted() {
        val updated = _downloads.updateAndGet { current -> current.filter { !it.isFinished } }
        persistDownloads(updated)
    }
}
