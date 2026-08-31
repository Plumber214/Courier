package courier.engine

import kotlinx.coroutines.flow.StateFlow

interface BinaryManager {
    val isReady: StateFlow<Boolean>
    val isDownloading: StateFlow<Boolean>
    val downloadProgress: StateFlow<Float>
    val statusMessage: StateFlow<String>
    val errorMessage: StateFlow<String?>

    suspend fun ensureBinariesReady(): Result<Unit>
    suspend fun updateBinaries(): Result<String>
    fun getBinaryVersion(): String
}

expect fun createBinaryManager(): BinaryManager
