package courier.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import courier.model.Platform
import courier.model.VideoFormat
import courier.model.VideoInfo
import courier.platform.getPlatformActions
import courier.ui.theme.AccentCyan
import courier.ui.theme.CardBorderDark
import courier.ui.theme.CardBorderFocused
import courier.ui.theme.GlassBorderGradient
import courier.ui.theme.PrimaryIndigo
import courier.ui.theme.PrimaryIndigoLight
import courier.ui.theme.SuccessGreen
import courier.ui.theme.SurfaceCard
import courier.ui.theme.SurfaceDark
import courier.ui.theme.SurfaceVariantDark
import courier.ui.theme.WarningOrange
import courier.ui.theme.TextMuted
import courier.ui.theme.TextPrimary
import courier.ui.theme.TextSecondary

@Composable
fun QualityPickerDialog(
    videoInfo: VideoInfo,
    defaultDownloadDir: String,
    savedLocations: List<String>,
    defaultQuality: String = "best",
    pairedDevices: List<courier.link.PairedDevice> = emptyList(),
    onSendToDevice: ((targetDeviceId: String, format: VideoFormat?, isAudioOnly: Boolean) -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (format: VideoFormat?, isAudioOnly: Boolean, destinationDir: String?) -> Unit
) {
    val initialAudioOnly = defaultQuality == "audio_only"
    var isAudioOnly by remember { mutableStateOf(initialAudioOnly) }
    var selectedLocation by remember { mutableStateOf(defaultDownloadDir) }

    val isVideoLike = videoInfo.mediaType == courier.model.MediaType.VIDEO || videoInfo.mediaType == courier.model.MediaType.AUDIO
    val videoFormats = remember(videoInfo) {
        val extracted = videoInfo.formats.filter { !it.isAudioOnly }
        if (!isVideoLike) {
            extracted
        } else {
            val standardList = listOf(
                VideoFormat("best", "Best Available Quality", resolution = "Highest", ext = "mp4"),
                VideoFormat("1080p", "1080p Full HD", resolution = "1080p", ext = "mp4"),
                VideoFormat("720p", "720p HD", resolution = "720p", ext = "mp4"),
                VideoFormat("480p", "480p SD", resolution = "480p", ext = "mp4"),
                VideoFormat("360p", "360p Standard", resolution = "360p", ext = "mp4")
            )
            if (extracted.isEmpty()) {
                standardList
            } else {
                val combined = mutableListOf<VideoFormat>()
                combined.add(VideoFormat("best", "Best Available Quality", resolution = "Highest", ext = "mp4"))
                for (f in extracted) {
                    if (f.formatId != "best" && combined.none { it.resolution == f.resolution }) {
                        combined.add(f)
                    }
                }
                if (combined.size <= 1) standardList else combined
            }
        }
    }

    val audioFormats = remember(videoInfo) {
        val extracted = videoInfo.formats.filter { it.isAudioOnly }
        val standardAudio = listOf(
            VideoFormat("bestaudio", "Best Audio Quality (M4A / Original)", ext = "m4a", isAudioOnly = true),
            VideoFormat("mp3", "MP3 Audio (Converted 320kbps)", ext = "mp3", isAudioOnly = true)
        )
        if (extracted.isEmpty()) standardAudio else standardAudio
    }

    var selectedFormat by remember(isAudioOnly, videoInfo, defaultQuality) {
        if (isAudioOnly) {
            mutableStateOf(audioFormats.firstOrNull())
        } else {
            val preselected = when (defaultQuality) {
                "1080p" -> videoFormats.find { it.resolution == "1080p" || it.formatId == "1080p" }
                "720p" -> videoFormats.find { it.resolution == "720p" || it.formatId == "720p" }
                "480p" -> videoFormats.find { it.resolution == "480p" || it.formatId == "480p" }
                "360p" -> videoFormats.find { it.resolution == "360p" || it.formatId == "360p" }
                else -> videoFormats.firstOrNull()
            } ?: videoFormats.firstOrNull()
            mutableStateOf(preselected)
        }
    }

    val platformColor = Color(videoInfo.platform.brandColorHex)
    val scope = rememberCoroutineScope()
    var showLocationPicker by remember { mutableStateOf(false) }

    if (showLocationPicker) {
        DownloadLocationPickerDialog(
            onDismissRequest = { showLocationPicker = false },
            onLocationSelected = { path ->
                selectedLocation = path
                showLocationPicker = false
            }
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            shape = RoundedCornerShape(26.dp),
            color = SurfaceDark,
            border = androidx.compose.foundation.BorderStroke(1.5.dp, GlassBorderGradient)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Choose Format & Target",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .background(SurfaceVariantDark, CircleShape)
                            .border(1.dp, CardBorderDark, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Video Preview Card
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceCard, RoundedCornerShape(14.dp))
                        .border(1.dp, CardBorderDark, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp, 52.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceVariantDark)
                            .border(1.dp, CardBorderDark, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.VideoLibrary,
                            contentDescription = null,
                            tint = platformColor,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = videoInfo.title,
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .background(platformColor.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = videoInfo.platform.displayName,
                                    color = platformColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (videoInfo.formattedDuration.isNotBlank()) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = videoInfo.formattedDuration,
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Mode Selector: Segmented Tabs (Video vs Audio Only)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceVariantDark, RoundedCornerShape(12.dp))
                        .border(1.dp, CardBorderDark, RoundedCornerShape(12.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Video Mode Tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (!isAudioOnly) PrimaryIndigo else Color.Transparent,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                isAudioOnly = false
                                selectedFormat = videoFormats.firstOrNull()
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Videocam,
                                contentDescription = null,
                                tint = if (!isAudioOnly) Color.White else TextMuted,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Video (MP4)",
                                color = if (!isAudioOnly) Color.White else TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = if (!isAudioOnly) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }

                    // Audio Only Tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (isAudioOnly) AccentCyan else Color.Transparent,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                isAudioOnly = true
                                selectedFormat = audioFormats.firstOrNull()
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Audiotrack,
                                contentDescription = null,
                                tint = if (isAudioOnly) Color.Black else TextMuted,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Audio Only",
                                color = if (isAudioOnly) Color.Black else TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = if (isAudioOnly) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Quality Options List
                Text(
                    text = if (isAudioOnly) "Select Audio Format" else "Select Video Resolution",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    modifier = Modifier.padding(start = 2.dp, bottom = 6.dp)
                )

                val displayFormats = if (isAudioOnly) audioFormats else videoFormats

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (fmt in displayFormats) {
                        val isSelected = selectedFormat?.formatId == fmt.formatId
                        val itemBg = if (isSelected) PrimaryIndigo.copy(alpha = 0.28f) else SurfaceCard
                        val itemBorder = if (isSelected) AccentCyan else CardBorderDark

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(itemBg, RoundedCornerShape(10.dp))
                                .border(1.dp, itemBorder, RoundedCornerShape(10.dp))
                                .selectable(
                                    selected = isSelected,
                                    role = Role.RadioButton,
                                    onClick = { selectedFormat = fmt }
                                )
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedFormat = fmt },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = AccentCyan,
                                        unselectedColor = TextMuted
                                    ),
                                    modifier = Modifier.size(18.dp)
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                Text(
                                    text = fmt.displayLabel,
                                    color = if (isSelected) TextPrimary else TextSecondary,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Editor-compatibility badge. Only meaningful when the
                                // codec is actually known — the synthetic preset entries
                                // ("1080p", "best") carry no vcodec, so they get no badge
                                // rather than a guessed one.
                                if (!isAudioOnly && fmt.vcodec != null) {
                                    val friendly = fmt.isEditorFriendly
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                if (friendly) SuccessGreen.copy(alpha = 0.18f)
                                                else WarningOrange.copy(alpha = 0.18f),
                                                RoundedCornerShape(5.dp)
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = if (friendly) "Editor-ready" else "Needs conversion",
                                            color = if (friendly) SuccessGreen else WarningOrange,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                }

                                if (fmt.ext.isNotBlank()) {
                                    Box(
                                        modifier = Modifier
                                            .background(SurfaceVariantDark, RoundedCornerShape(5.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = fmt.ext.uppercase(),
                                            color = AccentCyan,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Target Destination Folder Quick-Selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceCard, RoundedCornerShape(10.dp))
                        .border(1.dp, CardBorderDark, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Save to:",
                                color = TextMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = selectedLocation.ifBlank { getPlatformActions().getDefaultDownloadDirectory() },
                                color = TextPrimary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Browse Button
                    Box(
                        modifier = Modifier
                            .background(SurfaceVariantDark, RoundedCornerShape(6.dp))
                            .border(1.dp, CardBorderDark, RoundedCornerShape(6.dp))
                            .clickable {
                                if (getPlatformActions().isAndroid()) {
                                    showLocationPicker = true
                                } else {
                                    scope.launch {
                                        val chosen = getPlatformActions().chooseDirectory()
                                        if (!chosen.isNullOrBlank()) {
                                            selectedLocation = chosen
                                        }
                                    }
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Change",
                            color = AccentCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (pairedDevices.isNotEmpty() && onSendToDevice != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "OR DOWNLOAD TO PAIRED DEVICE",
                        color = AccentCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    pairedDevices.forEach { dev ->
                        Button(
                            onClick = { onSendToDevice(dev.deviceId, selectedFormat, isAudioOnly) },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SurfaceCard,
                                contentColor = TextPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .border(1.dp, PrimaryIndigo.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        ) {
                            Icon(
                                imageVector = if (dev.deviceType == "phone") Icons.Default.PhoneAndroid else Icons.Default.Computer,
                                contentDescription = null,
                                tint = AccentCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Send to ${dev.deviceName}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SurfaceVariantDark,
                            contentColor = TextSecondary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .border(1.dp, CardBorderDark, RoundedCornerShape(12.dp))
                    ) {
                        Text("Cancel", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = { onConfirm(selectedFormat, isAudioOnly, selectedLocation.ifBlank { null }) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryIndigo,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .weight(1.5f)
                            .height(46.dp)
                            .border(1.dp, AccentCyan.copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Download", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
