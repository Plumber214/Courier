package courier.data

import courier.model.DownloadItem
import courier.platform.readTextFile
import courier.platform.saveTextFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class DownloadRepository {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val historyFileName = "courier_downloads.json"

    private val _downloads = MutableStateFlow<List<DownloadItem>>(loadDownloads())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

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

    fun saveDownloads(items: List<DownloadItem>) {
        _downloads.value = items
        try {
            val content = json.encodeToString(ListSerializer(DownloadItem.serializer()), items)
            saveTextFile(historyFileName, content)
        } catch (e: Exception) {
            println("Failed to save downloads: ")
        }
    }

    fun addOrUpdate(item: DownloadItem) {
        val current = _downloads.value.toMutableList()
        val index = current.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            current[index] = item
        } else {
            current.add(0, item)
        }
        saveDownloads(current)
    }

    fun remove(id: String) {
        val current = _downloads.value.filter { it.id != id }
        saveDownloads(current)
    }

    fun clearCompleted() {
        val current = _downloads.value.filter { !it.isFinished }
        saveDownloads(current)
    }
}
