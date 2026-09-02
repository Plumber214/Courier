package courier.link

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HonestOfflineStatesTest {

    private val tempDirs = mutableListOf<File>()
    private val managers = mutableListOf<DeviceLinkManager>()

    @AfterTest
    fun cleanUp() {
        managers.forEach { runCatching { it.stop() } }
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun createTempDir(label: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "courier-offline-test-$label-${System.nanoTime()}")
        dir.mkdirs()
        tempDirs += dir
        return dir
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `relative time formatting formats ranges correctly`() {
        val now = System.currentTimeMillis()

        assertEquals("Never", formatRelativeTime(0L))
        assertEquals("Just now", formatRelativeTime(now - 10_000L))
        assertEquals("5m ago", formatRelativeTime(now - 5 * 60 * 1000L))
        assertEquals("3h ago", formatRelativeTime(now - 3 * 3600 * 1000L))
        assertEquals("Yesterday", formatRelativeTime(now - 25 * 3600 * 1000L))
        assertEquals("3d ago", formatRelativeTime(now - 3 * 24 * 3600 * 1000L))
    }

    @Test
    fun `recently disconnected paired device transitions to ASLEEP`() = runBlocking<Unit> {
        val dir = createTempDir("asleep")
        val certStore = CertificateStore(storageDirOverride = dir)
        val trustStore = TrustStore(fileNameOverride = "trust_asleep_${System.nanoTime()}.json")

        val now = System.currentTimeMillis()
        val deviceId = "phone-doze-123"
        val pairedDev = PairedDevice(
            deviceId = deviceId,
            deviceName = "Pixel 11 Pro",
            deviceType = "phone",
            certificateSha256 = "dummy",
            certificateBase64 = "dummy",
            lastSeenEpochMs = now - 60_000L // Seen 1 minute ago (within 15m ASLEEP window)
        )
        trustStore.addOrUpdatePairedDevice(pairedDev)

        val port = freePort()
        val manager = DeviceLinkManager(certStore = certStore, trustStore = trustStore, tcpPort = port).also { managers += it }
        delay(300)

        // Attempt reconnect with unreachable host: status must read ASLEEP rather than DISCONNECTED
        val status = manager.connectionStates.value[deviceId]
        assertEquals(ConnectionStatus.ASLEEP, status, "Expected ASLEEP status for recently seen paired device")
    }

    @Test
    fun `stale offline paired device transitions to DISCONNECTED`() = runBlocking<Unit> {
        val dir = createTempDir("stale")
        val certStore = CertificateStore(storageDirOverride = dir)
        val trustStore = TrustStore(fileNameOverride = "trust_stale_${System.nanoTime()}.json")

        val now = System.currentTimeMillis()
        val deviceId = "old-phone-456"
        val pairedDev = PairedDevice(
            deviceId = deviceId,
            deviceName = "Old Galaxy S20",
            deviceType = "phone",
            certificateSha256 = "dummy",
            certificateBase64 = "dummy",
            lastSeenEpochMs = now - 2 * 3600 * 1000L // Seen 2 hours ago (past 15m ASLEEP window)
        )
        trustStore.addOrUpdatePairedDevice(pairedDev)

        val port = freePort()
        val manager = DeviceLinkManager(certStore = certStore, trustStore = trustStore, tcpPort = port).also { managers += it }
        delay(300)

        val status = manager.connectionStates.value[deviceId]
        assertEquals(ConnectionStatus.DISCONNECTED, status, "Expected DISCONNECTED status for stale paired device")
    }

    @Test
    fun `paired offline devices preserve and surface friendly names`() {
        val dir = createTempDir("friendly")
        val trustStore = TrustStore(fileNameOverride = "trust_friendly_${System.nanoTime()}.json")

        val pairedDev = PairedDevice(
            deviceId = "dev-abc",
            deviceName = "Nathan's Studio Laptop",
            deviceType = "desktop",
            certificateSha256 = "dummy",
            certificateBase64 = "dummy"
        )
        trustStore.addOrUpdatePairedDevice(pairedDev)

        val retrieved = trustStore.getPairedDevice("dev-abc")
        assertTrue(retrieved != null)
        assertEquals("Nathan's Studio Laptop", retrieved.deviceName)
    }
}