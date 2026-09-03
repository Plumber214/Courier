package courier.link

import courier.platform.getPlatformActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

/**
 * What actually happened to a download handed to a paired device.
 *
 * The request is written to the outbox either way, so "queued" is a real
 * outcome and not a failure — but it is a different one from "sent", and the
 * caller previously received only a sequence number and could not tell them
 * apart.
 */
sealed class SendDownloadResult {
    /** Written to the outbox and pushed over a live link. */
    data class Sent(val seq: Long, val deviceName: String) : SendDownloadResult()

    /** Written to the outbox; the device is not connected right now. */
    data class Queued(val seq: Long, val deviceName: String) : SendDownloadResult()

    /** No such pairing — the device was unpaired between opening the picker and sending. */
    data class UnknownDevice(val deviceId: String) : SendDownloadResult()
}

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
    val tcpPort: Int = LinkConstants.DEFAULT_PORT,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _myIdentity: MutableStateFlow<DeviceIdentity> = run {
        val platformActions = getPlatformActions()
        val isAndroid = platformActions.isAndroid()
        val devName = certStore.getDeviceName()
        val devType = if (isAndroid) "phone" else "desktop"
        MutableStateFlow(
            DeviceIdentity(
                deviceId = certStore.deviceId,
                deviceName = devName,
                deviceType = devType,
                tcpPort = tcpPort
            )
        )
    }
    val myIdentity: StateFlow<DeviceIdentity> = _myIdentity.asStateFlow()

    private val _isDevicesTabActive = MutableStateFlow(false)
    val isDevicesTabActive: StateFlow<Boolean> = _isDevicesTabActive.asStateFlow()

    private var isSubsystemRunning = false
    private val reconnectSignal = Channel<Unit>(Channel.CONFLATED)

    val pairingManager = PairingManager({ _myIdentity.value }, certStore, trustStore, scope)
    val discovery = Discovery({ _myIdentity.value }, scope)

    private val activeLinks = ConcurrentHashMap<String, SecureLink>()

    /** Persisted so replays survive a restart of either side. */
    private val replayGuard = ReplayGuard()

    // Per-device reconnect backoff. Global backoff cannot express "this device
    // is unreachable but that one just came back".
    private val reconnectBackoffMs = ConcurrentHashMap<String, Long>()
    private val nextAttemptAtMs = ConcurrentHashMap<String, Long>()

    // Last-seen, held in memory and written through rarely.
    //
    // It used to be persisted on every received packet, and TrustStore.save is
    // a durable three-file operation: serialise the whole paired list, write a
    // temp file, fd.sync(), copy the previous file to .bak, atomically move.
    // With status packets streaming during a remote download that was several
    // fsync'd writes per second, for a timestamp that is only ever displayed
    // while a device is offline.
    private val lastSeenMs = ConcurrentHashMap<String, Long>()
    private val lastPersistedSeenMs = ConcurrentHashMap<String, Long>()

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
        identityProvider = { _myIdentity.value },
        certStore = certStore,
        trustStore = trustStore,
        onLinkEstablished = ::onLinkConnected,
        scope = scope
    )

    init {
        // Lifecycle manager: Gate the subsystem on having a paired device or active tab (Decision F1)
        scope.launch {
            combine(trustStore.pairedDevices, _isDevicesTabActive) { paired, tabActive ->
                paired.isNotEmpty() || tabActive
            }.collect { shouldRun ->
                if (shouldRun && !isSubsystemRunning) {
                    startInternal()
                } else if (!shouldRun && isSubsystemRunning) {
                    stopInternal()
                }
            }
        }

        // Notify platform of link state (for Android foreground service binding, F4)
        scope.launch {
            combine(trustStore.pairedDevices, _connectionStates) { paired, states ->
                val connectedCount = paired.count { states[it.deviceId] == ConnectionStatus.CONNECTED }
                val primary = paired.firstOrNull()
                val primaryStatus = primary?.let { states[it.deviceId]?.name ?: "DISCONNECTED" }
                getPlatformActions().onDeviceLinkStateChanged(
                    pairedCount = paired.size,
                    connectedCount = connectedCount,
                    primaryDeviceName = primary?.deviceName,
                    primaryDeviceStatus = primaryStatus
                )
            }.collect {}
        }
    }

    fun setDevicesTabActive(active: Boolean) {
        _isDevicesTabActive.value = active
        if (active) {
            startInternal()
            discovery.startActiveAnnouncing(1500L)
        } else {
            discovery.stopActiveAnnouncing()
            if (trustStore.pairedDevices.value.isEmpty()) {
                stopInternal()
            }
        }
    }

    fun start() {
        // Explicit start request: wake if dormant
        if (trustStore.pairedDevices.value.isNotEmpty() || _isDevicesTabActive.value) {
            startInternal()
        }
    }

    private fun startInternal() {
        if (isSubsystemRunning) return
        isSubsystemRunning = true
        linkServer.start(tcpPort)
        discovery.startUdpListener()
        startReconnectLoop()
        networkMonitor.start { kickNetwork() }
        reconnectSignal.trySend(Unit)
    }

    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        if (!isSubsystemRunning) return
        isSubsystemRunning = false
        networkMonitor.stop()
        reconnectJob?.cancel()
        reconnectJob = null
        linkServer.stop()
        discovery.stop()
        // Write through before tearing the links down, or the in-memory
        // last-seen for a device that was connected right up to this point is
        // lost and the card falls back to a stale timestamp.
        activeLinks.keys.forEach { flushLastSeen(it) }
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
        reconnectSignal.trySend(Unit)
    }

    /**
     * Records that [deviceId] was heard from, writing through to disk at most
     * once per [LinkConstants.LAST_SEEN_PERSIST_INTERVAL_MS].
     */
    private fun touchLastSeen(deviceId: String) {
        val now = System.currentTimeMillis()
        lastSeenMs[deviceId] = now
        val lastPersisted = lastPersistedSeenMs[deviceId] ?: 0L
        if (now - lastPersisted >= LinkConstants.LAST_SEEN_PERSIST_INTERVAL_MS) {
            lastPersistedSeenMs[deviceId] = now
            trustStore.updateLastSeen(deviceId, now)
        }
    }

    /**
     * Writes the in-memory last-seen for [deviceId] through to disk.
     *
     * Called when a link drops and when the subsystem stops — the moments the
     * value actually becomes visible, since the UI only shows it while a device
     * is not connected.
     */
    private fun flushLastSeen(deviceId: String) {
        val seen = lastSeenMs[deviceId] ?: return
        if (lastPersistedSeenMs[deviceId] == seen) return
        lastPersistedSeenMs[deviceId] = seen
        trustStore.updateLastSeen(deviceId, seen)
    }

    private fun onLinkConnected(link: SecureLink) {
        val peerId = link.peerDeviceId
        val existing = activeLinks[peerId]
        existing?.close()
        activeLinks[peerId] = link

        touchLastSeen(peerId)
        updateConnectionState(peerId, ConnectionStatus.CONNECTED)

        // Listen for incoming packets.
        val packetJob = scope.launch {
            link.incomingPackets.collect { packet ->
                touchLastSeen(peerId)
                handleIncomingPacket(link, packet)
            }
        }

        // Clean up when the link drops.
        //
        // This used to be a `finally` on the collect above — which never ran.
        // incomingPackets is a SharedFlow, and collecting one never completes,
        // so a closed socket left the collector suspended forever: the dead
        // link stayed in activeLinks, the ASLEEP transition never fired from
        // here, and one coroutine leaked per connection. awaitClosed is the
        // signal that actually arrives.
        scope.launch {
            link.awaitClosed()
            packetJob.cancel()
            if (activeLinks[peerId] == link) {
                activeLinks.remove(peerId)
                lastSeenMs[peerId] = System.currentTimeMillis()
                flushLastSeen(peerId)
                updateConnectionState(peerId, ConnectionStatus.ASLEEP)
                reconnectSignal.trySend(Unit)
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
        if (!trustStore.isPaired(peerId) &&
            packet.type != LinkConstants.TYPE_PAIR &&
            packet.type != LinkConstants.TYPE_PING
        ) {
            println("[SECURITY] Rejecting ${packet.type} from unpaired device $peerId")
            return
        }

        when (packet.type) {
            LinkConstants.TYPE_PING -> {
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

                // Deduplication
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

            LinkConstants.TYPE_IDENTITY_UPDATE -> {
                val newName = packet.body["deviceName"]?.jsonPrimitive?.contentOrNull
                if (!newName.isNullOrBlank()) {
                    trustStore.updateDeviceName(peerId, newName)
                }
            }

            LinkConstants.TYPE_CLIPBOARD -> {
                if (trustStore.isPaired(peerId)) {
                    val content = packet.body["content"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (content.isNotBlank()) {
                        _incomingClipboardEvents.tryEmit(peerId to content)
                    }
                }
            }
        }
    }

    fun updateDeviceName(newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        certStore.setDeviceName(trimmed)
        _myIdentity.value = _myIdentity.value.copy(deviceName = trimmed)

        // Propagate rename to all active connected peers over TLS (Stage 1.4)
        val renamePacket = LinkPacket(
            type = LinkConstants.TYPE_IDENTITY_UPDATE,
            body = buildJsonObject {
                put("deviceName", trimmed)
            }
        )
        activeLinks.values.forEach { link ->
            link.sendPacket(renamePacket)
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
    ): SendDownloadResult {
        val paired = trustStore.pairedDevices.value.firstOrNull { it.deviceId == targetDeviceId }
            ?: return SendDownloadResult.UnknownDevice(targetDeviceId)

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
                    for ((k, v) in packet.body) {
                        put(k, v)
                    }
                    put("seq", seq)
                }
            )
            link.sendPacket(packetWithSeq)
            outbox.markAttempt(seq)
            return SendDownloadResult.Sent(seq, paired.deviceName)
        }

        // Still enqueued — the outbox delivers it on reconnect.
        return SendDownloadResult.Queued(seq, paired.deviceName)
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

    fun sendClipboard(targetDeviceId: String, content: String): Boolean {
        if (!trustStore.isPaired(targetDeviceId)) return false
        val link = activeLinks[targetDeviceId] ?: return false
        if (!link.isConnected) return false
        return link.sendPacket(
            LinkPacket(
                type = LinkConstants.TYPE_CLIPBOARD,
                body = buildJsonObject {
                    put("content", content)
                    put("timestamp", System.currentTimeMillis())
                }
            )
        )
    }

    suspend fun connectToManualIp(ip: String, port: Int = LinkConstants.DEFAULT_PORT): Result<SecureLink> {
        return linkServer.connectOutbound(ip, port)
    }

    fun unpair(deviceId: String) {
        activeLinks.remove(deviceId)?.let { link ->
            pairingManager.unpair(deviceId, link)
            link.close()
        } ?: run {
            pairingManager.unpair(deviceId)
        }
        reconnectBackoffMs.remove(deviceId)
        nextAttemptAtMs.remove(deviceId)
        lastSeenMs.remove(deviceId)
        lastPersistedSeenMs.remove(deviceId)
        replayGuard.forget(deviceId)
        scope.launch { outbox.forgetDevice(deviceId) }
        updateConnectionState(deviceId, ConnectionStatus.DISCONNECTED)
        reconnectSignal.trySend(Unit)
    }

    private suspend fun flushOutboxForDevice(link: SecureLink) {
        val peerId = link.peerDeviceId
        val pending = outbox.getPendingForDevice(peerId)

        for (item in pending) {
            val packetWithSeq = item.packet.copy(
                body = buildJsonObject {
                    for ((k, v) in item.packet.body) {
                        put(k, v)
                    }
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
     * Event-driven reconnect runner (§2.Stage 2.5):
     * Sleeps when all paired devices are connected or no devices are paired.
     * Retries with per-device backoff when disconnected.
     */
    private fun startReconnectLoop() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            while (isActive) {
                val paired = trustStore.pairedDevices.value
                if (paired.isEmpty()) {
                    reconnectSignal.receive()
                    continue
                }

                val now = System.currentTimeMillis()
                var hasDisconnected = false
                var minWaitMs = Long.MAX_VALUE

                for (device in paired) {
                    val id = device.deviceId
                    val active = activeLinks[id]
                    if (active != null && active.isConnected) {
                        reconnectBackoffMs.remove(id)
                        nextAttemptAtMs.remove(id)
                        updateConnectionState(id, ConnectionStatus.CONNECTED)
                        continue
                    }

                    hasDisconnected = true
                    val nextAttempt = nextAttemptAtMs[id] ?: 0L
                    if (now >= nextAttempt) {
                        attemptReconnect(device)
                    } else {
                        val wait = (nextAttempt - now).coerceAtLeast(100L)
                        if (wait < minWaitMs) {
                            minWaitMs = wait
                        }
                    }
                }

                if (!hasDisconnected) {
                    // All paired devices connected! Idle completely until next event.
                    reconnectSignal.receive()
                    continue
                }

                if (minWaitMs == Long.MAX_VALUE) {
                    minWaitMs = LinkConstants.RECONNECT_TICK_MS
                }

                withTimeoutOrNull(minWaitMs) {
                    reconnectSignal.receive()
                }
            }
        }
    }

    private suspend fun attemptReconnect(device: PairedDevice) {
        val id = device.deviceId
        val discovered = discovery.discoveredDevices.value

        val host = device.customIp ?: discovered.firstOrNull { it.identity.deviceId == id }?.hostAddress
        val port = discovered.firstOrNull { it.identity.deviceId == id }?.tcpPort ?: LinkConstants.DEFAULT_PORT

        val now = System.currentTimeMillis()
        val isRecentlySeen = device.lastSeenEpochMs > 0 && (now - device.lastSeenEpochMs < LinkConstants.ASLEEP_THRESHOLD_MS)
        val fallbackStatus = if (isRecentlySeen) ConnectionStatus.ASLEEP else ConnectionStatus.DISCONNECTED

        if (host == null) {
            updateConnectionState(id, fallbackStatus)
            scheduleRetry(id)
            return
        }

        updateConnectionState(id, ConnectionStatus.CONNECTING)
        linkServer.connectOutbound(host, port)
            .onSuccess { link ->
                reconnectBackoffMs.remove(id)
                nextAttemptAtMs.remove(id)
                onLinkConnected(link)
            }
            .onFailure {
                updateConnectionState(id, fallbackStatus)
                scheduleRetry(id)
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