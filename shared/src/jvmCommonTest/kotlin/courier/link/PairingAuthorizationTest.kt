package courier.link

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pairing must be authorised by a person on the receiving device, not by a flag
 * in a packet.
 *
 * `TYPE_PAIR` is deliberately accepted from unpaired peers — pairing could not
 * otherwise begin — so `handleIncomingPairPacket` is reachable by anything on
 * the LAN that completes the TLS handshake. Until v1.7.0 it branched on the
 * peer-supplied `accepted` flag and nothing else, so a device could write itself
 * into the trust store with its certificate pinned and no dialog shown.
 */
class PairingAuthorizationTest {

    private val tempDirs = mutableListOf<File>()
    private val managers = mutableListOf<DeviceLinkManager>()

    @AfterTest
    fun cleanUp() {
        managers.forEach { runCatching { it.stop() } }
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun createTempDir(label: String): File {
        val dir = File(
            System.getProperty("java.io.tmpdir"),
            "courier-pairing-auth-$label-${System.nanoTime()}"
        )
        dir.mkdirs()
        tempDirs += dir
        return dir
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun manager(label: String, port: Int): Pair<DeviceLinkManager, CertificateStore> {
        val certStore = CertificateStore(storageDirOverride = createTempDir(label))
        val trustStore = TrustStore(fileNameOverride = "test_trust_${label}_${System.nanoTime()}.json")
        val mgr = DeviceLinkManager(certStore = certStore, trustStore = trustStore, tcpPort = port)
            .also { managers += it }
        return mgr to certStore
    }

    private fun acceptancePacket() = LinkPacket(
        type = LinkConstants.TYPE_PAIR,
        body = buildJsonObject {
            put("pair", true)
            put("accepted", true)
            put("friendlyName", "Totally Legitimate Device")
            put("timestamp", System.currentTimeMillis())
        }
    )

    @Test
    fun `an unsolicited acceptance from a stranger does not create a pairing`() = runBlocking<Unit> {
        val victimPort = freePort()
        val (victim, victimCert) = manager("victim", victimPort)
        val (attacker, attackerCert) = manager("attacker", freePort())

        victim.linkServer.start(victimPort)
        delay(150)

        val connection = attacker.linkServer.connectOutbound("127.0.0.1", victimPort)
        assertTrue(
            connection.isSuccess,
            "Handshake failed, so the test never reached the case it exists to cover: " +
                "${connection.exceptionOrNull()?.message}"
        )

        // The victim never initiated anything. It is sitting idle.
        assertTrue(
            victim.pairingManager.pairingState.value is PairingSessionState.Idle,
            "Precondition: victim must be idle"
        )

        assertTrue(connection.getOrThrow().sendPacket(acceptancePacket()), "Packet was not sent")
        delay(600)

        assertFalse(
            victim.trustStore.isPaired(attackerCert.deviceId),
            "An unpaired device paired itself by asserting acceptance. " +
                "This is the v1.6.0 bypass (PLAN-007 §0.1)."
        )
        assertTrue(
            victim.trustStore.pairedDevices.value.isEmpty(),
            "Victim trust store should still be empty"
        )
        // And it must not have been paired in the other direction either.
        assertFalse(attacker.trustStore.isPaired(victimCert.deviceId))
    }

    @Test
    fun `an acceptance from a third device does not hijack a pairing in progress`() = runBlocking<Unit> {
        val portA = freePort()
        val portB = freePort()

        val (managerA, _) = manager("initiator", portA)
        val (managerB, certB) = manager("target", portB)
        val (managerC, certC) = manager("interloper", freePort())

        managerB.linkServer.start(portB)
        managerA.linkServer.start(portA)
        delay(150)

        // A starts a genuine pairing with B and is now in OutgoingRequest.
        val linkAtoB = managerA.linkServer.connectOutbound("127.0.0.1", portB).getOrThrow()
        managerA.pairingManager.initiatePairing(
            linkAtoB,
            DiscoveredDevice(
                identity = DeviceIdentity(
                    deviceId = certB.deviceId,
                    deviceName = "Target",
                    deviceType = "desktop",
                    tcpPort = portB
                ),
                hostAddress = "127.0.0.1",
                tcpPort = portB
            )
        )
        delay(300)
        assertTrue(
            managerA.pairingManager.pairingState.value is PairingSessionState.OutgoingRequest,
            "Precondition: A should be waiting on B"
        )

        // C connects to A and claims to be the acceptance A is waiting for.
        val linkCtoA = managerC.linkServer.connectOutbound("127.0.0.1", portA).getOrThrow()
        assertTrue(linkCtoA.sendPacket(acceptancePacket()))
        delay(600)

        assertFalse(
            managerA.trustStore.isPaired(certC.deviceId),
            "A third device completed a pairing ceremony it was not part of"
        )
    }

    @Test
    fun `the initiator cannot complete its own pairing - only the receiver can`() = runBlocking<Unit> {
        val portA = freePort()
        val portB = freePort()

        val (managerA, certA) = manager("a", portA)
        val (managerB, certB) = manager("b", portB)

        managerB.linkServer.start(portB)
        delay(150)

        val linkA = managerA.linkServer.connectOutbound("127.0.0.1", portB).getOrThrow()
        managerA.pairingManager.initiatePairing(
            linkA,
            DiscoveredDevice(
                identity = DeviceIdentity(
                    deviceId = certB.deviceId,
                    deviceName = "Node B",
                    deviceType = "desktop",
                    tcpPort = portB
                ),
                hostAddress = "127.0.0.1",
                tcpPort = portB
            )
        )
        delay(400)

        // B has been asked, and has not answered. Nothing is paired anywhere.
        assertFalse(
            managerA.trustStore.isPaired(certB.deviceId),
            "Initiator paired before the receiver confirmed"
        )
        assertFalse(
            managerB.trustStore.isPaired(certA.deviceId),
            "Receiver paired before its user confirmed"
        )
        assertTrue(managerB.pairingManager.pairingState.value is PairingSessionState.IncomingRequest)

        // The receiving device's user confirms. Now both sides pair.
        managerB.pairingManager.acceptPairing()
        delay(600)

        assertTrue(managerB.trustStore.isPaired(certA.deviceId), "Receiver did not pair on confirm")
        assertTrue(managerA.trustStore.isPaired(certB.deviceId), "Initiator did not pair on acceptance")

        // Both sides agree on the same certificate, which is the point of pinning.
        assertEquals(
            certA.certificateSha256,
            managerB.trustStore.getPairedDevice(certA.deviceId)?.certificateSha256
        )
    }

    @Test
    fun `unpairing discards queued outbox packets for that device`() = runBlocking<Unit> {
        val outbox = Outbox(fileNameOverride = "test_outbox_unpair_${System.nanoTime()}.json")
        val leaving = "device-leaving-${System.nanoTime()}"
        val staying = "device-staying-${System.nanoTime()}"

        val packet = LinkPacket(
            type = LinkConstants.TYPE_DOWNLOAD_REQUEST,
            body = buildJsonObject { put("url", "https://example.com/video") }
        )
        outbox.enqueue(leaving, packet)
        outbox.enqueue(leaving, packet)
        outbox.enqueue(staying, packet)

        assertEquals(2, outbox.getPendingForDevice(leaving).size)

        outbox.forgetDevice(leaving)

        assertTrue(
            outbox.getPendingForDevice(leaving).isEmpty(),
            "Queued work outlived the pairing it belonged to"
        )
        assertEquals(
            1,
            outbox.getPendingForDevice(staying).size,
            "Forgetting one device must not touch another's queue"
        )
        assertNotNull(outbox.getAllItems().firstOrNull { it.targetDeviceId == staying })
    }
}
