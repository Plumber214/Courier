package courier.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stream
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import courier.manager.DownloadManager
import courier.model.DownloadStatus
import courier.model.Platform
import courier.ui.components.ClipboardPrompt
import courier.ui.components.DownloadItemCard
import courier.ui.components.QualityPickerDialog
import courier.ui.components.SetupWizardDialog
import courier.ui.components.UrlInputBar
import courier.ui.theme.AccentCyan
import courier.ui.theme.AccentPink
import courier.ui.theme.CardBorderDark
import courier.ui.theme.PrimaryIndigo
import courier.ui.theme.SuccessGreen
import courier.ui.theme.SurfaceCard
import courier.ui.theme.SurfaceDark
import courier.ui.theme.SurfaceVariantDark
import courier.ui.theme.TextMuted
import courier.ui.theme.TextPrimary
import courier.ui.theme.TextSecondary
import courier.viewmodel.HomeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    homeViewModel: HomeViewModel,
    downloadManager: DownloadManager,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by homeViewModel.uiState.collectAsState()
    val downloads by downloadManager.downloads.collectAsState()
    val settings by downloadManager.settings.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val dismissingItemIds = remember { mutableStateListOf<String>() }

    // Binary manager states
    val isBinaryReady by downloadManager.binaryManager.isReady.collectAsState()
    val isBinaryDownloading by downloadManager.binaryManager.isDownloading.collectAsState()
    val binaryProgress by downloadManager.binaryManager.downloadProgress.collectAsState()
    val binaryStatusMsg by downloadManager.binaryManager.statusMessage.collectAsState()
    val binaryErrorMsg by downloadManager.binaryManager.errorMessage.collectAsState()

    // Check clipboard on launch/resume
    LaunchedEffect(Unit) {
        homeViewModel.checkClipboardForVideoUrl()
        downloadManager.binaryManager.ensureBinariesReady()
    }

    val activeCount = downloads.count { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.MERGING }
    val queuedCount = downloads.count { it.status == DownloadStatus.QUEUED }
    val completedCount = downloads.count { it.status == DownloadStatus.COMPLETED }

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
                .padding(horizontal = 20.dp)
                .padding(top = 18.dp, bottom = 14.dp)
        ) {
            // App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(PrimaryIndigo, RoundedCornerShape(12.dp))
                            .border(1.dp, AccentCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.VideoLibrary,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(verticalArrangement = Arrangement.Center) {
                        Text(
                            text = "Courier",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            color = TextPrimary,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = "Video & Audio Downloader",
                            fontSize = 11.sp,
                            color = TextMuted,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (activeCount > 0 || queuedCount > 0) {
                        Box(
                            modifier = Modifier
                                .background(PrimaryIndigo.copy(alpha = 0.28f), RoundedCornerShape(10.dp))
                                .border(1.dp, AccentCyan, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (activeCount > 0) "$activeCount Active" else "$queuedCount Queued",
                                color = AccentCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .size(44.dp)
                            .background(SurfaceCard, CircleShape)
                            .border(1.dp, CardBorderDark, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = TextPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // URL Input Area (Refined 50dp height with frosted glass)
            UrlInputBar(
                url = uiState.inputUrl,
                onUrlChange = homeViewModel::onUrlChanged,
                onPasteClick = homeViewModel::pasteFromClipboard,
                onClearClick = homeViewModel::clearUrl,
                onDownloadClick = { homeViewModel.analyzeUrl() },
                isAnalyzing = uiState.isAnalyzing
            )

            // Prominent Link Analyzing Banner (Immediate user feedback)
            AnimatedVisibility(
                visible = uiState.isAnalyzing,
                enter = expandVertically(tween(250)) + fadeIn(tween(250)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(200))
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "PulseTransition")
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.6f,
                    targetValue = 1.0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "PulseAlpha"
                )

                val targetPlatform = if (uiState.inputUrl.isNotBlank()) Platform.fromUrl(uiState.inputUrl) else Platform.OTHER
                val brandColor = Color(targetPlatform.brandColorHex)

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceCard, RoundedCornerShape(16.dp))
                        .border(1.5.dp, AccentCyan.copy(alpha = pulseAlpha), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = AccentCyan,
                        strokeWidth = 2.5.dp
                    )

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Analyzing video stream...",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .background(brandColor.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = targetPlatform.displayName,
                                    color = brandColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = "Connecting and fetching available formats & resolutions...",
                            color = AccentCyan.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Clipboard Detection Prompt
            if (uiState.showClipboardBanner && !uiState.isAnalyzing) {
                Spacer(modifier = Modifier.height(12.dp))
                ClipboardPrompt(
                    detectedUrl = uiState.detectedClipboardUrl,
                    visible = uiState.showClipboardBanner,
                    onAccept = homeViewModel::acceptClipboardUrl,
                    onDismiss = homeViewModel::dismissClipboardBanner
                )
            }

            // Error message if any
            if (uiState.analysisError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AccentPink.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                        .border(1.dp, AccentPink.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = uiState.analysisError ?: "",
                        color = AccentPink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // Downloads List Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Downloads & Queue",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                if (completedCount > 0) {
                    Text(
                        text = "Clear finished ($completedCount)",
                        fontSize = 12.sp,
                        color = AccentCyan,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .background(SurfaceVariantDark, RoundedCornerShape(8.dp))
                            .border(1.dp, CardBorderDark, RoundedCornerShape(8.dp))
                            .clickable { downloadManager.clearCompleted() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Downloads Queue / List with smooth item removal animations
            if (downloads.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceCard, RoundedCornerShape(22.dp))
                            .border(1.dp, CardBorderDark, RoundedCornerShape(22.dp))
                            .padding(28.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .background(PrimaryIndigo.copy(alpha = 0.2f), CircleShape)
                                .border(1.dp, AccentCyan.copy(alpha = 0.4f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayCircleOutline,
                                contentDescription = null,
                                tint = AccentCyan,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Ready to Download",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Paste a video link from YouTube, TikTok, Instagram, or Facebook above, or use the system Share menu from any video app.",
                            fontSize = 13.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            lineHeight = 19.sp
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // Platform pills
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val list = listOf(Platform.YOUTUBE, Platform.TIKTOK, Platform.INSTAGRAM, Platform.FACEBOOK)
                            for (p in list) {
                                Box(
                                    modifier = Modifier
                                        .background(Color(p.brandColorHex).copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                                        .border(1.dp, Color(p.brandColorHex).copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        p.displayName,
                                        color = Color(p.brandColorHex),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(downloads, key = { it.id }) { item ->
                        val isDismissing = dismissingItemIds.contains(item.id)

                        AnimatedVisibility(
                            visible = !isDismissing,
                            enter = fadeIn(tween(250)) + expandVertically(tween(250)),
                            exit = shrinkVertically(tween(280)) + fadeOut(tween(220)) + slideOutHorizontally(tween(250))
                        ) {
                            DownloadItemCard(
                                item = item,
                                onCancel = { downloadManager.cancelDownload(item.id) },
                                onRetry = { downloadManager.retryDownload(item.id) },
                                onRemove = {
                                    coroutineScope.launch {
                                        dismissingItemIds.add(item.id)
                                        delay(300)
                                        downloadManager.removeDownload(item.id, deleteDiskFile = true)
                                        dismissingItemIds.remove(item.id)
                                    }
                                },
                                onOpenFile = { downloadManager.openDownloadedFile(item) },
                                onOpenFolder = { downloadManager.openDownloadFolder(item) }
                            )
                        }
                    }
                }
            }
        }

        // Quality picker dialog with destination selection
        if (uiState.showQualityPicker && uiState.previewInfo != null) {
            QualityPickerDialog(
                videoInfo = uiState.previewInfo!!,
                defaultDownloadDir = settings.downloadDirectory,
                savedLocations = settings.savedDownloadLocations,
                onDismiss = homeViewModel::dismissQualityPicker,
                onConfirm = { format, isAudioOnly, destinationDir ->
                    homeViewModel.confirmDownload(format, isAudioOnly, destinationDir)
                }
            )
        }

        // First launch setup wizard dialog (Desktop)
        if (!isBinaryReady && isBinaryDownloading) {
            SetupWizardDialog(
                isDownloading = isBinaryDownloading,
                progress = binaryProgress,
                statusMessage = binaryStatusMsg,
                errorMessage = binaryErrorMsg,
                onRetry = {
                    coroutineScope.launch {
                        downloadManager.binaryManager.ensureBinariesReady()
                    }
                }
            )
        }
    }
}
