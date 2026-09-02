package courier.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import courier.link.ConnectionStatus
import courier.link.DiscoveredDevice
import courier.link.PairedDevice
import courier.link.PairingSessionState
import courier.platform.getPlatformActions
import courier.ui.theme.AccentCyan
import courier.ui.theme.AccentPink
import courier.ui.theme.CardBorderDark
import courier.ui.theme.GlassBorderGradient
import courier.ui.theme.PrimaryContainer
import courier.ui.theme.PrimaryIndigo
import courier.ui.theme.SuccessGreen
import courier.ui.theme.SurfaceCard
import courier.ui.theme.SurfaceDark
import courier.ui.theme.SurfaceVariantDark
import courier.ui.theme.TextMuted
import courier.ui.theme.TextPrimary
import courier.ui.theme.TextSecondary
import courier.ui.theme.WarningOrange
import courier.viewmodel.DevicesViewModel

@Composable
fun DevicesScreen(
    viewModel: DevicesViewModel,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val pairedDevices by viewModel.pairedDevices.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val connectionStates by viewModel.connectionStates.collectAsState()
    val pairingState by viewModel.pairingState.collectAsState()
    val manualConnectStatus by viewModel.manualConnectStatus.collectAsState()

    var manualIpText by remember { mutableStateOf("") }
    var showManualIpDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Device Link",
                    color = TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Local network sync & remote downloads",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }

            IconButton(
                onClick = { viewModel.refreshDiscovery() },
                modifier = Modifier
                    .background(SurfaceVariantDark.copy(alpha = 0.5f), CircleShape)
                    .border(1.dp, CardBorderDark, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh Discovery",
                    tint = AccentCyan
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Local Device Identity Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark, RoundedCornerShape(16.dp))
                .border(1.dp, CardBorderDark, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(PrimaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (viewModel.myIdentity.deviceType == "phone") Icons.Default.PhoneAndroid else Icons.Default.Computer,
                        contentDescription = null,
                        tint = PrimaryIndigo,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = "This Device: ${viewModel.myIdentity.deviceName}",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "ID: ${viewModel.myIdentity.deviceId.take(8)}... • Port ${viewModel.myIdentity.tcpPort}",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Paired Devices Section
        Text(
            text = "PAIRED DEVICES",
            color = AccentCyan,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (pairedDevices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceCard, RoundedCornerShape(14.dp))
                    .border(1.dp, CardBorderDark, RoundedCornerShape(14.dp))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Devices,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No paired devices yet",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Devices discovered on your Wi-Fi will appear below",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                pairedDevices.forEach { device ->
                    val status = connectionStates[device.deviceId] ?: ConnectionStatus.DISCONNECTED
                    PairedDeviceCard(
                        device = device,
                        status = status,
                        onToggleClipboard = { viewModel.toggleClipboardSync(device.deviceId, it) },
                        onUnpair = { viewModel.unpair(device.deviceId) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Discovered Devices Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "DISCOVERED NEARBY",
                color = AccentCyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            TextButton(onClick = { showManualIpDialog = true }) {
                Text("+ Add by IP", color = PrimaryIndigo, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        val unpairDiscovered = discoveredDevices.filter { disc -> !pairedDevices.any { it.deviceId == disc.identity.deviceId } }

        if (unpairDiscovered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceCard, RoundedCornerShape(14.dp))
                    .border(1.dp, CardBorderDark, RoundedCornerShape(14.dp))
                    .padding(18.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = AccentCyan,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Scanning LAN for Courier devices...",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                unpairDiscovered.forEach { device ->
                    DiscoveredDeviceCard(
                        device = device,
                        onPairClick = { viewModel.initiatePairing(device) }
                    )
                }
            }
        }

        if (manualConnectStatus != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = manualConnectStatus ?: "",
                color = AccentCyan,
                fontSize = 12.sp
            )
        }
    }

    // Pairing Verification Code Dialog
    when (val state = pairingState) {
        is PairingSessionState.IncomingRequest -> {
            PairingVerificationDialog(
                title = "Pairing Request",
                deviceName = state.device.identity.deviceName,
                verificationCode = state.verificationCode,
                isIncoming = true,
                onConfirm = { viewModel.acceptPairing() },
                onDismiss = { viewModel.rejectPairing() }
            )
        }
        is PairingSessionState.OutgoingRequest -> {
            PairingVerificationDialog(
                title = "Pairing with Device",
                deviceName = state.device.identity.deviceName,
                verificationCode = state.verificationCode,
                isIncoming = false,
                onConfirm = { viewModel.acceptPairing() },
                onDismiss = { viewModel.cancelPairing() }
            )
        }
        is PairingSessionState.Error -> {
            AlertDialog(
                onDismissRequest = { viewModel.cancelPairing() },
                title = { Text("Pairing Error", color = TextPrimary) },
                text = { Text(state.message, color = TextSecondary) },
                confirmButton = {
                    TextButton(onClick = { viewModel.cancelPairing() }) {
                        Text("OK", color = AccentCyan)
                    }
                },
                containerColor = SurfaceDark
            )
        }
        is PairingSessionState.Idle -> {}
    }

    // Manual Add-By-IP Dialog
    if (showManualIpDialog) {
        AlertDialog(
            onDismissRequest = { showManualIpDialog = false },
            title = { Text("Connect to Device by IP", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Enter the local IP address of the Courier instance on your LAN:",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = manualIpText,
                        onValueChange = { manualIpText = it },
                        placeholder = { Text("e.g. 192.168.1.50", color = TextMuted) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = CardBorderDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showManualIpDialog = false
                        viewModel.connectManualIp(manualIpText)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
                ) {
                    Text("Connect", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualIpDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
        )
    }
}

@Composable
fun PairedDeviceCard(
    device: PairedDevice,
    status: ConnectionStatus,
    onToggleClipboard: (Boolean) -> Unit,
    onUnpair: () -> Unit
) {
    val statusColor = when (status) {
        ConnectionStatus.CONNECTED -> SuccessGreen
        ConnectionStatus.CONNECTING -> WarningOrange
        ConnectionStatus.DISCONNECTED -> TextMuted
        ConnectionStatus.ASLEEP -> TextMuted
    }

    val statusLabel = when (status) {
        ConnectionStatus.CONNECTED -> "Connected"
        ConnectionStatus.CONNECTING -> "Connecting..."
        ConnectionStatus.DISCONNECTED -> "Offline"
        ConnectionStatus.ASLEEP -> "Asleep — will deliver on wake"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(14.dp))
            .border(1.dp, CardBorderDark, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(SurfaceVariantDark, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (device.deviceType == "phone") Icons.Default.PhoneAndroid else Icons.Default.Computer,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = device.deviceName,
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(statusColor, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = statusLabel,
                                color = statusColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                IconButton(onClick = onUnpair) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Unpair",
                        tint = AccentPink.copy(alpha = 0.8f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Clipboard Sync Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDark.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Sync Clipboard",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (getPlatformActions().isAndroid()) {
                            Text(
                                text = "Manual push required while backgrounded on Android",
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                Switch(
                    checked = device.isClipboardSyncEnabled,
                    onCheckedChange = onToggleClipboard,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = PrimaryIndigo,
                        uncheckedTrackColor = SurfaceVariantDark
                    )
                )
            }
        }
    }
}

@Composable
fun DiscoveredDeviceCard(
    device: DiscoveredDevice,
    onPairClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(14.dp))
            .border(1.dp, CardBorderDark, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(SurfaceVariantDark, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (device.identity.deviceType == "phone") Icons.Default.PhoneAndroid else Icons.Default.Computer,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = device.identity.deviceName,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${device.hostAddress}:${device.tcpPort}",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            }

            Button(
                onClick = onPairClick,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryIndigo,
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Pair", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun PairingVerificationDialog(
    title: String,
    deviceName: String,
    verificationCode: String,
    isIncoming: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (isIncoming) {
                        "$deviceName wants to pair with this device. Verify that both screens show the same 8-character code:"
                    } else {
                        "Pairing with $deviceName. Verify that both screens show the same 8-character code:"
                    },
                    color = TextSecondary,
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PrimaryContainer, RoundedCornerShape(12.dp))
                        .border(1.dp, AccentCyan, RoundedCornerShape(12.dp))
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = verificationCode,
                        color = TextPrimary,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "If the codes match, tap Confirm on both devices.",
                    color = TextMuted,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Confirm", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
        containerColor = SurfaceDark
    )
}