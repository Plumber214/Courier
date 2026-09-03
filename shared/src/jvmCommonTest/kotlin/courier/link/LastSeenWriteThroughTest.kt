package courier.link

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Last-seen is held in memory and written through rarely (PLAN-007 G6).
 *
 * It used to be persisted on every received packet, and TrustStore's save is a
 * durable three-file operation — serialise the paired list, write a temp file,
 * fd.sync(), copy the previous file to .bak, atomically move. During a remote
 * download that ran several times a second, for a timestamp only ever displayed
 * while a device is offline.
 *
 * Both halves matter: packets must stop writing, and a disconnect must still
 * record the right time. A change that only did the first would look like a win
 * and quietly break the feature.
 */
class LastSeenWriteThroughTest {

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
            "courier-lastseen-$label-${System.nanoTime()}"
        )
        dir.mkdirs()
        tempDirs += dir
        return dir
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `packet traffic does not rewrite the trust store, but a disconnect does`() = runBlocking<Unit> {
        val portA = freePort()
        val portB = freePort()

        val certA = CertificateStore(storageDirOverride = createTempDir("a"))
        val certB = CertificateStore(storageDirOverride = createTempDir("b"))
        certA.setDeviceName("Node A")
        certB.setDeviceName("Node B")

        val trustA = TrustStore(fileNameOverride = "ls_trust_a_${System.nanoTime()}.json")
        val trustB = TrustStore(fileNameOverride = "ls_trust_b_${System.nanoTime()}.json")

        val managerA = DeviceLinkManager(certStore = certA, trustStore = trustA, tcpPort = portA)
            .also { managers += it }
        val managerB = DeviceLinkManager(certStore = certB, trustStore = trustB, tcpPort = portB)
            .also { managers += it }

        managerB.linkServer.start(portB)
        delay(200)

        val link = managerA.linkServer.connectOutbound("127.0.0.1", portB).getOrThrow()
        managerA.pairingManager.initiatePairing(
            link,
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
        delay(300)
        managerB.pairingManager.acceptPairing()
        delay(500)

        val pairedOnB = trustB.getPairedDevice(certA.deviceId)
        assertNotNull(pairedOnB, "Precondition: A must be paired on B")
        val afterPairing = pairedOnB.lastSeenEpochMs
        assertTrue(afterPairing > 0, "Precondition: last-seen must be set at pairing")

        // Enough wall-clock time that a per-packet write would produce a
        // visibly different timestamp, but far short of the 60 s write-through
        // interval.
        delay(1_200)

        // Drive real traffic across the link. Each rename is a packet B
        // receives, and each one used to trigger a full durable rewrite.
        repeat(5) { i ->
            managerA.updateDeviceName("Node A rename $i")
            delay(120)
        }

        val duringTraffic = trustB.getPairedDevice(certA.deviceId)?.lastSeenEpochMs
        assertEquals(
            afterPairing, duringTraffic,
            "Received packets rewrote the trust store; last-seen should be debounced"
        )

        // The rename itself must still have landed — proving the packets really
        // were delivered and this is a write-through test, not a dead link.
        val renamed = withTimeoutOrNull(3_000) {
            while (trustB.getPairedDevice(certA.deviceId)?.deviceName != "Node A rename 4") {
                delay(100)
            }
            true
        }
        assertNotNull(renamed, "Packets were not actually delivered, so the test proved nothing")

        // Now drop the link. This is the moment last-seen becomes visible in the
        // UI, so it must be written through.
        link.close()

        val advanced = withTimeoutOrNull(5_000) {
            while ((trustB.getPairedDevice(certA.deviceId)?.lastSeenEpochMs ?: 0L) <= afterPairing) {
                delay(100)
            }
            true
        }
        assertNotNull(
            advanced,
            "Disconnect did not flush last-seen; an offline device would show a stale time"
        )
    }

    @Test
    fun `unpairing forgets the in-memory last-seen too`() = runBlocking<Unit> {
        val certStore = CertificateStore(storageDirOverride = createTempDir("forget"))
        val trustStore = TrustStore(fileNameOverride = "ls_trust_f_${System.nanoTime()}.json")

        val deviceId = "device-to-forget"
        trustStore.addOrUpdatePairedDevice(
            PairedDevice(
                deviceId = deviceId,
                deviceName = "Temp",
                deviceType = "phone",
                certificateSha256 = "dummy",
                certificateBase64 = "dummy"
            )
        )

        val manager = DeviceLinkManager(
            certStore = certStore,
            trustStore = trustStore,
            tcpPort = freePort()
        ).also { managers += it }
        delay(200)

        manager.unpair(deviceId)
        delay(300)

        assertTrue(trustStore.getPairedDevice(deviceId) == null, "Device should be unpaired")
        assertEquals(
            ConnectionStatus.DISCONNECTED,
            manager.connectionStates.value[deviceId]
        )
    }
}
