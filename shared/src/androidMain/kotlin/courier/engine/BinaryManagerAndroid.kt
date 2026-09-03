package courier.engine

import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import courier.platform.AppContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class BinaryManagerAndroid : BinaryManager {
    private val _isReady = MutableStateFlow(true)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    override val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadProgress = MutableStateFlow(1f)
    override val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _statusMessage = MutableStateFlow("Embedded Engine Ready")
    override val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // FFmpeg ships inside the APK via youtubedl-android-ffmpeg, so there is no
    // download to fail and nothing to verify.
    private val _isMergerAvailable = MutableStateFlow(true)
    override val isMergerAvailable: StateFlow<Boolean> = _isMergerAvailable.asStateFlow()

    override suspend fun ensureBinariesReady(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (AppContextHolder.isInitialized) {
                YoutubeDL.getInstance().init(AppContextHolder.appContext)
                FFmpeg.getInstance().init(AppContextHolder.appContext)
                _isReady.value = true
                _statusMessage.value = "Engine Ready"
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("AppContext is not initialized yet"))
            }
        } catch (e: Exception) {
            Log.e("Courier", "ensureBinariesReady error", e)
            _errorMessage.value = "Failed to init engine: ${e.message}"
            Result.failure(e)
        }
    }

    override suspend fun updateBinaries(): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (AppContextHolder.isInitialized) {
                val status = YoutubeDL.getInstance().updateYoutubeDL(AppContextHolder.appContext)
                Result.success("Updated yt-dlp: $status")
            } else {
                Result.failure(IllegalStateException("AppContext is not initialized"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getBinaryVersion(): String {
        return try {
            if (AppContextHolder.isInitialized) {
                YoutubeDL.getInstance().version(AppContextHolder.appContext) ?: "Embedded"
            } else {
                "Embedded"
            }
        } catch (e: Exception) {
            "Embedded"
        }
    }
}

actual fun createBinaryManager(): BinaryManager = BinaryManagerAndroid()
