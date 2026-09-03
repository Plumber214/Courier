package courier.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.toLocalDateTime
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import courier.platform.getPlatformActions
import courier.ui.layout.CONTENT_MAX_WIDTH_DP
import courier.ui.layout.LocalWidthClass
import courier.ui.layout.WidthClass
import courier.ui.theme.AccentCyan
import courier.ui.theme.AccentPink
import courier.ui.theme.CardBorderDark
import courier.ui.theme.CardBorderFocused
import courier.ui.theme.PrimaryIndigo
import courier.ui.theme.SurfaceCard
import courier.ui.theme.SurfaceDark
import courier.ui.theme.SurfaceVariantDark
import courier.model.OutputProfile
import courier.model.TranscodeCodec
import courier.ui.theme.TextMuted
import courier.ui.theme.TextPrimary
import courier.ui.theme.WarningOrange
import courier.ui.theme.TextSecondary
import courier.util.AppVersion
import courier.viewmodel.SettingsViewModel

private val QUALITY_OPTIONS = listOf(
    "best" to "Always Best Available (Recommended)",
    "1080p" to "1080p Full HD",
    "720p" to "720p HD",
    "480p" to "480p SD",
    "audio_only" to "Audio Only (MP3/M4A)"
)

private val BROWSER_OPTIONS = listOf("None", "Chrome", "Edge", "Firefox", "Brave")

/**
 * Subtitle languages offered as chips.
 *
 * A short list rather than every code yt-dlp accepts: the point is to make the
 * common case one tap, and a video that publishes none of the selected
 * languages is skipped without failing.
 */
private val SUBTITLE_LANGUAGES = listOf(
    "en" to "English",
    "es" to "Spanish",
    "fr" to "French",
    "de" to "German",
    "pt" to "Portuguese",
    "ja" to "Japanese",
    "ko" to "Korean",
    "zh" to "Chinese"
)

private val OUTPUT_PROFILE_OPTIONS = listOf(
    Triple(
        OutputProfile.EDITING_NATIVE,
        "Editing (Recommended)",
        "H.264 / AAC. Always imports into Premiere, Resolve and Final Cut. Up to 1080p on YouTube."
    ),
    Triple(
        OutputProfile.MAX_QUALITY,
        "Maximum Quality",
        "Up to 4K. Uses AV1 or VP9 — will not import into most editors without conversion."
    ),
    Triple(
        OutputProfile.EDITING_TRANSCODE,
        "Editing, Any Resolution",
        "Up to 4K, then converted for editing. Much slower and produces larger files."
    )
)

private val TRANSCODE_CODEC_OPTIONS = listOf(
    TranscodeCodec.H264 to "H.264 (MP4) — smaller files, universally supported",
    TranscodeCodec.PRORES to "ProRes 422 HQ (MOV) — very large, best scrubbing",
    TranscodeCodec.DNXHR to "DNxHR HQ (MOV) — very large, Avid/Premiere native"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settings.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val showLocationPicker by viewModel.showLocationPickerDialog.collectAsState()
    val showRenameDialog by viewModel.showRenameDialog.collectAsState()
    val myIdentity by viewModel.myDeviceIdentity.collectAsState()
    val pairedDevices by viewModel.pairedDevices.collectAsState()

    if (showLocationPicker) {
        courier.ui.components.DownloadLocationPickerDialog(
            onDismissRequest = { viewModel.dismissLocationPicker() },
            onLocationSelected = { viewModel.onLocationPicked(it) }
        )
    }

    if (showRenameDialog) {
        var newNameText by remember(myIdentity.deviceName) { mutableStateOf(myIdentity.deviceName) }
        AlertDialog(
            onDismissRequest = { viewModel.closeRenameDialog() },
            title = { Text("Rename This Device", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Choose a friendly name for this device. It is shared with paired " +
                            "devices over encrypted TLS and is not broadcast publicly.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 17.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = newNameText,
                        onValueChange = { newNameText = it },
                        placeholder = { Text("e.g. Studio Laptop", color = TextMuted) },
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
                    onClick = { viewModel.submitNewDeviceName(newNameText) },
                    enabled = newNameText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
                ) {
                    Text("Save", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.closeRenameDialog() }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
        )
    }

    val gutter = if (LocalWidthClass.current == WidthClass.COMPACT) 16.dp else 22.dp

    Surface(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter
        ) {
        Column(
            modifier = Modifier
                .widthIn(max = CONTENT_MAX_WIDTH_DP.dp)
                .fillMaxWidth()
                .padding(horizontal = gutter)
                .padding(top = 18.dp, bottom = 20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier
                        .size(38.dp)
                        .background(SurfaceCard, CircleShape)
                        .border(1.dp, CardBorderDark, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = AccentCyan,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = "Settings",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Preferences & System Configuration",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section: Storage & Saved Locations
            val activeDirSummary = settings.downloadDirectory
                .ifBlank { viewModel.defaultDownloadDirectory }
                .replace('\\', '/')
                .trimEnd('/')
                .substringAfterLast('/')

            SettingsSection(
                title = "Download Locations",
                icon = Icons.Default.Folder,
                summary = "Saving to $activeDirSummary",
                initiallyExpanded = true
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val defaultDir = viewModel.defaultDownloadDirectory
                    val activeDir = settings.downloadDirectory.ifBlank { defaultDir }

                    Text(
                        "Active Destination Folder",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        activeDir,
                        color = AccentCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Saved Locations Header with Add Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Saved Locations",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Button(
                            onClick = { viewModel.browseAndAddLocation() },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrimaryIndigo,
                                contentColor = Color.White
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyan.copy(alpha = 0.5f)),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Location",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add Location", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Combined list of locations (default dir + saved custom locations)
                    val allLocations = remember(settings.savedDownloadLocations, defaultDir) {
                        val list = mutableListOf(defaultDir)
                        for (loc in settings.savedDownloadLocations) {
                            if (loc.isNotBlank() && !list.contains(loc)) {
                                list.add(loc)
                            }
                        }
                        list
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (loc in allLocations) {
                            val isSelected = activeDir == loc
                            val isDefault = loc == defaultDir

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isSelected) PrimaryIndigo.copy(alpha = 0.2f) else SurfaceVariantDark.copy(alpha = 0.6f),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (isSelected) AccentCyan.copy(alpha = 0.6f) else CardBorderDark,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .selectable(
                                        selected = isSelected,
                                        role = Role.RadioButton,
                                        onClick = { viewModel.updateDownloadDirectory(loc) }
                                    )
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { viewModel.updateDownloadDirectory(loc) },
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = AccentCyan,
                                            unselectedColor = TextMuted
                                        ),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = loc,
                                            color = if (isSelected) TextPrimary else TextSecondary,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (isDefault) {
                                            Text(
                                                text = "Default Folder",
                                                color = TextMuted,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }

                                if (!isDefault) {
                                    IconButton(
                                        onClick = { viewModel.removeSavedLocation(loc) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Remove location",
                                            tint = TextMuted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section: Concurrency
            SettingsSection(
                title = "Downloads & Performance",
                icon = Icons.Default.Speed,
                summary = "${settings.maxConcurrentDownloads} at a time"
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "Max Concurrent Downloads",
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "Parallel download slots",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }

                        Box(
                            modifier = Modifier
                                .background(PrimaryIndigo.copy(alpha = 0.28f), RoundedCornerShape(8.dp))
                                .border(1.dp, AccentCyan, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "${settings.maxConcurrentDownloads}",
                                color = AccentCyan,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Slider(
                        value = settings.maxConcurrentDownloads.toFloat(),
                        onValueChange = { viewModel.updateMaxConcurrentDownloads(it.toInt()) },
                        valueRange = 1f..5f,
                        steps = 3,
                        colors = SliderDefaults.colors(
                            thumbColor = AccentCyan,
                            activeTrackColor = PrimaryIndigo,
                            inactiveTrackColor = SurfaceVariantDark
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section: Quality Preference
            SettingsSection(
                title = "Default Quality",
                icon = Icons.Default.HighQuality,
                summary = QUALITY_OPTIONS.firstOrNull { it.first == settings.defaultQuality }
                    ?.second ?: settings.defaultQuality
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    for ((key, label) in QUALITY_OPTIONS) {
                        val isSelected = settings.defaultQuality == key
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isSelected) PrimaryIndigo.copy(alpha = 0.2f) else Color.Transparent, RoundedCornerShape(10.dp))
                                .border(1.dp, if (isSelected) AccentCyan.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(10.dp))
                                .selectable(
                                    selected = isSelected,
                                    role = Role.RadioButton,
                                    onClick = { viewModel.updateDefaultQuality(key) }
                                )
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { viewModel.updateDefaultQuality(key) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = AccentCyan,
                                    unselectedColor = TextMuted
                                ),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = label,
                                color = if (isSelected) TextPrimary else TextSecondary,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section: Output Format
            //
            // YouTube publishes no H.264 above 1080p — 1440p/2160p exist only as
            // AV1/VP9, which Premiere cannot import. The options are described by
            // outcome ("imports into Premiere") rather than by codec name, since
            // that is the decision the user is actually making.
            SettingsSection(
                title = "Output Format",
                icon = Icons.Default.Movie,
                summary = OUTPUT_PROFILE_OPTIONS.firstOrNull { it.first == settings.outputProfile }
                    ?.second ?: ""
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val isAndroidPlatform = remember { getPlatformActions().isAndroid() }
                    val visibleProfiles = remember(isAndroidPlatform) {
                        if (isAndroidPlatform) {
                            OUTPUT_PROFILE_OPTIONS.filter { it.first != OutputProfile.EDITING_TRANSCODE }
                        } else {
                            OUTPUT_PROFILE_OPTIONS
                        }
                    }
                    val visibleCodecs = remember(isAndroidPlatform) {
                        if (isAndroidPlatform) {
                            TRANSCODE_CODEC_OPTIONS.filter { it.first == TranscodeCodec.H264 }
                        } else {
                            TRANSCODE_CODEC_OPTIONS
                        }
                    }

                    for ((profile, label, detail) in visibleProfiles) {
                        val isSelected = settings.outputProfile == profile
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isSelected) PrimaryIndigo.copy(alpha = 0.2f) else Color.Transparent, RoundedCornerShape(10.dp))
                                .border(1.dp, if (isSelected) AccentCyan.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(10.dp))
                                .selectable(
                                    selected = isSelected,
                                    role = Role.RadioButton,
                                    onClick = { viewModel.updateOutputProfile(profile) }
                                )
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { viewModel.updateOutputProfile(profile) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = AccentCyan,
                                    unselectedColor = TextMuted
                                ),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = label,
                                    color = if (isSelected) TextPrimary else TextSecondary,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = detail,
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }

                    if (settings.outputProfile == OutputProfile.EDITING_TRANSCODE && !isAndroidPlatform) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Convert to",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 2.dp)
                        )
                        for ((codec, codecLabel) in visibleCodecs) {
                            val isCodecSelected = settings.transcodeCodec == codec
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (isCodecSelected) PrimaryIndigo.copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(8.dp))
                                    .selectable(
                                        selected = isCodecSelected,
                                        role = Role.RadioButton,
                                        onClick = { viewModel.updateTranscodeCodec(codec) }
                                    )
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isCodecSelected,
                                    onClick = { viewModel.updateTranscodeCodec(codec) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = AccentCyan,
                                        unselectedColor = TextMuted
                                    ),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = codecLabel,
                                    color = if (isCodecSelected) TextPrimary else TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section: Subtitles, chapters and metadata.
            //
            // Every option here is an FFmpeg post-processing step, so they are
            // disabled outright when the merger is missing rather than offered
            // and then failing at the very end of a download.
            val isMergerAvailable by courier.di.AppModule.binaryManager.isMergerAvailable.collectAsState()

            val mediaExtras = buildList {
                if (settings.writeSubtitles) add("subtitles")
                if (settings.embedChapters) add("chapters")
                if (settings.embedThumbnail) add("thumbnail")
                if (settings.embedMetadata) add("metadata")
            }

            SettingsSection(
                title = "Subtitles & Extras",
                icon = Icons.Default.ClosedCaption,
                summary = when {
                    !isMergerAvailable -> "Unavailable — FFmpeg is missing"
                    mediaExtras.isEmpty() -> "Nothing extra embedded"
                    else -> "Embedding ${mediaExtras.joinToString(", ")}"
                }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (!isMergerAvailable) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(WarningOrange.copy(alpha = 0.13f), RoundedCornerShape(10.dp))
                                .border(1.dp, WarningOrange.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = WarningOrange,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "These all need FFmpeg, which is not installed. " +
                                    "Update the engine components below to enable them.",
                                color = WarningOrange,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }

                    SettingToggle(
                        title = "Subtitles",
                        detail = "Embedded into the video, including auto-generated captions. " +
                            "Skipped for audio-only downloads.",
                        checked = settings.writeSubtitles,
                        enabled = isMergerAvailable,
                        onCheckedChange = { viewModel.updateWriteSubtitles(it) }
                    )

                    if (settings.writeSubtitles && isMergerAvailable) {
                        Column {
                            Text(
                                "LANGUAGES",
                                color = AccentCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                for ((code, label) in SUBTITLE_LANGUAGES) {
                                    val isOn = settings.subtitleLanguages.contains(code)
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                if (isOn) PrimaryIndigo.copy(alpha = 0.3f) else SurfaceVariantDark,
                                                RoundedCornerShape(10.dp)
                                            )
                                            .border(
                                                1.dp,
                                                if (isOn) AccentCyan else CardBorderDark,
                                                RoundedCornerShape(10.dp)
                                            )
                                            .clickable { viewModel.toggleSubtitleLanguage(code) }
                                            .padding(horizontal = 12.dp, vertical = 7.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (isOn) Color.White else TextSecondary,
                                            fontSize = 12.sp,
                                            fontWeight = if (isOn) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "A language with no subtitles published is skipped without failing " +
                                    "the download.",
                                color = TextMuted,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }

                    SettingToggle(
                        title = "Chapters",
                        detail = "Chapter markers, so players and editors can jump between sections.",
                        checked = settings.embedChapters,
                        enabled = isMergerAvailable,
                        onCheckedChange = { viewModel.updateEmbedChapters(it) }
                    )

                    SettingToggle(
                        title = "Cover art",
                        detail = "The video's thumbnail, embedded as cover art. Useful for audio files.",
                        checked = settings.embedThumbnail,
                        enabled = isMergerAvailable,
                        onCheckedChange = { viewModel.updateEmbedThumbnail(it) }
                    )

                    SettingToggle(
                        title = "Title & uploader metadata",
                        detail = "Writes the title, uploader and description into the file's own tags.",
                        checked = settings.embedMetadata,
                        enabled = isMergerAvailable,
                        onCheckedChange = { viewModel.updateEmbedMetadata(it) }
                    )

                    Text(
                        "These apply to new downloads. Anything already queued keeps the " +
                            "settings it was created with.",
                        color = TextMuted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section: Device Link
            SettingsSection(
                title = "Device Link",
                icon = Icons.Default.Devices,
                summary = if (!settings.deviceLinkEnabled) {
                    "Off"
                } else when (pairedDevices.size) {
                    0 -> "On • no paired devices"
                    1 -> "On • 1 paired device"
                    else -> "On • ${pairedDevices.size} paired devices"
                }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SettingToggle(
                        title = "Enable Device Link",
                        detail = "Off closes the listening socket and disconnects every paired " +
                            "device. Pairings are kept.",
                        checked = settings.deviceLinkEnabled,
                        onCheckedChange = { viewModel.updateDeviceLinkEnabled(it) }
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceVariantDark.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .border(1.dp, CardBorderDark, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (myIdentity.deviceType == "phone") {
                                Icons.Default.PhoneAndroid
                            } else {
                                Icons.Default.Computer
                            },
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = myIdentity.deviceName,
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "This device • port ${myIdentity.tcpPort}",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                        IconButton(
                            onClick = { viewModel.openRenameDialog() },
                            modifier = Modifier
                                .size(34.dp)
                                .background(SurfaceCard, RoundedCornerShape(8.dp))
                                .border(1.dp, CardBorderDark, RoundedCornerShape(8.dp))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Rename this device",
                                tint = AccentCyan,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (pairedDevices.isEmpty()) {
                        Text(
                            "No paired devices. Pair one from the Devices tab.",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "PAIRED",
                                color = AccentCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            for (device in pairedDevices) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (device.deviceType == "phone") {
                                            Icons.Default.PhoneAndroid
                                        } else {
                                            Icons.Default.Computer
                                        },
                                        contentDescription = null,
                                        tint = TextMuted,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = device.deviceName,
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Text(
                                "Unpair from the Devices tab.",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section: Cookie Authentication
            SettingsSection(
                title = "Browser Cookies",
                icon = Icons.Default.Cookie,
                summary = if (settings.selectedCookieBrowser.equals("None", ignoreCase = true) ||
                    settings.selectedCookieBrowser.isBlank()
                ) "Not using browser cookies" else "Using ${settings.selectedCookieBrowser}"
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Import Cookies from Browser",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Instagram and Facebook require a signed-in browser session for most posts. Enables downloading age-restricted YouTube videos and authenticated Instagram/Facebook media.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (browser in BROWSER_OPTIONS) {
                            val isSelected = settings.selectedCookieBrowser.equals(browser, ignoreCase = true)
                            val bg = if (isSelected) PrimaryIndigo.copy(alpha = 0.3f) else SurfaceVariantDark
                            val border = if (isSelected) AccentCyan else CardBorderDark
                            val textColor = if (isSelected) Color.White else TextSecondary

                            Box(
                                modifier = Modifier
                                    .background(bg, RoundedCornerShape(10.dp))
                                    .border(1.dp, border, RoundedCornerShape(10.dp))
                                    .clickable { viewModel.updateCookieBrowser(browser) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = browser,
                                    color = textColor,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section: App Updates
            SettingsSection(
                title = "App Updates",
                icon = Icons.Default.SystemUpdate,
                summary = "v${AppVersion.VERSION_NAME}" +
                    if (settings.autoCheckAppUpdates) " • checking automatically" else " • manual checks only"
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Row 1: Automatic Updates Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Automatic Update Checks",
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Silently check for new versions on launch and download in background",
                                color = TextMuted,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Switch(
                            checked = settings.autoCheckAppUpdates,
                            onCheckedChange = { viewModel.updateAutoCheckAppUpdates(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = AccentCyan,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = SurfaceVariantDark
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(CardBorderDark)
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    // Row 2: Manual Check & State
                    val updateManager = courier.di.AppModule.appUpdateManager
                    val appUpdateState by updateManager.updateState.collectAsState()
                    val isChecking = appUpdateState is courier.update.AppUpdateState.Checking
                    val isDownloading = appUpdateState is courier.update.AppUpdateState.Downloading

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Courier Version",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val lastAppCheckedText = if (settings.lastAppUpdateCheckEpochMs > 0) {
                                val instant = kotlinx.datetime.Instant.fromEpochMilliseconds(settings.lastAppUpdateCheckEpochMs)
                                val local = instant.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
                                "Checked ${local.year}-${local.monthNumber.toString().padStart(2, '0')}-${local.dayOfMonth.toString().padStart(2, '0')} ${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
                            } else {
                                "Checked: Never"
                            }
                            Text(
                                text = "v${courier.util.AppVersion.VERSION_NAME} • $lastAppCheckedText",
                                color = AccentCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Button(
                            onClick = { viewModel.checkAppUpdates() },
                            enabled = !isChecking && !isDownloading,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrimaryIndigo,
                                contentColor = Color.White
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyan.copy(alpha = 0.5f))
                        ) {
                            if (isChecking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Check Now", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Live Status Feedback
                    when (val st = appUpdateState) {
                        is courier.update.AppUpdateState.Checking -> {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Connecting to GitHub Releases...",
                                color = AccentCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        is courier.update.AppUpdateState.UpToDate -> {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "✓ Courier is up to date (v${st.version})",
                                color = AccentCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        is courier.update.AppUpdateState.Downloading -> {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Downloading v${st.latestVersion} (${st.progressPercent.toInt()}% • ${st.speedFormatted})",
                                color = AccentCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { st.progressPercent / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp),
                                color = AccentCyan,
                                trackColor = Color.White.copy(alpha = 0.1f)
                            )
                        }
                        is courier.update.AppUpdateState.UpdateReady -> {
                            Spacer(modifier = Modifier.height(12.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(PrimaryIndigo.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                                    .border(1.dp, AccentCyan, RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "🚀 Version ${st.latestVersion} Ready",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Restart Courier to apply the new version",
                                            color = TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }

                                    Button(
                                        onClick = { viewModel.restartAndApplyAppUpdate() },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = AccentCyan,
                                            contentColor = SurfaceDark
                                        )
                                    ) {
                                        Text("Restart & Apply", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        is courier.update.AppUpdateState.ManualUpdateRequired -> {
                            Spacer(modifier = Modifier.height(12.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(WarningOrange.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                                    .border(1.dp, WarningOrange.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = "Version ${st.latestVersion} is available",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = st.reason,
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier
                                        .background(SurfaceVariantDark, RoundedCornerShape(8.dp))
                                        .border(1.dp, CardBorderDark, RoundedCornerShape(8.dp))
                                        .clickable { getPlatformActions().setClipboardText(st.releaseUrl) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        "Copy release link",
                                        color = AccentCyan,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        is courier.update.AppUpdateState.Error -> {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Update error: ${st.message}",
                                color = Color(0xFFFF6B6B),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        else -> {}
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section: Engine & Updates
            SettingsSection(
                title = "Engine Components",
                icon = Icons.Default.SystemUpdate,
                summary = "yt-dlp ${uiState.binaryVersion.ifBlank { "embedded" }}"
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "yt-dlp Video Engine",
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val lastCheckedText = if (settings.lastEngineUpdateCheckEpochMs > 0) {
                                val instant = kotlinx.datetime.Instant.fromEpochMilliseconds(settings.lastEngineUpdateCheckEpochMs)
                                val local = instant.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
                                "Checked ${local.year}-${local.monthNumber.toString().padStart(2, '0')}-${local.dayOfMonth.toString().padStart(2, '0')}"
                            } else {
                                "Checked: Never"
                            }
                            Text(
                                "Version: ${uiState.binaryVersion.ifBlank { "Embedded" }} • $lastCheckedText",
                                color = AccentCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Button(
                            onClick = { viewModel.checkAndUpdateBinaries() },
                            enabled = !uiState.isUpdatingBinaries,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrimaryIndigo,
                                contentColor = Color.White
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyan.copy(alpha = 0.5f))
                        ) {
                            if (uiState.isUpdatingBinaries) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Update", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (uiState.updateStatusMessage != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = uiState.updateStatusMessage ?: "",
                            color = AccentCyan,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // Disclaimer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceCard, RoundedCornerShape(14.dp))
                    .border(1.dp, CardBorderDark, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Project Courier is designed for personal content archival. Ensure you comply with platform Terms of Service and only download media you have the right to access.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Persistent Version & Build Footer Badge
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceCard, RoundedCornerShape(12.dp))
                    .border(1.dp, CardBorderDark, RoundedCornerShape(12.dp))
                    .clickable { getPlatformActions().setClipboardText(AppVersion.BUILD_IDENTITY) }
                    .padding(vertical = 12.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = AppVersion.DISPLAY_STRING,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    // Reports the running artifact's own build time, so "am I on the
                    // new build?" is answerable without inspecting the jar.
                    Text(
                        text = "Built ${AppVersion.BUILD_TIMESTAMP}",
                        color = TextMuted,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
        }
    }
}

/**
 * One collapsible settings group.
 *
 * The screen was a single scroll of eight always-open cards, so finding
 * anything below the fold meant scrolling past everything above it. Collapsed
 * headers turn that into a contents list: [summary] carries the current value
 * so the common case — checking a setting rather than changing it — needs no
 * expansion at all.
 */
@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    summary: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceCard, RoundedCornerShape(18.dp))
                .border(1.dp, CardBorderDark, RoundedCornerShape(18.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AccentCyan,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                if (summary.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = summary,
                        color = TextMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                tint = TextMuted,
                modifier = Modifier.size(20.dp)
            )
        }

        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            SettingsCard { content() }
        }
    }
}

/** A labelled on/off row, the shape most of these settings share. */
@Composable
private fun SettingToggle(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) TextPrimary else TextMuted,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = detail,
                color = TextMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AccentCyan,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = SurfaceVariantDark
            )
        )
    }
}

@Composable
private fun SettingsCard(
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(18.dp))
            .border(1.dp, CardBorderDark, RoundedCornerShape(18.dp))
            .padding(20.dp)
    ) {
        content()
    }
}
