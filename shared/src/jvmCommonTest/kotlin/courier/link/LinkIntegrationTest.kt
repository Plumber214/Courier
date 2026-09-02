package courier.link

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end transport tests over real sockets on loopback.
 *
 * The unit tests cover the pieces; this covers the thing that actually has to
 * work. In particular it exercises the TLS role inversion - the TCP server side
 * becoming the TLS *client* - which is the single least intuitive part of the
 * design and cannot be verified by inspection.
 *
 * Two independent identities are created in temp directories so both nodes have
 * distinct device ids, keypairs and trust stores.
 */
class LinkIntegrationTest {

    private val tempDirs = mutableListOf<File>()
    private val servers = mutableListOf<LinkServer>()
    private val links = mutableListOf<SecureLink>()

    @AfterTest
    fun cleanUp() {
        links.forEach { runCatching { it.close() } }
        servers.forEach { runCatching { it.stop() } }
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun newCertStore(label: String): CertificateStore {
        val dir = File(System.getProperty("java.io.tmpdir"), "courier-link-test-$label-${System.nanoTime()}")
        dir.mkdirs()
        tempDirs += dir
        return CertificateStore(storageDirOverride = dir)
    }

    private fun newTrustStore(label: String) =
        TrustStore(fileNameOverride = "test_trust_${label}_${System.nanoTime()}.json")

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun identityFor(store: CertificateStore, name: String, port: Int) = DeviceIdentity(
        deviceId = store.deviceId,
        deviceName = name,
        deviceType = "desktop",
        tcpPort = port
    )

    @Test
    fun `two nodes complete the identity exchange and TLS upgrade over real sockets`() = runBlocking<Unit> {
        val port = freePort()

        val serverCerts = newCertStore("server")
        val clientCerts = newCertStore("client")
        val serverTrust = newTrustStore("server")
        val clientTrust = newTrustStore("client")

        val serverLinks = LinkedBlockingQueue<SecureLink>()
        val listener = LinkServer(
            myIdentity = identityFor(serverCerts, "Node-A", port),
            certStore = serverCerts,
            trustStore = serverTrust,
            onLinkEstablished = { serverLinks.offer(it) }
        ).also { servers += it }
        listener.start(port)

        val dialerLinks = LinkedBlockingQueue<SecureLink>()
        val dialer = LinkServer(
            myIdentity = identityFor(clientCerts, "Node-B", freePort()),
            certStore = clientCerts,
            trustStore = clientTrust,
            onLinkEstablished = { dialerLinks.offer(it) }
        ).also { servers += it }

        val outbound = dialer.connectOutbound("127.0.0.1", port)
        assertTrue(outbound.isSuccess, "TLS handshake failed: ${outbound.exceptionOrNull()?.message}")

        val dialerSide = outbound.getOrThrow().also { links += it }
        val listenerSide = serverLinks.poll(15, TimeUnit.SECONDS)
        assertNotNull(listenerSide, "Listening node never surfaced a link")
        links += listenerSide

        // Each side learned the other's real device id through the exchange.
        assertEquals(serverCerts.deviceId, dialerSide.peerDeviceId)
        assertEquals(clientCerts.deviceId, listenerSide.peerDeviceId)
        assertTrue(dialerSide.isConnected)
        assertTrue(listenerSide.isConnected)
    }

    @Test
    fun `both sides derive the same verification code`() {
        // Pairing is worthless if the codes shown on the two screens disagree.
        val a = newCertStore("code-a")
        val b = newCertStore("code-b")

        val codeOnA = a.computeVerificationCode(b.certificateSha256)
        val codeOnB = b.computeVerificationCode(a.certificateSha256)

        assertEquals(codeOnA, codeOnB, "Verification codes must match on both devices")
        assertEquals(9, codeOnA.length, "Expected an 8 hex char code with a separator")
    }

    @Test
    fun `packets round-trip between two connected nodes`() = runBlocking<Unit> {
        val port = freePort()
        val serverCerts = newCertStore("rt-server")
        val clientCerts = newCertStore("rt-client")

        val serverLinks = LinkedBlockingQueue<SecureLink>()
        LinkServer(
            myIdentity = identityFor(serverCerts, "Node-A", port),
            certStore = serverCerts,
            trustStore = newTrustStore("rt-server"),
            onLinkEstablished = { serverLinks.offer(it) }
        ).also { servers += it }.start(port)

        val dialer = LinkServer(
            myIdentity = identityFor(clientCerts, "Node-B", freePort()),
            certStore = clientCerts,
            trustStore = newTrustStore("rt-client"),
            onLinkEstablished = { }
        ).also { servers += it }

        val dialerSide = dialer.connectOutbound("127.0.0.1", port).getOrThrow().also { links += it }
        val listenerSide = serverLinks.poll(15, TimeUnit.SECONDS)
        assertNotNull(listenerSide)
        links += listenerSide

        val received = withTimeoutOrNull(15_000L) {
            val collector = CompletableDeferred<LinkPacket>()
            val job = launch {
                listenerSide.incomingPackets.collect { collector.complete(it) }
            }
            // Give the collector a moment to subscribe before sending.
            delay(300)
            dialerSide.sendPacket(
                LinkPacket(type = LinkConstants.TYPE_PING, body = buildJsonObject { put("hello", "world") })
            )
            val result = collector.await()
            job.cancel()
            result
        }

        assertNotNull(received, "Packet never arrived at the far side")
        assertEquals(LinkConstants.TYPE_PING, received.type)
    }

    @Test
    fun `an endless frame with no newline does not exhaust the listener`() = runBlocking<Unit> {
        // The pre-TLS identity read is the most exposed point in the transport:
        // reachable by any unpaired peer on the LAN. Before the bounded reader
        // this streamed straight into an unbounded buffer.
        val port = freePort()
        val serverCerts = newCertStore("flood-server")

        val establishedLinks = LinkedBlockingQueue<SecureLink>()
        LinkServer(
            myIdentity = identityFor(serverCerts, "Node-A", port),
            certStore = serverCerts,
            trustStore = newTrustStore("flood-server"),
            onLinkEstablished = { establishedLinks.offer(it) }
        ).also { servers += it }.start(port)

        delay(150)
        Socket("127.0.0.1", port).use { hostile ->
            val out = hostile.getOutputStream()
            val chunk = ByteArray(8 * 1024) { 'x'.code.toByte() }
            // Well past MAX_PACKET_SIZE_BYTES, and never a newline.
            repeat(64) {
                runCatching { out.write(chunk); out.flush() }
            }
        }

        // The listener must have refused it rather than promoting it to a link.
        assertNull(
            establishedLinks.poll(3, TimeUnit.SECONDS),
            "A peer that never terminates a frame must not establish a link"
        )

        // And the server must still be serving.
        val clientCerts = newCertStore("flood-client")
        val dialer = LinkServer(
            myIdentity = identityFor(clientCerts, "Node-B", freePort()),
            certStore = clientCerts,
            trustStore = newTrustStore("flood-client"),
            onLinkEstablished = { }
        ).also { servers += it }

        val recovery = dialer.connectOutbound("127.0.0.1", port)
        assertTrue(
            recovery.isSuccess,
            "Listener stopped accepting healthy peers after a flood: ${recovery.exceptionOrNull()?.message}"
        )
        recovery.getOrNull()?.let { links += it }
    }
}
