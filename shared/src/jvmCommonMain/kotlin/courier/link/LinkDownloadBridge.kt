package courier.link

import courier.manager.DownloadManager
import courier.model.DownloadStatus
import courier.platform.getPlatformActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LinkDownloadBridge(
    private val linkManager: DeviceLinkManager,
    private val downloadManager: DownloadManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private var listenerJob: Job? = null
    private val activeRemoteWatchers = mutableMapOf<String, Job>()

    fun start() {
        stop()
        listenerJob = scope.launch {
            linkManager.incomingDownloadRequests.collect { (senderDeviceId, req) ->
                handleIncomingRemoteRequest(senderDeviceId, req)
            }
        }
    }

    fun stop() {
        listenerJob?.cancel()
        activeRemoteWatchers.values.forEach { it.cancel() }
        activeRemoteWatchers.clear()
    }

    private fun handleIncomingRemoteRequest(senderDeviceId: String, req: RemoteDownloadRequest) {
        val senderDevice = linkManager.trustStore.getPairedDevice(senderDeviceId)
        val senderName = senderDevice?.deviceName ?: "Paired Device"

        // Enqueue on this receiver device (Decision E2).
        //
        // req.destinationHint is deliberately ignored (PLAN-007 G2). Pairing is
        // consent to accept download requests, not write access to arbitrary
        // paths — the receiving device decides where its own files land. The
        // field stays on the wire format so a v1.6.0 peer still parses.
        val itemId = downloadManager.enqueueDownload(
            url = req.url,
            isAudioOnly = req.audioOnly
        )

        // Notify user of incoming download from paired device
        getPlatformActions().onDownloadStarted("Receiving download from $senderName")

        // Confirm acceptance back to sender
        linkManager.sendDownloadAccepted(senderDeviceId, req.seq, itemId)

        // Watch and stream status updates back to sender
        activeRemoteWatchers[itemId]?.cancel()
        activeRemoteWatchers[itemId] = scope.launch {
            downloadManager.downloads.collectLatest { list ->
                val item = list.firstOrNull { it.id == itemId }
                if (item != null) {
                    val statusStr = item.status.name
                    linkManager.sendDownloadStatus(
                        targetDeviceId = senderDeviceId,
                        localItemId = itemId,
                        status = statusStr,
                        percent = item.progressPercent,
                        title = item.title,
                        error = item.errorMessage,
                        speed = item.speedFormatted,
                        eta = item.etaFormatted
                    )

                    if (item.status == DownloadStatus.COMPLETED || item.status == DownloadStatus.FAILED || item.status == DownloadStatus.CANCELLED) {
                        activeRemoteWatchers.remove(itemId)
                    }
                }
            }
        }
    }
}