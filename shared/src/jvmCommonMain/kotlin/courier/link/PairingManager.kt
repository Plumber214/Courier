package courier.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

sealed class PairingSessionState {
    data object Idle : PairingSessionState()
    data class OutgoingRequest(
        val device: DiscoveredDevice,
        val verificationCode: String
    ) : PairingSessionState()
    data class IncomingRequest(
        val device: DiscoveredDevice,
        val verificationCode: String
    ) : PairingSessionState()
    data class Error(val message: String) : PairingSessionState()
}

class PairingManager(
    private val identityProvider: () -> DeviceIdentity,
    private val certStore: CertificateStore,
    private val trustStore: TrustStore,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    constructor(
        myIdentity: DeviceIdentity,
        certStore: CertificateStore,
        trustStore: TrustStore,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    ) : this({ myIdentity }, certStore, trustStore, scope)

    private val _pairingState = MutableStateFlow<PairingSessionState>(PairingSessionState.Idle)
    val pairingState: StateFlow<PairingSessionState> = _pairingState.asStateFlow()

    private var activePairingLink: SecureLink? = null
    private var pairingTimeoutJob: Job? = null

    fun handleIncomingPairPacket(link: SecureLink, packet: LinkPacket) {
        val isPair = packet.body["pair"]?.jsonPrimitive?.booleanOrNull ?: false
        val isAccepted = packet.body["accepted"]?.jsonPrimitive?.booleanOrNull ?: false
        val peerFriendlyName = packet.body["friendlyName"]?.jsonPrimitive?.contentOrNull
            ?: packet.body["deviceName"]?.jsonPrimitive?.contentOrNull
            ?: link.peerIdentity.deviceName

        if (isPair) {
            if (isAccepted) {
                // Outgoing request accepted by peer
                val peerCert = link.peerCertificate
                val peerCertSha256 = CertificateStore.computeSha256(peerCert.encoded)
                val peerCertBase64 = Base64.getEncoder().encodeToString(peerCert.encoded)

                val pairedDevice = PairedDevice(
                    deviceId = link.peerDeviceId,
                    deviceName = peerFriendlyName,
                    deviceType = link.peerIdentity.deviceType,
                    certificateSha256 = peerCertSha256,
                    certificateBase64 = peerCertBase64,
                    pairedAtEpochMs = System.currentTimeMillis(),
                    lastSeenEpochMs = System.currentTimeMillis()
                )

                trustStore.addOrUpdatePairedDevice(pairedDevice)
                cancelPairing()
            } else {
                // Incoming pair request from peer
                val peerCertSha256 = CertificateStore.computeSha256(link.peerCertificate.encoded)
                val code = certStore.computeVerificationCode(peerCertSha256)
                activePairingLink = link

                val discDevice = DiscoveredDevice(
                    identity = link.peerIdentity.copy(deviceName = peerFriendlyName),
                    hostAddress = "",
                    tcpPort = link.peerIdentity.tcpPort
                )

                _pairingState.value = PairingSessionState.IncomingRequest(
                    device = discDevice,
                    verificationCode = code
                )

                startPairingTimeout()
            }
        } else {
            // Peer rejected or unpaired
            if (trustStore.isPaired(link.peerDeviceId)) {
                trustStore.removePairedDevice(link.peerDeviceId)
            }
            cancelPairing()
        }
    }

    fun initiatePairing(link: SecureLink, device: DiscoveredDevice) {
        activePairingLink = link
        val peerCertSha256 = CertificateStore.computeSha256(link.peerCertificate.encoded)
        val code = certStore.computeVerificationCode(peerCertSha256)

        _pairingState.value = PairingSessionState.OutgoingRequest(
            device = device,
            verificationCode = code
        )

        // Send courier.pair request with friendly name over secure TLS (Decision F3)
        link.sendPacket(
            LinkPacket(
                type = LinkConstants.TYPE_PAIR,
                body = buildJsonObject {
                    put("pair", true)
                    put("friendlyName", identityProvider().deviceName)
                    put("timestamp", System.currentTimeMillis())
                }
            )
        )

        startPairingTimeout()
    }

    fun acceptPairing() {
        val link = activePairingLink ?: return
        val peerCert = link.peerCertificate
        val peerCertSha256 = CertificateStore.computeSha256(peerCert.encoded)
        val peerCertBase64 = Base64.getEncoder().encodeToString(peerCert.encoded)

        val peerName = (_pairingState.value as? PairingSessionState.IncomingRequest)?.device?.identity?.deviceName
            ?: link.peerIdentity.deviceName

        val pairedDevice = PairedDevice(
            deviceId = link.peerDeviceId,
            deviceName = peerName,
            deviceType = link.peerIdentity.deviceType,
            certificateSha256 = peerCertSha256,
            certificateBase64 = peerCertBase64,
            pairedAtEpochMs = System.currentTimeMillis(),
            lastSeenEpochMs = System.currentTimeMillis()
        )

        trustStore.addOrUpdatePairedDevice(pairedDevice)

        // Confirm acceptance back to peer with friendly name over secure TLS (Decision F3)
        link.sendPacket(
            LinkPacket(
                type = LinkConstants.TYPE_PAIR,
                body = buildJsonObject {
                    put("pair", true)
                    put("accepted", true)
                    put("friendlyName", identityProvider().deviceName)
                    put("timestamp", System.currentTimeMillis())
                }
            )
        )

        cancelPairing()
    }

    fun rejectPairing() {
        val link = activePairingLink
        link?.sendPacket(
            LinkPacket(
                type = LinkConstants.TYPE_PAIR,
                body = buildJsonObject {
                    put("pair", false)
                    put("timestamp", System.currentTimeMillis())
                }
            )
        )
        cancelPairing()
    }

    fun unpair(deviceId: String, activeLink: SecureLink? = null) {
        activeLink?.sendPacket(
            LinkPacket(
                type = LinkConstants.TYPE_PAIR,
                body = buildJsonObject {
                    put("pair", false)
                    put("timestamp", System.currentTimeMillis())
                }
            )
        )
        trustStore.removePairedDevice(deviceId)
    }

    private fun startPairingTimeout() {
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = scope.launch {
            delay(LinkConstants.PAIRING_TIMEOUT_MS)
            if (_pairingState.value !is PairingSessionState.Idle) {
                _pairingState.value = PairingSessionState.Error("Pairing timed out")
                delay(3000)
                cancelPairing()
            }
        }
    }

    fun cancelPairing() {
        pairingTimeoutJob?.cancel()
        activePairingLink = null
        _pairingState.value = PairingSessionState.Idle
    }
}