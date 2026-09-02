package courier.link

import courier.platform.readTextFile
import courier.platform.saveTextFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.cert.X509Certificate

class TrustStore {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val _pairedDevices = MutableStateFlow<List<PairedDevice>>(emptyList())
    val pairedDevices: StateFlow<List<PairedDevice>> = _pairedDevices.asStateFlow()

    init {
        loadTrustedDevices()
    }

    private fun loadTrustedDevices() {
        val raw = readTextFile(TRUST_STORE_FILENAME)
        if (!raw.isNullOrBlank()) {
            try {
                val list = json.decodeFromString<List<PairedDevice>>(raw)
                _pairedDevices.value = list
            } catch (e: Exception) {
                println("Failed to parse trusted devices: ${e.message}")
            }
        }
    }

    private fun saveTrustedDevices() {
        try {
            val raw = json.encodeToString(_pairedDevices.value)
            saveTextFile(TRUST_STORE_FILENAME, raw)
        } catch (e: Exception) {
            println("Failed to save trusted devices: ${e.message}")
        }
    }

    fun isPaired(deviceId: String): Boolean {
        return _pairedDevices.value.any { it.deviceId == deviceId }
    }

    fun getPairedDevice(deviceId: String): PairedDevice? {
        return _pairedDevices.value.firstOrNull { it.deviceId == deviceId }
    }

    fun addOrUpdatePairedDevice(device: PairedDevice) {
        val current = _pairedDevices.value.toMutableList()
        val index = current.indexOfFirst { it.deviceId == device.deviceId }
        if (index >= 0) {
            current[index] = device
        } else {
            current.add(device)
        }
        _pairedDevices.value = current
        saveTrustedDevices()
    }

    fun removePairedDevice(deviceId: String) {
        val current = _pairedDevices.value.toMutableList()
        current.removeAll { it.deviceId == deviceId }
        _pairedDevices.value = current
        saveTrustedDevices()
    }

    fun setClipboardSync(deviceId: String, enabled: Boolean) {
        val current = _pairedDevices.value.toMutableList()
        val index = current.indexOfFirst { it.deviceId == deviceId }
        if (index >= 0) {
            current[index] = current[index].copy(isClipboardSyncEnabled = enabled)
            _pairedDevices.value = current
            saveTrustedDevices()
        }
    }

    /**
     * Validates a peer's certificate against the pinned certificate.
     *
     * SECURITY RULE (CVE-2020-26164 Mitigation §1.4):
     * If the certificate SHA-256 fingerprint does not match the pinned fingerprint,
     * this method logs a security alert and returns FALSE.
     * It NEVER automatically unpairs or overwrites the pinned certificate.
     */
    fun validatePinnedCertificate(deviceId: String, cert: X509Certificate): Boolean {
        val pinned = getPairedDevice(deviceId) ?: return false
        val incomingFingerprint = CertificateStore.computeSha256(cert.encoded)
        if (pinned.certificateSha256.equals(incomingFingerprint, ignoreCase = true)) {
            return true
        } else {
            println("[SECURITY ALERT] Certificate mismatch for paired device $deviceId! Expected: ${pinned.certificateSha256}, Got: $incomingFingerprint. Refusing connection.")
            return false
        }
    }

    companion object {
        private const val TRUST_STORE_FILENAME = "courier_trusted_devices.json"
    }
}