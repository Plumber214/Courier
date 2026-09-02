package courier.link

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IdentityAndRenameTest {

    private val tempDirs = mutableListOf<File>()
    private val managers = mutableListOf<DeviceLinkManager>()

    @AfterTest
    fun cleanUp() {
        managers.forEach { runCatching { it.stop() } }
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun createTempDir(label: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "courier-rename-test-$label-${System.nanoTime()}")
        dir.mkdirs()
        tempDirs += dir
        return dir
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `discovery announcement never leaks friendly name in public broadcast`() {
        val dir = createTempDir("leak-check")
        val certStore = CertificateStore(storageDirOverride = dir)
        certStore.setDeviceName("Nathan's Private Laptop")

        val identity = DeviceIdentity(
            deviceId = certStore.deviceId,
            deviceName = certStore.getDeviceName(),
            deviceType = "desktop"
        )
        val discovery = Discovery(identityProvider = { identity })

        val publicIdentity = discovery.getPublicIdentity()

        // Assert public generic name
        assertTrue(publicIdentity.deviceName.startsWith("Courier device"), "Expected generic public name")
        assertFalse(publicIdentity.deviceName.contains("Nathan"), "Public announcement leaked private owner name!")
        assertFalse(publicIdentity.deviceName.contains("Private"), "Public announcement leaked private device name!")
    }

    @Test
    fun `rename on node A propagates over live TLS link to node B and persists across restart`() = runBlocking<Unit> {
        val portA = freePort()
        val portB = freePort()

        val dirA = createTempDir("nodeA")
        val dirB = createTempDir("nodeB")

        val certA = CertificateStore(storageDirOverride = dirA)
        val certB = CertificateStore(storageDirOverride = dirB)
        certA.setDeviceName("Node-A-Original")
        certB.setDeviceName("Node-B-Original")

        val trustFileA = "test_trust_a_${System.nanoTime()}.json"
        val trustFileB = "test_trust_b_${System.nanoTime()}.json"
        val trustA = TrustStore(fileNameOverride = trustFileA)
        val trustB = TrustStore(fileNameOverride = trustFileB)

        val managerA = DeviceLinkManager(certStore = certA, trustStore = trustA, tcpPort = portA).also { managers += it }
        val managerB = DeviceLinkManager(certStore = certB, trustStore = trustB, tcpPort = portB).also { managers += it }

        managerB.linkServer.start(portB)
        delay(150)

        // Connect outbound from A to B
        val connectResult = managerA.linkServer.connectOutbound("127.0.0.1", portB)
        assertTrue(connectResult.isSuccess, "Outbound connection failed: ${connectResult.exceptionOrNull()?.message}")
        val linkA = connectResult.getOrThrow()

        // Pair A and B
        val discB = DiscoveredDevice(
            identity = DeviceIdentity(deviceId = certB.deviceId, deviceName = "Node-B-Original", deviceType = "desktop", tcpPort = portB),
            hostAddress = "127.0.0.1",
            tcpPort = portB
        )
        managerA.pairingManager.initiatePairing(linkA, device = discB)
        delay(300)
        managerB.pairingManager.acceptPairing()
        delay(300)

        // Verify initial pairing saved friendly names
        val pairedAonB = trustB.getPairedDevice(certA.deviceId)
        assertNotNull(pairedAonB, "Node A was not paired in Node B trust store")
        assertEquals("Node-A-Original", pairedAonB.deviceName)

        // Now Node A renames itself to "Studio Workstation"
        managerA.updateDeviceName("Studio Workstation")

        // Wait for propagation over TLS
        val updatedNameReceived = withTimeoutOrNull(5000L) {
            while (trustB.getPairedDevice(certA.deviceId)?.deviceName != "Studio Workstation") {
                delay(100)
            }
            true
        }
        assertNotNull(updatedNameReceived, "Node B did not receive the rename update from Node A within timeout")

        // Verify persistence by reading fresh TrustStore from disk (simulating Node B restart)
        val freshTrustB = TrustStore(fileNameOverride = trustFileB)
        val reloadedPairedA = freshTrustB.getPairedDevice(certA.deviceId)
        assertNotNull(reloadedPairedA)
        assertEquals("Studio Workstation", reloadedPairedA.deviceName, "Device rename did not survive restart!")
    }
}