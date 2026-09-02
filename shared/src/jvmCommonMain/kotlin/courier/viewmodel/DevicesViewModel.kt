package courier.viewmodel

import courier.link.ConnectionStatus
import courier.link.DeviceIdentity
import courier.link.DeviceLinkManager
import courier.link.DiscoveredDevice
import courier.link.LinkConstants
import courier.link.PairedDevice
import courier.link.PairingSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DevicesViewModel(
    private val linkManager: DeviceLinkManager = DeviceLinkManager.getInstance(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    val myIdentity: DeviceIdentity = linkManager.myIdentity

    val pairedDevices: StateFlow<List<PairedDevice>> = linkManager.trustStore.pairedDevices
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = linkManager.discovery.discoveredDevices
    val connectionStates: StateFlow<Map<String, ConnectionStatus>> = linkManager.connectionStates
    val pairingState: StateFlow<PairingSessionState> = linkManager.pairingManager.pairingState

    private val _manualConnectStatus = MutableStateFlow<String?>(null)
    val manualConnectStatus: StateFlow<String?> = _manualConnectStatus.asStateFlow()

    init {
        linkManager.start()
    }

    fun refreshDiscovery() {
        linkManager.kickNetwork()
    }

    fun initiatePairing(device: DiscoveredDevice) {
        scope.launch {
            _manualConnectStatus.value = "Connecting to ${device.identity.deviceName}..."
            val result = linkManager.linkServer.connectOutbound(device.hostAddress, device.tcpPort)
            result.fold(
                onSuccess = { link ->
                    _manualConnectStatus.value = null
                    linkManager.pairingManager.initiatePairing(link, device)
                },
                onFailure = { err ->
                    _manualConnectStatus.value = "Failed to connect: ${err.message ?: "Network error"}"
                }
            )
        }
    }

    fun acceptPairing() {
        linkManager.pairingManager.acceptPairing()
    }

    fun rejectPairing() {
        linkManager.pairingManager.rejectPairing()
    }

    fun cancelPairing() {
        linkManager.pairingManager.cancelPairing()
    }

    fun unpair(deviceId: String) {
        linkManager.pairingManager.unpair(deviceId)
    }

    fun toggleClipboardSync(deviceId: String, enabled: Boolean) {
        linkManager.trustStore.setClipboardSync(deviceId, enabled)
    }

    fun pushClipboard() {
        courier.di.AppModule.clipboardSyncManager.pushClipboardToPairedDevices()
    }

    fun connectManualIp(ip: String, port: Int = LinkConstants.DEFAULT_PORT) {
        if (ip.isBlank()) return
        scope.launch {
            _manualConnectStatus.value = "Connecting to $ip:$port..."
            val result = linkManager.connectToManualIp(ip.trim(), port)
            result.fold(
                onSuccess = { link ->
                    _manualConnectStatus.value = "Connected to ${link.peerIdentity.deviceName}! Initiating pairing..."
                    val discDevice = DiscoveredDevice(
                        identity = link.peerIdentity,
                        hostAddress = ip.trim(),
                        tcpPort = port
                    )
                    linkManager.pairingManager.initiatePairing(link, discDevice)
                },
                onFailure = { err ->
                    _manualConnectStatus.value = "Connection failed: ${err.message ?: "Check IP address and firewall"}"
                }
            )
        }
    }

    fun clearManualConnectStatus() {
        _manualConnectStatus.value = null
    }
}