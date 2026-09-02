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
    private val highestSeqPerDevice = ConcurrentHashMap<String, Long>()

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
    }

    fun stop() {
        reconnectJob?.cancel()
        linkServer.stop()
        discovery.stop()
        activeLinks.values.forEach { it.close() }
        activeLinks.clear()
        _connectionStates.value = emptyMap()
    }

    fun kickNetwork() {
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
                if (!trustStore.isPaired(peerId)) {
                    println("[SECURITY] Rejecting download request from unpaired device $peerId")
                    return
                }

                val seq = packet.body["seq"]?.jsonPrimitive?.longOrNull ?: 0L
                val url = packet.body["url"]?.jsonPrimitive?.contentOrNull ?: ""
                val formatHint = packet.body["formatHint"]?.jsonPrimitive?.contentOrNull
                val audioOnly = packet.body["audioOnly"]?.jsonPrimitive?.booleanOrNull ?: false
                val destinationHint = packet.body["destinationHint"]?.jsonPrimitive?.contentOrNull

                // Deduplication check
                val highest = highestSeqPerDevice[peerId] ?: 0L
                if (seq <= highest && seq > 0) {
                    // Replay - send ack again and drop duplicate
                    sendAck(link, seq)
                    return
                }
                highestSeqPerDevice[peerId] = seq

                // Send ack
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
                if (!trustStore.isPaired(peerId)) return
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

    private fun startReconnectLoop() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var backoffMs = 2000L
            while (isActive) {
                delay(backoffMs)
                reconnectAllPairedDevices()
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L) // Exponential backoff capped at 30s (§1.5)
            }
        }
    }

    private suspend fun reconnectAllPairedDevices() {
        val paired = trustStore.pairedDevices.value
        val discovered = discovery.discoveredDevices.value

        for (device in paired) {
            val active = activeLinks[device.deviceId]
            if (active != null && active.isConnected) {
                updateConnectionState(device.deviceId, ConnectionStatus.CONNECTED)
                continue
            }

            // Find host address in discovered devices or custom IP
            val host = device.customIp ?: discovered.firstOrNull { it.identity.deviceId == device.deviceId }?.hostAddress
            val port = discovered.firstOrNull { it.identity.deviceId == device.deviceId }?.tcpPort ?: LinkConstants.DEFAULT_PORT

            if (host != null) {
                updateConnectionState(device.deviceId, ConnectionStatus.CONNECTING)
                val result = linkServer.connectOutbound(host, port)
                result.onSuccess { link ->
                    onLinkConnected(link)
                }.onFailure {
                    updateConnectionState(device.deviceId, ConnectionStatus.DISCONNECTED)
                }
            } else {
                updateConnectionState(device.deviceId, ConnectionStatus.DISCONNECTED)
            }
        }
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