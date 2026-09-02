package courier.link

import courier.platform.getPlatformActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

sealed interface SendClipboardResult {
    data class Success(val deviceName: String) : SendClipboardResult
    data class DeviceOffline(val deviceName: String) : SendClipboardResult
    data object EmptyClipboard : SendClipboardResult
    data class Error(val message: String) : SendClipboardResult
}

data class ClipboardReceivedEvent(
    val senderDeviceId: String,
    val senderDeviceName: String,
    val previewText: String
)

class ClipboardSyncManager(
    val linkManager: DeviceLinkManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private var incomingJob: Job? = null

    private val _clipboardReceivedEvents = MutableSharedFlow<ClipboardReceivedEvent>(extraBufferCapacity = 16)
    val clipboardReceivedEvents: SharedFlow<ClipboardReceivedEvent> = _clipboardReceivedEvents.asSharedFlow()

    fun start() {
        stop()
        // Listen for incoming explicit clipboard packets from paired devices (F2, Stage 4.5)
        incomingJob = scope.launch {
            linkManager.incomingClipboardEvents.collect { (senderDeviceId, content) ->
                handleIncomingClipboard(senderDeviceId, content)
            }
        }
    }

    fun stop() {
        incomingJob?.cancel()
        incomingJob = null
    }

    /**
     * Sends the current system clipboard text explicitly to [targetDeviceId] (Stage 4.3).
     * No background polling or automatic sends exist.
     */
    fun sendClipboardToDevice(targetDeviceId: String): SendClipboardResult {
        val paired = linkManager.trustStore.getPairedDevice(targetDeviceId)
        val devName = paired?.deviceName ?: "device"

        val clipText = getPlatformActions().getClipboardText()
        if (clipText.isNullOrBlank()) {
            return SendClipboardResult.EmptyClipboard
        }

        val isConnected = linkManager.connectionStates.value[targetDeviceId] == ConnectionStatus.CONNECTED
        if (!isConnected) {
            return SendClipboardResult.DeviceOffline(devName)
        }

        val sent = linkManager.sendClipboard(targetDeviceId, clipText)
        return if (sent) {
            SendClipboardResult.Success(devName)
        } else {
            SendClipboardResult.DeviceOffline(devName)
        }
    }

    private fun handleIncomingClipboard(senderDeviceId: String, content: String) {
        if (content.isBlank()) return
        val sender = linkManager.trustStore.getPairedDevice(senderDeviceId)
        val senderName = sender?.deviceName ?: "Courier device"

        // 1. Apply to local system clipboard immediately (Decision F2 / Stage 4.5)
        getPlatformActions().setClipboardText(content)

        // 2. Emit confirmation event with sender name
        val preview = if (content.length > 40) content.take(37) + "..." else content
        _clipboardReceivedEvents.tryEmit(
            ClipboardReceivedEvent(
                senderDeviceId = senderDeviceId,
                senderDeviceName = senderName,
                previewText = preview
            )
        )
    }
}