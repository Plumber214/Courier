package courier.viewmodel

import courier.link.ClipboardSyncManager
import courier.link.ConnectionStatus
import courier.link.DeviceIdentity
import courier.link.DeviceLinkManager
import courier.link.DiscoveredDevice
import courier.link.LinkConstants
import courier.link.PairedDevice
import courier.link.PairingSessionState
import courier.link.SendClipboardResult
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
    val myIdentity: StateFlow<DeviceIdentity> = linkManager.myIdentity

    val pairedDevices: StateFlow<List<PairedDevice>> = linkManager.trustStore.pairedDevices
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = linkManager.discovery.discoveredDevices
    val connectionStates: StateFlow<Map<String, ConnectionStatus>> = linkManager.connectionStates
    val pairingState: StateFlow<PairingSessionState> = linkManager.pairingManager.pairingState

    private val _manualConnectStatus = MutableStateFlow<String?>(null)
    val manualConnectStatus: StateFlow<String?> = _manualConnectStatus.asStateFlow()

    private val _clipboardStatusMessage = MutableStateFlow<String?>(null)
    val clipboardStatusMessage: StateFlow<String?> = _clipboardStatusMessage.asStateFlow()

    val isScanning: StateFlow<Boolean> = linkManager.discovery.isScanning

    private val _showRenameDialog = MutableStateFlow(false)
    val showRenameDialog: StateFlow<Boolean> = _showRenameDialog.asStateFlow()

    init {
        scope.launch {
            courier.di.AppModule.clipboardSyncManager.clipboardReceivedEvents.collect { event ->
                _clipboardStatusMessage.value = "Clipboard received from ${event.senderDeviceName}"
            }
        }
    }

    fun setDevicesTabActive(active: Boolean) {
        linkManager.setDevicesTabActive(active)
    }

    fun openRenameDialog() {
        _showRenameDialog.value = true
    }

    fun closeRenameDialog() {
        _showRenameDialog.value = false
    }

    fun submitNewDeviceName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotBlank()) {
            linkManager.updateDeviceName(trimmed)
        }
        _showRenameDialog.value = false
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
        // Goes through the manager, not the pairing manager directly, so the
        // live link and the persisted replay mark are torn down too.
        linkManager.unpair(deviceId)
    }

    fun sendClipboard(deviceId: String) {
        val result = courier.di.AppModule.clipboardSyncManager.sendClipboardToDevice(deviceId)
        when (result) {
            is SendClipboardResult.Success -> {
                _clipboardStatusMessage.value = "Clipboard sent to ${result.deviceName}"
            }
            is SendClipboardResult.DeviceOffline -> {
                _clipboardStatusMessage.value = "${result.deviceName} is offline"
            }
            is SendClipboardResult.EmptyClipboard -> {
                _clipboardStatusMessage.value = "Clipboard is empty"
            }
            is SendClipboardResult.Error -> {
                _clipboardStatusMessage.value = "Failed to send: ${result.message}"
            }
        }
    }

    fun clearClipboardStatusMessage() {
        _clipboardStatusMessage.value = null
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