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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import courier.platform.getPlatformActions
import courier.ui.theme.AccentCyan
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

    Surface(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp)
                .padding(top = 18.dp, bottom = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier
                        .size(44.dp)
                        .background(SurfaceCard, CircleShape)
                        .border(1.dp, CardBorderDark, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Text(
                    text = "Settings",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section: Storage & Saved Locations
            SettingsSectionHeader(title = "Download Locations", icon = Icons.Default.Folder)

            SettingsCard {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val defaultDir = getPlatformActions().getDefaultDownloadDirectory()
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
            SettingsSectionHeader(title = "Downloads & Performance", icon = Icons.Default.Speed)

            SettingsCard {
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
            SettingsSectionHeader(title = "Default Quality", icon = Icons.Default.HighQuality)

            SettingsCard {
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
            SettingsSectionHeader(title = "Output Format", icon = Icons.Default.Movie)

            SettingsCard {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    for ((profile, label, detail) in OUTPUT_PROFILE_OPTIONS) {
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

                    if (settings.outputProfile == OutputProfile.EDITING_TRANSCODE) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Convert to",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 2.dp)
                        )
                        for ((codec, codecLabel) in TRANSCODE_CODEC_OPTIONS) {
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

            // Section: Cookie Authentication
            SettingsSectionHeader(title = "Browser Cookies", icon = Icons.Default.Cookie)

            SettingsCard {
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

            // Section: Engine & Updates
            SettingsSectionHeader(title = "Engine Components", icon = Icons.Default.SystemUpdate)

            SettingsCard {
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
                            Text(
                                "Version: ${uiState.binaryVersion.ifBlank { "Embedded" }}",
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

@Composable
private fun SettingsSectionHeader(
    title: String,
    icon: ImageVector
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 10.dp, start = 2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AccentCyan,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
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
