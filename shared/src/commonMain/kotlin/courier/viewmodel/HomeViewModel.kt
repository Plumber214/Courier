package courier.viewmodel

import courier.engine.DownloadEngine
import courier.engine.UrlValidator
import courier.manager.DownloadManager
import courier.model.Platform
import courier.model.VideoFormat
import courier.model.VideoInfo
import courier.platform.getPlatformActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val inputUrl: String = "",
    val isAnalyzing: Boolean = false,
    val analysisError: String? = null,
    val previewInfo: VideoInfo? = null,
    val showQualityPicker: Boolean = false,
    val detectedClipboardUrl: String? = null,
    val showClipboardBanner: Boolean = false
)

class HomeViewModel(
    private val downloadManager: DownloadManager,
    private val engine: DownloadEngine,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var lastDismissedClipboardUrl: String? = null

    fun onUrlChanged(newUrl: String) {
        _uiState.value = _uiState.value.copy(
            inputUrl = newUrl,
            analysisError = null
        )
    }

    fun pasteFromClipboard() {
        val clipText = getPlatformActions().getClipboardText()
        if (!clipText.isNullOrBlank()) {
            val clean = UrlValidator.cleanUrl(clipText)
            onUrlChanged(clean)
        }
    }

    fun clearUrl() {
        _uiState.value = _uiState.value.copy(
            inputUrl = "",
            analysisError = null,
            previewInfo = null,
            showQualityPicker = false
        )
    }

    fun checkClipboardForVideoUrl() {
        val clipText = getPlatformActions().getClipboardText() ?: return
        val clean = UrlValidator.cleanUrl(clipText)
        if (clean == lastDismissedClipboardUrl || clean == _uiState.value.inputUrl) {
            return
        }

        if (UrlValidator.isSupportedVideoUrl(clean)) {
            _uiState.value = _uiState.value.copy(
                detectedClipboardUrl = clean,
                showClipboardBanner = true
            )
        }
    }

    fun acceptClipboardUrl() {
        val detected = _uiState.value.detectedClipboardUrl ?: return
        _uiState.value = _uiState.value.copy(
            inputUrl = detected,
            showClipboardBanner = false,
            detectedClipboardUrl = null
        )
        analyzeUrl(detected)
    }

    fun dismissClipboardBanner() {
        lastDismissedClipboardUrl = _uiState.value.detectedClipboardUrl
        _uiState.value = _uiState.value.copy(
            showClipboardBanner = false,
            detectedClipboardUrl = null
        )
    }

    fun analyzeUrl(overrideUrl: String? = null) {
        val targetUrl = overrideUrl ?: _uiState.value.inputUrl
        val clean = UrlValidator.cleanUrl(targetUrl)
        if (clean.isBlank()) return

        _uiState.value = _uiState.value.copy(
            inputUrl = clean,
            isAnalyzing = true,
            analysisError = null,
            previewInfo = null,
            showQualityPicker = false
        )

        scope.launch {
            val cookieBrowser = downloadManager.settings.value.selectedCookieBrowser.let {
                if (it == "None" || it.isBlank()) null else it.lowercase()
            }

            val result = engine.fetchVideoInfo(clean, cookieBrowser)
            result.fold(
                onSuccess = { info ->
                    _uiState.value = _uiState.value.copy(
                        isAnalyzing = false,
                        previewInfo = info,
                        showQualityPicker = true,
                        analysisError = null
                    )
                },
                onFailure = { err ->
                    val fallbackInfo = VideoInfo(
                        id = "vid_${clean.hashCode()}",
                        url = clean,
                        title = "${Platform.fromUrl(clean).displayName} Video",
                        platform = Platform.fromUrl(clean),
                        formats = listOf(
                            VideoFormat("best", "Best Available Quality", resolution = "Highest", ext = "mp4"),
                            VideoFormat("1080p", "1080p Full HD", resolution = "1080p", ext = "mp4"),
                            VideoFormat("720p", "720p HD", resolution = "720p", ext = "mp4"),
                            VideoFormat("480p", "480p SD", resolution = "480p", ext = "mp4"),
                            VideoFormat("360p", "360p Standard", resolution = "360p", ext = "mp4"),
                            VideoFormat("bestaudio", "Best Audio Quality (M4A)", ext = "m4a", isAudioOnly = true),
                            VideoFormat("mp3", "MP3 Audio (Converted 320kbps)", ext = "mp3", isAudioOnly = true)
                        )
                    )
                    _uiState.value = _uiState.value.copy(
                        isAnalyzing = false,
                        previewInfo = fallbackInfo,
                        showQualityPicker = true,
                        analysisError = null
                    )
                }
            )
        }
    }

    fun confirmDownload(format: VideoFormat?, isAudioOnly: Boolean, destinationDir: String? = null) {
        val info = _uiState.value.previewInfo
        val url = info?.url ?: _uiState.value.inputUrl
        if (url.isBlank()) return

        downloadManager.enqueueDownload(
            url = url,
            videoInfo = info,
            format = format,
            isAudioOnly = isAudioOnly,
            destinationDir = destinationDir
        )

        _uiState.value = _uiState.value.copy(
            inputUrl = "",
            previewInfo = null,
            showQualityPicker = false,
            analysisError = null
        )
    }

    fun dismissQualityPicker() {
        _uiState.value = _uiState.value.copy(
            showQualityPicker = false,
            previewInfo = null
        )
    }
}
