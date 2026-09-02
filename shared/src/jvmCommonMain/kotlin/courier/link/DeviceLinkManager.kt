package courier.link

import courier.platform.getPlatformActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

@kotlinx.serialization.Serializable
data class RemoteDownloadRequest(
    val url: String,
    val seq: Long,
    val formatHint: String? = null,
    val audioOnly: Boolean = false,
    val destinationHint: String? = null
)

@kotlinx.serialization.Serializable
data class RemoteDownloadStatus(
    val localItemId: String,
    val status: String,
    val percent: Float,
    val title: String,
    val error: String? = null,
    val speed: String? = null,
    val eta: String? = null
)

class DeviceLinkManager(
    val certStore: CertificateStore = CertificateStore(),
    val trustStore: TrustStore = TrustStore(),
    val outbox: Outbox = Outbox(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val json = Json { ignoreUnknownKeys = true }

    val myIdentity: DeviceIdentity

    init {
        val platformActions = getPlatformActions()
        val isAndroid = platformActions.isAndroid()
        val defaultName = if (isAndroid) "Courier on Android" else "Courier on Desktop"
        val devType = if (isAndroid) "phone" else "desktop"
        myIdentity = DeviceIdentity(
            deviceId = certStore.deviceId,
            deviceName = defaultName,
            deviceType = devType
        )
    }

    val pairingManager = PairingManager(myIdentity, certStore, trustStore, scope)
    val discovery = Discovery(myIdentity, scope)

    private val activeLinks = ConcurrentHashMap<String, SecureLink>()

    /** Persisted so replays survive a restart of either side. */
    private val replayGuard = ReplayGuard()

    // Per-device reconnect backoff. Global backoff cannot express "this device
    // is unreachable but that one just came back".
    private val reconnectBackoffMs = ConcurrentHashMap<String, Long>()
    private val nextAttemptAtMs = ConcurrentHashMap<String, Long>()

    private val networkMonitor = createNetworkChangeMonitor()

    private val _connectionStates = MutableStateFlow<Map<String, ConnectionStatus>>(emptyMap())
    val connectionStates: StateFlow<Map<String, ConnectionStatus>> = _connectionStates.asStateFlow()

    private val _incomingDownloadRequests = MutableSharedFlow<Pair<String, RemoteDownloadRequest>>(extraBufferCapacity = 32)
    val incomingDownloadRequests: SharedFlow<Pair<String, RemoteDownloadRequest>> = _incomingDownloadRequests.asSharedFlow()

    private val _incomingClipboardEvents = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 32)
    val incomingClipboardEvents: SharedFlow<Pair<String, String>> = _incomingClipboardEvents.asSharedFlow()

    private val _remoteDownloadStatuses = MutableStateFlow<Map<String, RemoteDownloadStatus>>(emptyMap())
    val remoteDownloadStatuses: StateFlow<Map<String, RemoteDownloadStatus>> = _remoteDownloadStatuses.asStateFlow()

    private var reconnectJob: Job? = null

    val linkServer = LinkServer(
        myIdentity = myIdentity,
        certStore = certStore,
        trustStore = trustStore,
        onLinkEstablished = ::onLinkConnected,
        scope = scope
    )

    fun start() {
        linkServer.start()
        discovery.start()
        startReconnectLoop()
        // Backoff alone makes recovery after a Wi-Fi bounce feel broken; this
        // is what makes it feel instant.
        networkMonitor.start { kickNetwork() }
    }

    fun stop() {
        networkMonitor.stop()
        reconnectJob?.cancel()
        linkServer.stop()
        discovery.stop()
        activeLinks.values.forEach { it.close() }
        activeLinks.clear()
        reconnectBackoffMs.clear()
        nextAttemptAtMs.clear()
        _connectionStates.value = emptyMap()
    }

    /**
     * Re-announces and retries every paired device immediately.
     *
     * Clearing the backoff state is the point: without it a device already
     * backed off to 30 s would ignore the kick and keep waiting.
     */
    fun kickNetwork() {
        reconnectBackoffMs.clear()
        nextAttemptAtMs.clear()
        discovery.broadcastNow()
        scope.launch {
            reconnectAllPairedDevices()
        }
    }

    private fun onLinkConnected(link: SecureLink) {
        val peerId = link.peerDeviceId
        val existing = activeLinks[peerId]
        existing?.close()
        activeLinks[peerId] = link

        updateConnectionState(peerId, ConnectionStatus.CONNECTED)

        // Listen for incoming packets
        scope.launch {
            link.incomingPackets.collect { packet ->
                handleIncomingPacket(link, packet)
            }
        }

        // Flush pending outbox packets
        scope.launch {
            flushOutboxForDevice(link)
        }
    }

    private suspend fun handleIncomingPacket(link: SecureLink, packet: LinkPacket) {
        val peerId = link.peerDeviceId

        // An unpaired peer completes the TLS handshake — it has to, or pairing
        // could never happen — so authorisation is enforced here instead.
        // Only pairing and liveness are reachable before pairing completes;
        // everything else, status updates included, requires a paired peer.
        // Without this an unpaired device on the LAN can inject fake download
        // progress into the UI.
        if (!trustStore.isPaired(peerId) &&
            packet.type != LinkConstants.TYPE_PAIR &&
            packet.type != LinkConstants.TYPE_PING
        ) {
            println("[SECURITY] Rejecting ${packet.type} from unpaired device $peerId")
            return
        }

        when (packet.type) {
            LinkConstants.TYPE_PING -> {
                // Heartbeat received
                updateConnectionState(peerId, ConnectionStatus.CONNECTED)
            }

            LinkConstants.TYPE_PAIR -> {
                pairingManager.handleIncomingPairPacket(link, packet)
            }

            LinkConstants.TYPE_ACK -> {
                val ackSeq = packet.body["ackSeq"]?.jsonPrimitive?.longOrNull
                if (ackSeq != null) {
                    outbox.acknowledge(peerId, ackSeq)
                }
            }

            LinkConstants.TYPE_DOWNLOAD_REQUEST -> {
                val seq = packet.body["seq"]?.jsonPrimitive?.longOrNull ?: 0L
                val url = packet.body["url"]?.jsonPrimitive?.contentOrNull ?: ""
                val formatHint = packet.body["formatHint"]?.jsonPrimitive?.contentOrNull
                val audioOnly = packet.body["audioOnly"]?.jsonPrimitive?.booleanOrNull ?: false
                val destinationHint = packet.body["destinationHint"]?.jsonPrimitive?.contentOrNull

                // Deduplication. Re-ack the replay so the sender can retire the
                // outbox entry whose ack was what went missing.
                if (replayGuard.isReplay(peerId, seq)) {
                    sendAck(link, seq)
                    return
                }
                replayGuard.record(peerId, seq)

                sendAck(link, seq)

                if (url.isNotBlank()) {
                    val req = RemoteDownloadRequest(
                        url = url,
                        seq = seq,
                        formatHint = formatHint,
                        audioOnly = audioOnly,
                        destinationHint = destinationHint
                    )
                    _incomingDownloadRequests.tryEmit(peerId to req)
                }
            }

            LinkConstants.TYPE_DOWNLOAD_ACCEPTED -> {
                val seq = packet.body["seq"]?.jsonPrimitive?.longOrNull
                if (seq != null) {
                    outbox.acknowledge(peerId, seq)
                }
            }

            LinkConstants.TYPE_DOWNLOAD_STATUS -> {
                val localItemId = packet.body["localItemId"]?.jsonPrimitive?.contentOrNull ?: return
                val status = packet.body["status"]?.jsonPrimitive?.contentOrNull ?: "DOWNLOADING"
                val percent = packet.body["percent"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
                val title = packet.body["title"]?.jsonPrimitive?.contentOrNull ?: ""
                val error = packet.body["error"]?.jsonPrimitive?.contentOrNull
                val speed = packet.body["speed"]?.jsonPrimitive?.contentOrNull
                val eta = packet.body["eta"]?.jsonPrimitive?.contentOrNull

                val current = _remoteDownloadStatuses.value.toMutableMap()
                current[localItemId] = RemoteDownloadStatus(
                    localItemId = localItemId,
                    status = status,
                    percent = percent,
                    title = title,
                    error = error,
                    speed = speed,
                    eta = eta
                )
                _remoteDownloadStatuses.value = current
            }

            LinkConstants.TYPE_CLIPBOARD -> {
                val paired = trustStore.getPairedDevice(peerId)
                if (paired?.isClipboardSyncEnabled == true) {
                    val content = packet.body["content"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (content.isNotBlank()) {
                        _incomingClipboardEvents.tryEmit(peerId to content)
                    }
                }
            }
        }
    }

    private fun sendAck(link: SecureLink, seq: Long) {
        link.sendPacket(
            LinkPacket(
                type = LinkConstants.TYPE_ACK,
                body = buildJsonObject {
                    put("ackSeq", seq)
                }
            )
        )
    }

    suspend fun sendDownloadRequest(
        targetDeviceId: String,
        url: String,
        formatHint: String? = null,
        audioOnly: Boolean = false,
        destinationHint: String? = null
    ): Long {
        val packet = LinkPacket(
            type = LinkConstants.TYPE_DOWNLOAD_REQUEST,
            body = buildJsonObject {
                put("url", url)
                put("formatHint", formatHint)
                put("audioOnly", audioOnly)
                put("destinationHint", destinationHint)
            }
        )

        // Persist in outbox before UI confirmation (§1.5)
        val seq = outbox.enqueue(targetDeviceId, packet)

        // If link is connected, send immediately
        val link = activeLinks[targetDeviceId]
        if (link != null && link.isConnected) {
            val packetWithSeq = packet.copy(
                body = buildJsonObject {
                    packet.body.forEach { (k, v) -> put(k, v) }
                    put("seq", seq)
                }
            )
            link.sendPacket(packetWithSeq)
            outbox.markAttempt(seq)
        }

        return seq
    }

    fun sendDownloadAccepted(targetDeviceId: String, seq: Long, localItemId: String) {
        val link = activeLinks[targetDeviceId] ?: return
        link.sendPacket(
            LinkPacket(
                type = LinkConstants.TYPE_DOWNLOAD_ACCEPTED,
                body = buildJsonObject {
                    put("seq", seq)
                    put("localItemId", localItemId)
                }
            )
        )
    }

    fun sendDownloadStatus(
        targetDeviceId: String,
        localItemId: String,
        status: String,
        percent: Float,
        title: String,
        error: String? = null,
        speed: String? = null,
        eta: String? = null
    ) {
        val link = activeLinks[targetDeviceId] ?: return
        link.sendPacket(
            LinkPacket(
                type = LinkConstants.TYPE_DOWNLOAD_STATUS,
                body = buildJsonObject {
                    put("localItemId", localItemId)
                    put("status", status)
                    put("percent", percent)
                    put("title", title)
                    put("error", error)
                    put("speed", speed)
                    put("eta", eta)
                }
            )
        )
    }

    fun sendClipboard(targetDeviceId: String, content: String) {
        val paired = trustStore.getPairedDevice(targetDeviceId)
        if (paired?.isClipboardSyncEnabled != true) return

        val link = activeLinks[targetDeviceId] ?: return
        link.sendPacket(
            LinkPacket(
                type = LinkConstants.TYPE_CLIPBOARD,
                body = buildJsonObject {
                    put("content", content)
                    put("timestamp", System.currentTimeMillis())
                }
            )
        )
    }

    /**
     * Unpairs a device and drops every trace of it.
     *
     * The replay mark has to go with it: a later re-pair starts a fresh
     * sequence, and a stale high-water mark would silently swallow its first
     * requests as duplicates.
     */
    fun unpair(deviceId: String) {
        val link = activeLinks.remove(deviceId)
        pairingManager.unpair(deviceId, link)
        link?.close()
        replayGuard.forget(deviceId)
        reconnectBackoffMs.remove(deviceId)
        nextAttemptAtMs.remove(deviceId)
        updateConnectionState(deviceId, ConnectionStatus.DISCONNECTED)
    }

    suspend fun connectToManualIp(host: String, port: Int = LinkConstants.DEFAULT_PORT): Result<SecureLink> {
        val result = linkServer.connectOutbound(host, port)
        result.onSuccess { link ->
            onLinkConnected(link)
        }
        return result
    }

    private suspend fun flushOutboxForDevice(link: SecureLink) {
        val pending = outbox.getPendingForDevice(link.peerDeviceId)
        for (item in pending) {
            val packetWithSeq = item.packet.copy(
                body = buildJsonObject {
                    item.packet.body.forEach { (k, v) -> put(k, v) }
                    put("seq", item.seq)
                }
            )
            val sent = link.sendPacket(packetWithSeq)
            if (sent) {
                outbox.markAttempt(item.seq)
            }
        }
    }

    /**
     * Ticks frequently and cheaply; [nextAttemptAtMs] decides which devices are
     * actually retried.
     *
     * The previous version delayed by a single global backoff that only ever
     * doubled and was never reset on success, so after about a minute of uptime
     * every retry sat 30 s apart forever — including immediately after a
     * successful connect. Backoff belongs per device, and has to reset.
     */
    private fun startReconnectLoop() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            while (isActive) {
                delay(LinkConstants.RECONNECT_TICK_MS)
                reconnectAllPairedDevices()
            }
        }
    }

    private suspend fun reconnectAllPairedDevices() {
        val paired = trustStore.pairedDevices.value
        val discovered = discovery.discoveredDevices.value
        val now = System.currentTimeMillis()

        for (device in paired) {
            val id = device.deviceId
            val active = activeLinks[id]
            if (active != null && active.isConnected) {
                // Connected: clear any backoff so the next drop retries promptly.
                reconnectBackoffMs.remove(id)
                nextAttemptAtMs.remove(id)
                updateConnectionState(id, ConnectionStatus.CONNECTED)
                continue
            }

            if (now < (nextAttemptAtMs[id] ?: 0L)) continue

            // Find host address in discovered devices or custom IP
            val host = device.customIp ?: discovered.firstOrNull { it.identity.deviceId == id }?.hostAddress
            val port = discovered.firstOrNull { it.identity.deviceId == id }?.tcpPort ?: LinkConstants.DEFAULT_PORT

            if (host == null) {
                updateConnectionState(id, ConnectionStatus.DISCONNECTED)
                scheduleRetry(id)
                continue
            }

            updateConnectionState(id, ConnectionStatus.CONNECTING)
            linkServer.connectOutbound(host, port)
                .onSuccess { link ->
                    reconnectBackoffMs.remove(id)
                    nextAttemptAtMs.remove(id)
                    onLinkConnected(link)
                }
                .onFailure {
                    updateConnectionState(id, ConnectionStatus.DISCONNECTED)
                    scheduleRetry(id)
                }
        }
    }

    /** Doubles this device's backoff toward the cap and sets its next eligible attempt. */
    private fun scheduleRetry(deviceId: String) {
        val next = nextBackoffMs(reconnectBackoffMs[deviceId] ?: 0L)
        reconnectBackoffMs[deviceId] = next
        nextAttemptAtMs[deviceId] = System.currentTimeMillis() + next
    }

    private fun updateConnectionState(deviceId: String, status: ConnectionStatus) {
        val map = _connectionStates.value.toMutableMap()
        map[deviceId] = status
        _connectionStates.value = map
    }

    companion object {
        @Volatile
        private var instance: DeviceLinkManager? = null

        fun getInstance(): DeviceLinkManager {
            return instance ?: synchronized(this) {
                instance ?: DeviceLinkManager().also { instance = it }
            }
        }
    }
}