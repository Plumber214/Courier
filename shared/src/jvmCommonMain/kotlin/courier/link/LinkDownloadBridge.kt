package courier.link

import courier.engine.FormatSelector
import courier.manager.DownloadManager
import courier.model.DownloadItem
import courier.model.DownloadStatus
import courier.model.VideoFormat
import courier.platform.getPlatformActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The subset of a download's state that is actually sent to the requesting
 * device.
 *
 * Comparing on this rather than on [DownloadItem] is what makes
 * `distinctUntilChanged` useful: the item changes far more often than anything
 * transmitted about it does.
 */
internal data class RemoteStatusSnapshot(
    val status: DownloadStatus,
    val percent: Int,
    val title: String,
    val error: String?,
    val speed: String?,
    val eta: String?
) {
    val isTerminal: Boolean
        get() = status == DownloadStatus.COMPLETED ||
            status == DownloadStatus.FAILED ||
            status == DownloadStatus.CANCELLED
}

internal fun DownloadItem.toRemoteSnapshot() = RemoteStatusSnapshot(
    status = status,
    // Whole percent. The engine reports progress far more finely than a remote
    // progress bar can show, and every extra distinct value is another packet.
    percent = progressPercent.toInt().coerceIn(0, 100),
    title = title,
    error = errorMessage,
    speed = speedFormatted,
    eta = etaFormatted
)

/**
 * The status updates worth sending for one download, ending at its terminal
 * state.
 *
 * Extracted from the bridge so the two properties that were broken can be
 * asserted without a network or a manager: that unrelated downloads produce no
 * traffic for this one, and that the stream completes rather than running for
 * the life of the process.
 */
internal fun remoteStatusStream(
    downloads: Flow<List<DownloadItem>>,
    itemId: String
): Flow<RemoteStatusSnapshot> =
    downloads
        .mapNotNull { list -> list.firstOrNull { it.id == itemId } }
        .map { it.toRemoteSnapshot() }
        .distinctUntilChanged()
        .transformWhile { snapshot ->
            emit(snapshot)
            // Emit the terminal state, then stop collecting.
            !snapshot.isTerminal
        }

class LinkDownloadBridge(
    private val linkManager: DeviceLinkManager,
    private val downloadManager: DownloadManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private var listenerJob: Job? = null
    private val activeRemoteWatchers = ConcurrentHashMap<String, Job>()

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
        listenerJob = null
        activeRemoteWatchers.values.forEach { it.cancel() }
        activeRemoteWatchers.clear()
    }

    private fun handleIncomingRemoteRequest(senderDeviceId: String, req: RemoteDownloadRequest) {
        val senderDevice = linkManager.trustStore.getPairedDevice(senderDeviceId)
        val senderName = senderDevice?.deviceName ?: "Paired Device"

        // The sender's quality choice, resolved against what this device can
        // actually offer. See FormatSelector.presetForRemoteHint for why the
        // hint is normalised rather than applied verbatim.
        val preset = FormatSelector.presetForRemoteHint(req.formatHint)
        val format = preset?.let {
            VideoFormat(formatId = it, qualityLabel = it, resolution = it, ext = "mp4")
        }

        // Enqueue on this receiver device (Decision E2).
        //
        // req.destinationHint is deliberately ignored (PLAN-007 G2). Pairing is
        // consent to accept download requests, not write access to arbitrary
        // paths — the receiving device decides where its own files land. The
        // field stays on the wire format so a v1.6.0 peer still parses.
        val itemId = downloadManager.enqueueDownload(
            url = req.url,
            format = format,
            isAudioOnly = req.audioOnly
        )

        // Notify user of incoming download from paired device
        getPlatformActions().onDownloadStarted("Receiving download from $senderName")

        // Confirm acceptance back to sender
        linkManager.sendDownloadAccepted(senderDeviceId, req.seq, itemId)

        watchAndStream(senderDeviceId, itemId)
    }

    /**
     * Streams this item's status back to [senderDeviceId] until it finishes.
     *
     * Previously this collected the whole downloads list, so one progress tick
     * on any download emitted a packet for this one; and on a terminal status it
     * removed the job from the map without cancelling it, leaving the collector
     * running for the life of the process. Narrowing to the single item,
     * comparing only transmitted fields, and stopping after the terminal
     * emission fixes both.
     */
    private fun watchAndStream(senderDeviceId: String, itemId: String) {
        activeRemoteWatchers.remove(itemId)?.cancel()

        val job = scope.launch {
            try {
                remoteStatusStream(downloadManager.downloads, itemId)
                    .collect { snapshot ->
                        linkManager.sendDownloadStatus(
                            targetDeviceId = senderDeviceId,
                            localItemId = itemId,
                            status = snapshot.status.name,
                            percent = snapshot.percent.toFloat(),
                            title = snapshot.title,
                            error = snapshot.error,
                            speed = snapshot.speed,
                            eta = snapshot.eta
                        )
                    }
            } finally {
                activeRemoteWatchers.remove(itemId)
            }
        }

        activeRemoteWatchers[itemId] = job
    }

    /** Live watcher count. Exists so tests can assert watchers are not leaked. */
    internal fun activeWatcherCount(): Int = activeRemoteWatchers.size
}
