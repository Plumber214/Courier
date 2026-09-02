package courier.link

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

object LinkConstants {
    const val DEFAULT_PORT = 1816
    const val PROTOCOL_VERSION = 1
    const val MDNS_SERVICE_TYPE = "_courier._tcp.local."
    
    // Security Caps (CVE Mitigation §1.4)
    const val MAX_PACKET_SIZE_BYTES = 64 * 1024 // 64 KB
    const val MAX_CONCURRENT_CONNECTIONS = 8
    const val SOCKET_CONNECT_TIMEOUT_MS = 10_000 // 10s
    const val SOCKET_READ_TIMEOUT_MS = 90_000 // 90s
    const val HEARTBEAT_INTERVAL_MS = 30_000L // 30s
    const val PAIRING_TIMEOUT_MS = 30_000L // 30s
    const val UDP_RATE_LIMIT_PER_IP_MS = 500L
    const val MAX_DISCOVERED_DEVICES = 32

    // Packet Types
    const val TYPE_IDENTITY = "courier.identity"
    const val TYPE_PAIR = "courier.pair"
    const val TYPE_ACK = "courier.ack"
    const val TYPE_PING = "courier.ping"
    const val TYPE_CLIPBOARD = "courier.clipboard"
    const val TYPE_DOWNLOAD_REQUEST = "courier.download.request"
    const val TYPE_DOWNLOAD_ACCEPTED = "courier.download.accepted"
    const val TYPE_DOWNLOAD_STATUS = "courier.download.status"
}

@Serializable
data class LinkPacket(
    val id: Long = System.currentTimeMillis(),
    val type: String,
    val body: JsonObject
)

@Serializable
data class DeviceIdentity(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String, // "desktop", "phone", "tablet", "laptop"
    val tcpPort: Int = LinkConstants.DEFAULT_PORT,
    val protocolVersion: Int = LinkConstants.PROTOCOL_VERSION,
    val incomingCapabilities: List<String> = listOf(
        LinkConstants.TYPE_DOWNLOAD_REQUEST,
        LinkConstants.TYPE_CLIPBOARD,
        LinkConstants.TYPE_PING,
        LinkConstants.TYPE_PAIR
    ),
    val outgoingCapabilities: List<String> = listOf(
        LinkConstants.TYPE_DOWNLOAD_REQUEST,
        LinkConstants.TYPE_CLIPBOARD,
        LinkConstants.TYPE_PING,
        LinkConstants.TYPE_PAIR
    )
)

@Serializable
data class PairedDevice(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val certificateSha256: String,
    val certificateBase64: String,
    val pairedAtEpochMs: Long = System.currentTimeMillis(),
    val lastSeenEpochMs: Long = System.currentTimeMillis(),
    val customIp: String? = null,
    val isClipboardSyncEnabled: Boolean = false
)

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ASLEEP
}

@Serializable
data class DiscoveredDevice(
    val identity: DeviceIdentity,
    val hostAddress: String,
    val tcpPort: Int,
    val lastSeenEpochMs: Long = System.currentTimeMillis()
)