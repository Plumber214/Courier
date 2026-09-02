package courier.link

import courier.platform.getPlatformActions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExplicitClipboardTest {

    private val tempDirs = mutableListOf<File>()
    private val managers = mutableListOf<DeviceLinkManager>()

    @AfterTest
    fun cleanUp() {
        managers.forEach { runCatching { it.stop() } }
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun createTempDir(label: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "courier-clip-test-$label-${System.nanoTime()}")
        dir.mkdirs()
        tempDirs += dir
        return dir
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `sendClipboardToDevice reports offline when device is not connected`() {
        val dir = createTempDir("offline")
        val certStore = CertificateStore(storageDirOverride = dir)
        val trustStore = TrustStore(fileNameOverride = "test_trust_clip_off_${System.nanoTime()}.json")

        val targetDev = PairedDevice(
            deviceId = "target-123",
            deviceName = "Pixel 11 Pro",
            deviceType = "phone",
            certificateSha256 = "dummy",
            certificateBase64 = "dummy"
        )
        trustStore.addOrUpdatePairedDevice(targetDev)

        getPlatformActions().setClipboardText("https://youtube.com/watch?v=test")

        val manager = DeviceLinkManager(certStore = certStore, trustStore = trustStore).also { managers += it }
        val clipboardManager = ClipboardSyncManager(manager)

        val result = clipboardManager.sendClipboardToDevice("target-123")
        assertIs<SendClipboardResult.DeviceOffline>(result)
        assertEquals("Pixel 11 Pro", result.deviceName)
    }

    @Test
    fun `sendClipboardToDevice reports empty when clipboard is blank`() {
        val dir = createTempDir("empty")
        val certStore = CertificateStore(storageDirOverride = dir)
        val trustStore = TrustStore(fileNameOverride = "test_trust_clip_empty_${System.nanoTime()}.json")

        val targetDev = PairedDevice(
            deviceId = "target-456",
            deviceName = "MacBook Pro",
            deviceType = "desktop",
            certificateSha256 = "dummy",
            certificateBase64 = "dummy"
        )
        trustStore.addOrUpdatePairedDevice(targetDev)

        getPlatformActions().setClipboardText("")

        val manager = DeviceLinkManager(certStore = certStore, trustStore = trustStore).also { managers += it }
        val clipboardManager = ClipboardSyncManager(manager)

        val result = clipboardManager.sendClipboardToDevice("target-456")
        assertIs<SendClipboardResult.EmptyClipboard>(result)
    }

    @Test
    fun `explicit send transfers clipboard over TLS and confirms on receiver with sender name`() = runBlocking<Unit> {
        val dirA = createTempDir("nodeA")
        val dirB = createTempDir("nodeB")

        val certA = CertificateStore(storageDirOverride = dirA).also { it.setDeviceName("Studio Desktop") }
        val certB = CertificateStore(storageDirOverride = dirB).also { it.setDeviceName("Pixel 11 Pro") }

        val trustA = TrustStore(fileNameOverride = "trust_a_${System.nanoTime()}.json")
        val trustB = TrustStore(fileNameOverride = "trust_b_${System.nanoTime()}.json")

        // Cross-pair in TrustStores
        trustA.addOrUpdatePairedDevice(
            PairedDevice(
                deviceId = certB.deviceId,
                deviceName = "Pixel 11 Pro",
                deviceType = "phone",
                certificateSha256 = certB.certificateSha256,
                certificateBase64 = certB.certificateBase64
            )
        )
        trustB.addOrUpdatePairedDevice(
            PairedDevice(
                deviceId = certA.deviceId,
                deviceName = "Studio Desktop",
                deviceType = "desktop",
                certificateSha256 = certA.certificateSha256,
                certificateBase64 = certA.certificateBase64
            )
        )

        val portA = freePort()
        val portB = freePort()

        val managerA = DeviceLinkManager(certStore = certA, trustStore = trustA, tcpPort = portA).also { managers += it }
        val managerB = DeviceLinkManager(certStore = certB, trustStore = trustB, tcpPort = portB).also { managers += it }

        val clipManagerA = ClipboardSyncManager(managerA)
        val clipManagerB = ClipboardSyncManager(managerB)
        clipManagerB.start()
        delay(200)

        // Connect A -> B
        val connectResult = managerA.linkServer.connectOutbound("127.0.0.1", portB)
        assertTrue(connectResult.isSuccess, "Connection A->B failed: ${connectResult.exceptionOrNull()?.message}")
        delay(300)

        // Set local clipboard on Node A
        val testUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        getPlatformActions().setClipboardText(testUrl)

        // Receiver event queue
        val receivedEvents = LinkedBlockingQueue<ClipboardReceivedEvent>()
        val collectJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            clipManagerB.clipboardReceivedEvents.collect { receivedEvents.offer(it) }
        }

        // Send explicitly from Node A to Node B
        val sendResult = clipManagerA.sendClipboardToDevice(certB.deviceId)
        assertIs<SendClipboardResult.Success>(sendResult)
        assertEquals("Pixel 11 Pro", sendResult.deviceName)

        val event = receivedEvents.poll(5, TimeUnit.SECONDS)
        collectJob.cancel()
        clipManagerB.stop()

        assertTrue(event != null, "Node B never received the clipboard packet")
        assertEquals(certA.deviceId, event.senderDeviceId)
        assertEquals("Studio Desktop", event.senderDeviceName)
        assertEquals(testUrl, getPlatformActions().getClipboardText())
    }
}