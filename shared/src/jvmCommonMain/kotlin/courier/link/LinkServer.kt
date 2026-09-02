package courier.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger

class LinkServer(
    private val myIdentity: DeviceIdentity,
    private val certStore: CertificateStore,
    private val trustStore: TrustStore,
    private val onLinkEstablished: (SecureLink) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val activeConnections = AtomicInteger(0)

    fun start(port: Int = LinkConstants.DEFAULT_PORT) {
        stop()
        serverJob = scope.launch {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss

                while (isActive && !ss.isClosed) {
                    val clientSocket = ss.accept()

                    // Connection Cap (CVE Mitigation §1.4)
                    if (activeConnections.get() >= LinkConstants.MAX_CONCURRENT_CONNECTIONS) {
                        clientSocket.close()
                        continue
                    }

                    activeConnections.incrementAndGet()
                    scope.launch {
                        try {
                            handleInboundConnection(clientSocket)
                        } catch (e: Exception) {
                            println("Inbound connection error: ${e.message}")
                        } finally {
                            activeConnections.decrementAndGet()
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    println("LinkServer error: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        serverJob?.cancel()
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }

    private suspend fun handleInboundConnection(rawSocket: Socket) = withContext(Dispatchers.IO) {
        rawSocket.soTimeout = LinkConstants.SOCKET_CONNECT_TIMEOUT_MS

        val reader = BufferedReader(InputStreamReader(rawSocket.inputStream, Charsets.UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(rawSocket.outputStream, Charsets.UTF_8))

        // 1. Cleartext Identity Exchange
        // Send our identity
        val myIdentityPacket = LinkPacket(
            type = LinkConstants.TYPE_IDENTITY,
            body = json.encodeToJsonElement(myIdentity).jsonObject
        )
        writer.write(json.encodeToString(myIdentityPacket) + "\n")
        writer.flush()

        // Read peer identity
        val peerLine = reader.readLine() ?: throw Exception("Peer disconnected before identity exchange")
        val peerPacket = json.decodeFromString<LinkPacket>(peerLine)
        if (peerPacket.type != LinkConstants.TYPE_IDENTITY) {
            throw Exception("Expected courier.identity, got ${peerPacket.type}")
        }
        val peerIdentity = json.decodeFromJsonElement(DeviceIdentity.serializer(), peerPacket.body)

        // 2. Upgrade to TLS (Inbound TCP connection -> TLS Client role)
        var peerCert: X509Certificate? = null
        val sslContext = certStore.createSslContext { cert ->
            peerCert = cert
            // If already paired, pin check
            if (trustStore.isPaired(peerIdentity.deviceId)) {
                trustStore.validatePinnedCertificate(peerIdentity.deviceId, cert)
            } else {
                true // Allow handshake for pairing
            }
        }

        val sslSocket = SecureLink.upgradeToTls(
            rawSocket = rawSocket,
            isTcpServer = true,
            sslContext = sslContext,
            peerHost = rawSocket.inetAddress?.hostAddress ?: "127.0.0.1",
            peerPort = rawSocket.port
        )

        val finalCert = peerCert ?: (sslSocket.session.peerCertificates.firstOrNull() as? X509Certificate)
        if (finalCert == null) {
            sslSocket.close()
            throw Exception("No peer certificate received in TLS handshake")
        }

        // Verify pinning again post-handshake
        if (trustStore.isPaired(peerIdentity.deviceId) && !trustStore.validatePinnedCertificate(peerIdentity.deviceId, finalCert)) {
            sslSocket.close()
            throw Exception("Pinned certificate validation failed")
        }

        val link = SecureLink(
            peerDeviceId = peerIdentity.deviceId,
            peerIdentity = peerIdentity,
            peerCertificate = finalCert,
            socket = sslSocket,
            scope = scope
        )
        link.start()
        onLinkEstablished(link)
    }

    suspend fun connectOutbound(host: String, port: Int = LinkConstants.DEFAULT_PORT): Result<SecureLink> = withContext(Dispatchers.IO) {
        try {
            val rawSocket = Socket()
            rawSocket.connect(InetSocketAddress(host, port), LinkConstants.SOCKET_CONNECT_TIMEOUT_MS)
            rawSocket.soTimeout = LinkConstants.SOCKET_CONNECT_TIMEOUT_MS

            val reader = BufferedReader(InputStreamReader(rawSocket.inputStream, Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(rawSocket.outputStream, Charsets.UTF_8))

            // 1. Cleartext Identity Exchange
            val myIdentityPacket = LinkPacket(
                type = LinkConstants.TYPE_IDENTITY,
                body = json.encodeToJsonElement(myIdentity).jsonObject
            )
            writer.write(json.encodeToString(myIdentityPacket) + "\n")
            writer.flush()

            val peerLine = reader.readLine() ?: throw Exception("Peer disconnected before identity exchange")
            val peerPacket = json.decodeFromString<LinkPacket>(peerLine)
            if (peerPacket.type != LinkConstants.TYPE_IDENTITY) {
                throw Exception("Expected courier.identity, got ${peerPacket.type}")
            }
            val peerIdentity = json.decodeFromJsonElement(DeviceIdentity.serializer(), peerPacket.body)

            // 2. Upgrade to TLS (Outbound TCP connection -> TLS Server role)
            var peerCert: X509Certificate? = null
            val sslContext = certStore.createSslContext { cert ->
                peerCert = cert
                if (trustStore.isPaired(peerIdentity.deviceId)) {
                    trustStore.validatePinnedCertificate(peerIdentity.deviceId, cert)
                } else {
                    true
                }
            }

            val sslSocket = SecureLink.upgradeToTls(
                rawSocket = rawSocket,
                isTcpServer = false,
                sslContext = sslContext,
                peerHost = host,
                peerPort = port
            )

            val finalCert = peerCert ?: (sslSocket.session.peerCertificates.firstOrNull() as? X509Certificate)
            if (finalCert == null) {
                sslSocket.close()
                throw Exception("No peer certificate received in TLS handshake")
            }

            if (trustStore.isPaired(peerIdentity.deviceId) && !trustStore.validatePinnedCertificate(peerIdentity.deviceId, finalCert)) {
                sslSocket.close()
                throw Exception("Pinned certificate validation failed")
            }

            val link = SecureLink(
                peerDeviceId = peerIdentity.deviceId,
                peerIdentity = peerIdentity,
                peerCertificate = finalCert,
                socket = sslSocket,
                scope = scope
            )
            link.start()
            onLinkEstablished(link)
            Result.success(link)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}