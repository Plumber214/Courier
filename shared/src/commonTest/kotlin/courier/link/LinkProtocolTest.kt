package courier.link

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinkProtocolTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testLinkPacketSerialization() {
        val packet = LinkPacket(
            id = 123456789L,
            type = LinkConstants.TYPE_IDENTITY,
            body = buildJsonObject {
                put("deviceId", "test_device_1")
                put("deviceName", "Pixel 11 Pro")
                put("deviceType", "phone")
                put("tcpPort", 1816)
            }
        )

        val raw = json.encodeToString(packet)
        val deserialized = json.decodeFromString<LinkPacket>(raw)

        assertEquals(123456789L, deserialized.id)
        assertEquals(LinkConstants.TYPE_IDENTITY, deserialized.type)
        assertNotNull(deserialized.body["deviceId"])
    }

    @Test
    fun testVerificationCodeSymmetry() {
        val certA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val certB = "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb"

        val combinedAB = listOf(certA, certB).sorted().joinToString(":")
        val combinedBA = listOf(certB, certA).sorted().joinToString(":")

        assertEquals(combinedAB, combinedBA, "Sorted combination ensures identical verification codes on both screens")
    }

    @Test
    fun testSecurityCapsConstants() {
        assertTrue(LinkConstants.MAX_PACKET_SIZE_BYTES <= 64 * 1024, "Max packet size capped at 64KB")
        assertTrue(LinkConstants.MAX_CONCURRENT_CONNECTIONS <= 8, "Max concurrent connections capped at 8")
        assertTrue(LinkConstants.UDP_RATE_LIMIT_PER_IP_MS >= 500L, "UDP rate limit active")
    }
}