package courier.viewmodel

import courier.engine.DownloadEngine
import courier.engine.UrlValidator
import courier.manager.DownloadManager
import courier.model.DownloadItem
import courier.model.MediaType
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
    val showClipboardBanner: Boolean = false,
    val activePreviewItem: DownloadItem? = null,
    val remoteSendMessage: String? = null
)

/**
 * What to tell the user after handing a download to a paired device.
 *
 * Queued is deliberately not phrased as a failure: the outbox is durable and
 * the request survives a restart on both ends. What it must not do is read like
 * the download has started somewhere.
 */
internal fun describeRemoteSend(result: courier.link.SendDownloadResult): String = when (result) {
    is courier.link.SendDownloadResult.Sent ->
        "Sent to ${result.deviceName}"
    is courier.link.SendDownloadResult.Queued ->
        "${result.deviceName} is offline — queued, and will be sent when it reconnects"
    is courier.link.SendDownloadResult.UnknownDevice ->
        "That device is no longer paired, so nothing was sent"
}

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

    /**
     * Handles a link shared into Courier from another app.
     *
     * Goes straight to analysis: the user already expressed intent by choosing
     * Courier from the share sheet, so asking them to confirm a banner as well
     * would be asking twice.
     */
    fun acceptSharedUrl(sharedUrl: String) {
        val clean = UrlValidator.cleanUrl(sharedUrl)
        if (clean.isBlank()) return
        _uiState.value = _uiState.value.copy(
            inputUrl = clean,
            showClipboardBanner = false,
            detectedClipboardUrl = null,
            analysisError = null
        )
        analyzeUrl(clean)
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
            val resolvedUrl = courier.engine.ShareLinkResolver.resolve(clean)
            val cookieBrowser = downloadManager.settings.value.selectedCookieBrowser.let {
                if (it == "None" || it.isBlank()) null else it.lowercase()
            }

            val result = engine.fetchVideoInfo(resolvedUrl, cookieBrowser)
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
                    _uiState.value = _uiState.value.copy(
                        isAnalyzing = false,
                        previewInfo = null,
                        showQualityPicker = false,
                        analysisError = courier.engine.ExtractionError.friendlyMessage(err, resolvedUrl)
                    )
                }
            )
        }
    }

    fun confirmDownload(
        format: VideoFormat?,
        isAudioOnly: Boolean,
        destinationDir: String? = null,
        mediaType: MediaType = _uiState.value.previewInfo?.mediaType ?: (if (isAudioOnly) MediaType.AUDIO else MediaType.VIDEO),
        selectedGalleryIndices: List<Int> = emptyList(),
        downloadPlaylist: Boolean = false
    ) {
        val info = _uiState.value.previewInfo
        val url = info?.url ?: _uiState.value.inputUrl
        if (url.isBlank()) return

        downloadManager.enqueueDownload(
            url = url,
            videoInfo = info,
            format = format,
            isAudioOnly = isAudioOnly,
            destinationDir = destinationDir,
            mediaType = mediaType,
            selectedGalleryIndices = selectedGalleryIndices,
            downloadPlaylist = downloadPlaylist
        )

        _uiState.value = _uiState.value.copy(
            inputUrl = "",
            previewInfo = null,
            showQualityPicker = false,
            analysisError = null
        )
    }

    fun sendToRemoteDevice(
        targetDeviceId: String,
        format: VideoFormat?,
        isAudioOnly: Boolean
    ) {
        val info = _uiState.value.previewInfo
        val url = info?.url ?: _uiState.value.inputUrl
        if (url.isBlank()) return

        scope.launch {
            val result = courier.di.AppModule.deviceLinkManager.sendDownloadRequest(
                targetDeviceId = targetDeviceId,
                url = url,
                // Resolution first, format id only as a fallback. The id came
                // from this device's own yt-dlp run and may name nothing on the
                // other device; the resolution is the part that travels.
                formatHint = format?.resolution ?: format?.formatId,
                audioOnly = isAudioOnly
            )

            _uiState.value = _uiState.value.copy(
                remoteSendMessage = describeRemoteSend(result)
            )
        }

        _uiState.value = _uiState.value.copy(
            inputUrl = "",
            previewInfo = null,
            showQualityPicker = false,
            analysisError = null
        )
    }

    fun clearRemoteSendMessage() {
        _uiState.value = _uiState.value.copy(remoteSendMessage = null)
    }

    fun dismissQualityPicker() {
        _uiState.value = _uiState.value.copy(
            showQualityPicker = false,
            previewInfo = null
        )
    }

    fun openMediaPreview(item: DownloadItem) {
        _uiState.value = _uiState.value.copy(
            activePreviewItem = item
        )
    }

    fun dismissMediaPreview() {
        _uiState.value = _uiState.value.copy(
            activePreviewItem = null
        )
    }
}
