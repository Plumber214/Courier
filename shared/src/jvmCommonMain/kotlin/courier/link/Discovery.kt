package courier.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

class Discovery(
    private val identityProvider: () -> DeviceIdentity,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var udpSocket: DatagramSocket? = null
    private var listenerJob: Job? = null
    private var broadcastJob: Job? = null
    private var jmdns: JmDNS? = null

    // Held for the announcing window rather than opened and closed per
    // broadcast. Announcing runs at ~1.5 s while the Devices tab is open, so
    // the old approach churned a socket about forty times a minute.
    private var broadcastSocket: DatagramSocket? = null

    // Enumerating every interface is a syscall-heavy operation and the answer
    // rarely changes; a network change closes the announcing window anyway.
    private var cachedBroadcastTargets: List<InetAddress> = emptyList()
    private var broadcastTargetsRefreshedAtMs = 0L

    // Rate limiting map: Remote IP -> Last received epoch ms
    private val udpRateLimits = ConcurrentHashMap<String, Long>()

    fun getPublicIdentity(): DeviceIdentity = identityProvider().toGenericPublicIdentity()

    /**
     * Starts listening for incoming UDP discovery broadcasts.
     * The listener runs while the subsystem is awake (§2.Stage 2.3).
     */
    fun startUdpListener() {
        if (listenerJob?.isActive == true && udpSocket != null && !udpSocket!!.isClosed) return

        listenerJob?.cancel()
        listenerJob = scope.launch {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(LinkConstants.DEFAULT_PORT))
                }
                udpSocket = socket
                val buffer = ByteArray(LinkConstants.MAX_PACKET_SIZE_BYTES)

                while (isActive && !socket.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)

                    val remoteIp = packet.address?.hostAddress ?: continue
                    val now = System.currentTimeMillis()

                    // 1. Rate Limiting per IP (CVE Mitigation §1.4)
                    val lastSeen = udpRateLimits[remoteIp] ?: 0L
                    if (now - lastSeen < LinkConstants.UDP_RATE_LIMIT_PER_IP_MS) {
                        continue // Drop packet exceeding rate limit
                    }
                    udpRateLimits[remoteIp] = now

                    // 2. Parse Packet
                    val raw = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    try {
                        val linkPacket = json.decodeFromString<LinkPacket>(raw)
                        if (linkPacket.type == LinkConstants.TYPE_IDENTITY) {
                            val peerIdentity = json.decodeFromJsonElement(DeviceIdentity.serializer(), linkPacket.body)
                            if (peerIdentity.deviceId != identityProvider().deviceId) {
                                registerDiscoveredDevice(peerIdentity, remoteIp, peerIdentity.tcpPort)
                            }
                        }
                    } catch (_: Exception) {
                        // Ignore malformed packets
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    println("UDP Listener error: ${e.message}")
                }
            }
        }
    }

    fun stopUdpListener() {
        listenerJob?.cancel()
        listenerJob = null
        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        udpSocket = null
        udpRateLimits.clear()
    }

    /**
     * Starts active LAN announcements (F5): sends UDP broadcasts every ~1.5s
     * and runs mDNS while the Devices screen is open.
     */
    fun startActiveAnnouncing(intervalMs: Long = 1500L) {
        startUdpListener() // Ensure listener is running to catch responses
        _isScanning.value = true

        broadcastSocket = try {
            DatagramSocket().apply { broadcast = true }
        } catch (_: Exception) {
            null
        }

        broadcastJob?.cancel()
        broadcastJob = scope.launch {
            var pruneTick = 0
            while (isActive) {
                sendUdpBroadcast()
                pruneTick++
                if (pruneTick % 5 == 0) {
                    pruneStaleDiscoveredDevices(60_000L)
                }
                delay(intervalMs)
            }
        }
        startMdns()
    }

    fun stopActiveAnnouncing() {
        broadcastJob?.cancel()
        broadcastJob = null
        try {
            broadcastSocket?.close()
        } catch (_: Exception) {}
        broadcastSocket = null
        _isScanning.value = false
        stopMdns()
    }

    fun pruneStaleDiscoveredDevices(maxAgeMs: Long = 60_000L) {
        val now = System.currentTimeMillis()
        val current = _discoveredDevices.value
        val filtered = current.filter { now - it.lastSeenEpochMs < maxAgeMs }
        if (filtered.size != current.size) {
            _discoveredDevices.value = filtered
        }
    }

    fun start() {
        startUdpListener()
    }

    fun stop() {
        stopActiveAnnouncing()
        stopUdpListener()
        _discoveredDevices.value = emptyList()
    }

    fun broadcastNow() {
        scope.launch {
            // A manual kick usually follows a network change, so this is the one
            // call that should not trust the cached interface list.
            sendUdpBroadcast(forceRefreshTargets = true)
        }
    }

    /**
     * Every address worth broadcasting to: the global address plus each live
     * interface's own subnet broadcast.
     *
     * Recomputed at most once a minute. `broadcastNow` forces a refresh,
     * because the one time the answer really has changed is a network switch,
     * which is exactly what triggers a manual kick.
     */
    private fun broadcastTargets(forceRefresh: Boolean): List<InetAddress> {
        val now = System.currentTimeMillis()
        if (!forceRefresh &&
            cachedBroadcastTargets.isNotEmpty() &&
            now - broadcastTargetsRefreshedAtMs < 60_000L
        ) {
            return cachedBroadcastTargets
        }

        val targets = mutableListOf<InetAddress>()
        try {
            targets.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {}

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.interfaceAddresses) {
                    addr.broadcast?.let { targets.add(it) }
                }
            }
        } catch (_: Exception) {}

        cachedBroadcastTargets = targets
        broadcastTargetsRefreshedAtMs = now
        return targets
    }

    private fun sendUdpBroadcast(forceRefreshTargets: Boolean = false) {
        try {
            val publicIdentity = getPublicIdentity()
            val bodyJson = json.encodeToJsonElement(publicIdentity).jsonObject
            val packet = LinkPacket(
                type = LinkConstants.TYPE_IDENTITY,
                body = bodyJson
            )
            val jsonBytes = (json.encodeToString(packet) + "\n").toByteArray(Charsets.UTF_8)

            // Use the socket held for the announcing window when there is one.
            // A one-off kick outside that window opens and closes its own.
            val held = broadcastSocket
            val socket = held ?: DatagramSocket().apply { broadcast = true }
            try {
                for (target in broadcastTargets(forceRefreshTargets)) {
                    try {
                        socket.send(
                            DatagramPacket(jsonBytes, jsonBytes.size, target, LinkConstants.DEFAULT_PORT)
                        )
                    } catch (_: Exception) {}
                }
            } finally {
                if (held == null) socket.close()
            }
        } catch (_: Exception) {}
    }

    private fun startMdns() {
        if (jmdns != null) return
        scope.launch {
            try {
                val localAddr = getLocalIpAddress()
                if (localAddr != null) {
                    val publicIdentity = getPublicIdentity()
                    val genericServiceName = "Courier-${publicIdentity.deviceId.take(6)}"
                    val jmdnsInstance = JmDNS.create(localAddr, genericServiceName)
                    jmdns = jmdnsInstance

                    val props = mapOf("id" to publicIdentity.deviceId, "type" to publicIdentity.deviceType)
                    val serviceInfo = ServiceInfo.create(
                        LinkConstants.MDNS_SERVICE_TYPE,
                        genericServiceName,
                        publicIdentity.tcpPort,
                        0,
                        0,
                        props
                    )
                    jmdnsInstance.registerService(serviceInfo)

                    jmdnsInstance.addServiceListener(LinkConstants.MDNS_SERVICE_TYPE, object : ServiceListener {
                        override fun serviceAdded(event: ServiceEvent) {}
                        override fun serviceRemoved(event: ServiceEvent) {}
                        override fun serviceResolved(event: ServiceEvent) {
                            val info = event.info
                            val devId = info.getPropertyString("id") ?: return
                            if (devId == identityProvider().deviceId) return

                            val peerHost = info.inet4Addresses.firstOrNull()?.hostAddress ?: return
                            val peerIdentity = DeviceIdentity(
                                deviceId = devId,
                                deviceName = info.name,
                                deviceType = info.getPropertyString("type") ?: "desktop",
                                tcpPort = info.port
                            )
                            registerDiscoveredDevice(peerIdentity, peerHost, info.port)
                        }
                    })
                }
            } catch (_: Exception) {}
        }
    }

    private fun stopMdns() {
        try {
            jmdns?.unregisterAllServices()
            jmdns?.close()
        } catch (_: Exception) {}
        jmdns = null
    }

    fun registerDiscoveredDevice(
        identity: DeviceIdentity,
        hostAddress: String,
        tcpPort: Int,
        lastSeenEpochMs: Long = System.currentTimeMillis()
    ) {
        val current = _discoveredDevices.value.toMutableList()
        current.removeAll { it.identity.deviceId == identity.deviceId }

        // Evict by oldest lastSeenEpochMs (Decision F8, §0.8, §2.Stage 2.6)
        if (current.size >= LinkConstants.MAX_DISCOVERED_DEVICES) {
            current.sortBy { it.lastSeenEpochMs }
            if (current.isNotEmpty()) {
                current.removeAt(0)
            }
        }

        current.add(DiscoveredDevice(identity, hostAddress, tcpPort, lastSeenEpochMs))
        _discoveredDevices.value = current
    }

    private fun getLocalIpAddress(): InetAddress? {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (iface.isLoopback || !iface.isUp) continue
            for (addr in iface.inetAddresses) {
                if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                    return addr
                }
            }
        }
        return null
    }
}