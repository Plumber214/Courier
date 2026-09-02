package courier.link

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class SecureLink(
    val peerDeviceId: String,
    val peerIdentity: DeviceIdentity,
    val peerCertificate: X509Certificate,
    private val socket: SSLSocket,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
    private val writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
    private val writeLock = Any()

    private val _incomingPackets = MutableSharedFlow<LinkPacket>(extraBufferCapacity = 64)
    val incomingPackets: SharedFlow<LinkPacket> = _incomingPackets.asSharedFlow()

    private var readJob: Job? = null
    private var pingJob: Job? = null
    private var isClosed = false

    val isConnected: Boolean
        get() = !isClosed && socket.isConnected && !socket.isClosed

    fun start() {
        startReader()
        startPingSender()
    }

    private fun startReader() {
        readJob = scope.launch {
            try {
                while (isActive && isConnected) {
                    // Bounded read: the cap has to be enforced while reading,
                    // not after. See BoundedLineReader.
                    val line = reader.readLineBounded() ?: break
                    if (line.isBlank()) continue

                    try {
                        val packet = json.decodeFromString<LinkPacket>(line)
                        _incomingPackets.tryEmit(packet)
                    } catch (e: Exception) {
                        println("Failed to parse packet from $peerDeviceId: ${e.message}")
                    }
                }
            } catch (e: PacketTooLargeException) {
                // A peer that ignores the frame limit is broken or hostile.
                // Skipping the frame would leave it free to keep going, so the
                // connection goes instead.
                println("[SECURITY] Dropping connection to $peerDeviceId: ${e.message}")
            } catch (_: Exception) {
            } finally {
                close()
            }
        }
    }

    private fun startPingSender() {
        pingJob = scope.launch {
            while (isActive && isConnected) {
                delay(LinkConstants.HEARTBEAT_INTERVAL_MS)
                if (isConnected) {
                    sendPacket(LinkPacket(type = LinkConstants.TYPE_PING, body = buildJsonObject {}))
                }
            }
        }
    }

    fun sendPacket(packet: LinkPacket): Boolean {
        if (!isConnected) return false
        return try {
            val payload = json.encodeToString(packet) + "\n"
            synchronized(writeLock) {
                writer.write(payload)
                writer.flush()
            }
            true
        } catch (e: Exception) {
            close()
            false
        }
    }

    fun close() {
        if (isClosed) return
        isClosed = true
        readJob?.cancel()
        pingJob?.cancel()
        try {
            socket.close()
        } catch (_: Exception) {}
    }

    companion object {
        /**
         * Upgrades a raw TCP socket to TLS with KDE Connect style TLS ROLE INVERSION:
         * - If isTcpServer is TRUE (we accepted the TCP connection), we act as TLS CLIENT.
         * - If isTcpServer is FALSE (we initiated the TCP connection), we act as TLS SERVER.
         */
        suspend fun upgradeToTls(
            rawSocket: Socket,
            isTcpServer: Boolean,
            sslContext: SSLContext,
            peerHost: String,
            peerPort: Int
        ): SSLSocket = withContext(Dispatchers.IO) {
            rawSocket.soTimeout = LinkConstants.SOCKET_READ_TIMEOUT_MS

            val sslFactory = sslContext.socketFactory
            val sslSocket = sslFactory.createSocket(
                rawSocket,
                peerHost,
                peerPort,
                true
            ) as SSLSocket

            // Apply TLS Role Inversion (§1.1)
            if (isTcpServer) {
                sslSocket.useClientMode = true
            } else {
                sslSocket.useClientMode = false
                sslSocket.needClientAuth = true
            }

            sslSocket.startHandshake()
            sslSocket
        }
    }
}