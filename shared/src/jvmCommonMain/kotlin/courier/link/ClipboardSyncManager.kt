package courier.link

import courier.platform.getPlatformActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.MessageDigest

class ClipboardSyncManager(
    private val linkManager: DeviceLinkManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private var lastLocalClipHash: String? = null
    private var lastReceivedClipHash: String? = null

    private var incomingJob: Job? = null
    private var desktopPollJob: Job? = null

    fun start() {
        stop()

        // 1. Listen for incoming clipboard packets from paired devices
        incomingJob = scope.launch {
            linkManager.incomingClipboardEvents.collect { (senderDeviceId, content) ->
                handleIncomingClipboard(senderDeviceId, content)
            }
        }

        // 2. On Desktop, continuously poll system clipboard (Decision E6 / §1.7)
        if (!getPlatformActions().isAndroid()) {
            desktopPollJob = scope.launch {
                while (isActive) {
                    delay(1500L) // Poll every 1.5s on desktop
                    pollAndSyncClipboard()
                }
            }
        }
    }

    fun stop() {
        incomingJob?.cancel()
        desktopPollJob?.cancel()
    }

    /**
     * Called when the app is foregrounded on Android or when the user manually pushes clipboard.
     */
    fun pushClipboardToPairedDevices() {
        val clipText = getPlatformActions().getClipboardText() ?: return
        if (clipText.isBlank()) return

        val hash = computeHash(clipText)
        lastLocalClipHash = hash

        val paired = linkManager.trustStore.pairedDevices.value
        for (device in paired) {
            if (device.isClipboardSyncEnabled) {
                linkManager.sendClipboard(device.deviceId, clipText)
            }
        }
    }

    private fun pollAndSyncClipboard() {
        val clipText = getPlatformActions().getClipboardText() ?: return
        if (clipText.isBlank()) return

        val hash = computeHash(clipText)
        if (hash == lastLocalClipHash || hash == lastReceivedClipHash) {
            return // No change or originated from remote sync
        }

        lastLocalClipHash = hash

        val paired = linkManager.trustStore.pairedDevices.value
        for (device in paired) {
            if (device.isClipboardSyncEnabled) {
                linkManager.sendClipboard(device.deviceId, clipText)
            }
        }
    }

    private fun handleIncomingClipboard(senderDeviceId: String, content: String) {
        if (content.isBlank()) return

        val hash = computeHash(content)
        if (hash == lastReceivedClipHash || hash == lastLocalClipHash) {
            return // Deduplicate loop
        }

        lastReceivedClipHash = hash
        getPlatformActions().setClipboardText(content)
    }

    private fun computeHash(text: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}