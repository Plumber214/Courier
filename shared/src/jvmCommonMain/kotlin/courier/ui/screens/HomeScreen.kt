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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import courier.manager.DownloadManager
import courier.model.DownloadStatus
import courier.model.MediaType
import courier.model.Platform
import courier.ui.components.ClipboardPrompt
import courier.ui.components.DownloadItemCard
import courier.platform.getPlatformActions
import courier.ui.components.GalleryPickerDialog
import courier.ui.components.MediaPlayerModal
import courier.ui.components.PhotoPickerDialog
import courier.ui.components.QualityPickerDialog
import courier.ui.components.SetupWizardDialog
import courier.ui.components.StatusBanner
import courier.ui.components.UrlInputBar
import courier.ui.layout.CONTENT_MAX_WIDTH_DP
import courier.ui.layout.LocalWidthClass
import courier.ui.layout.WidthClass
import courier.ui.theme.AccentCyan
import courier.ui.theme.AccentPink
import courier.ui.theme.CardBorderDark
import courier.ui.theme.PrimaryIndigo
import courier.ui.theme.SurfaceCard
import courier.ui.theme.SurfaceVariantDark
import courier.ui.theme.TextMuted
import courier.ui.theme.TextPrimary
import courier.ui.theme.TextSecondary
import courier.ui.theme.WarningOrange
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
    val isMergerAvailable by downloadManager.binaryManager.isMergerAvailable.collectAsState()
    val isBinaryDownloading by downloadManager.binaryManager.isDownloading.collectAsState()
    val binaryProgress by downloadManager.binaryManager.downloadProgress.collectAsState()
    val binaryStatusMsg by downloadManager.binaryManager.statusMessage.collectAsState()
    val binaryErrorMsg by downloadManager.binaryManager.errorMessage.collectAsState()

    // Check clipboard on launch/resume
    LaunchedEffect(Unit) {
        homeViewModel.checkClipboardForVideoUrl()
        downloadManager.binaryManager.ensureBinariesReady()
    }

    // A link shared into Courier from another app. Keyed on the pending value
    // rather than Unit, so a second share into an already-running app is picked
    // up too — the failure the clipboard route had.
    val pendingSharedLink by courier.share.IncomingLinks.pending.collectAsState()
    LaunchedEffect(pendingSharedLink) {
        val shared = courier.share.IncomingLinks.consume()
        if (shared != null) {
            homeViewModel.acceptSharedUrl(shared)
        }
    }

    val widthClass = LocalWidthClass.current
    val isCompact = widthClass == WidthClass.COMPACT
    val gutter = if (isCompact) 16.dp else 22.dp

    val activeCount = downloads.count { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.MERGING }
    val queuedCount = downloads.count { it.status == DownloadStatus.QUEUED }
    val pausedCount = downloads.count { it.status == DownloadStatus.PAUSED }
    val completedCount = downloads.count { it.status == DownloadStatus.COMPLETED }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        color = Color.Transparent
    ) {
        // Centred and capped rather than stretched: a full-width line of body
        // text on a 2560 dp monitor is unreadable, and the download rows would
        // be mostly empty space between a thumbnail and three buttons.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
        Column(
            modifier = Modifier
                .widthIn(max = CONTENT_MAX_WIDTH_DP.dp)
                .fillMaxSize()
                .padding(horizontal = gutter)
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
                            text = "Video, Photo & Audio Downloader",
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
                    val statusParts = buildList {
                        if (activeCount > 0) add("$activeCount Active")
                        if (queuedCount > 0) add("$queuedCount Queued")
                        if (pausedCount > 0) add("$pausedCount Paused")
                    }
                    val statusText = statusParts.joinToString(" • ")

                    if (statusText.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .background(PrimaryIndigo.copy(alpha = 0.28f), RoundedCornerShape(10.dp))
                                .border(1.dp, AccentCyan, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = statusText,
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

            // Handing a download to a paired device used to close the picker and
            // say nothing — whether it went out, or was queued because the other
            // device is asleep, or never had a link at all.
            if (uiState.remoteSendMessage != null) {
                Spacer(modifier = Modifier.height(14.dp))
                StatusBanner(
                    message = uiState.remoteSendMessage,
                    icon = Icons.Default.Devices,
                    onDismiss = homeViewModel::clearRemoteSendMessage
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // URL Input Area
            UrlInputBar(
                url = uiState.inputUrl,
                onUrlChange = homeViewModel::onUrlChanged,
                onPasteClick = homeViewModel::pasteFromClipboard,
                onClearClick = homeViewModel::clearUrl,
                onDownloadClick = { homeViewModel.analyzeUrl() },
                isAnalyzing = uiState.isAnalyzing
            )

            // Prominent Link Analyzing Banner
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

                Spacer(modifier = Modifier.height(14.dp))
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

            // Missing merger notice.
            //
            // Without FFmpeg, progressive downloads still work but merged
            // video+audio and audio extraction fail. Reporting "Engine Ready"
            // and letting the user find out mid-download is what this replaces.
            if (isBinaryReady && !isMergerAvailable && !isBinaryDownloading) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(WarningOrange.copy(alpha = 0.13f), RoundedCornerShape(12.dp))
                        .border(1.dp, WarningOrange.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = WarningOrange,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = binaryErrorMsg
                            ?: "FFmpeg is missing. High-quality merged video and audio extraction will fail.",
                        color = WarningOrange,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .background(SurfaceVariantDark, RoundedCornerShape(8.dp))
                            .border(1.dp, CardBorderDark, RoundedCornerShape(8.dp))
                            .clickable {
                                coroutineScope.launch {
                                    downloadManager.binaryManager.ensureBinariesReady()
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("Retry", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Clipboard Detection Prompt
            if (uiState.showClipboardBanner && !uiState.isAnalyzing) {
                Spacer(modifier = Modifier.height(14.dp))
                ClipboardPrompt(
                    detectedUrl = uiState.detectedClipboardUrl,
                    visible = uiState.showClipboardBanner,
                    onAccept = homeViewModel::acceptClipboardUrl,
                    onDismiss = homeViewModel::dismissClipboardBanner
                )
            }

            // Error message if any
            if (uiState.analysisError != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AccentPink.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .border(1.dp, AccentPink.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Error",
                            tint = AccentPink,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = uiState.analysisError ?: "",
                            color = AccentPink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (uiState.analysisError?.contains("Settings", ignoreCase = true) == true) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(SurfaceVariantDark, RoundedCornerShape(8.dp))
                                .border(1.dp, CardBorderDark, RoundedCornerShape(8.dp))
                                .clickable { onOpenSettings() }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("Settings", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

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

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (activeCount > 0 || queuedCount > 0) {
                        Text(
                            text = "Cancel all",
                            fontSize = 12.sp,
                            color = AccentPink,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .background(AccentPink.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                                .border(1.dp, AccentPink.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .clickable { downloadManager.cancelAll() }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }

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
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Downloads Queue / List
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
                            .padding(32.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .background(PrimaryIndigo.copy(alpha = 0.2f), CircleShape)
                                .border(1.dp, AccentCyan.copy(alpha = 0.4f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayCircleOutline,
                                contentDescription = null,
                                tint = AccentCyan,
                                modifier = Modifier.size(38.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

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

                        Spacer(modifier = Modifier.height(22.dp))

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
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
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
                val renderDownload: @Composable (courier.model.DownloadItem) -> Unit = { item ->
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
                                onPause = { downloadManager.pauseDownload(item.id) },
                                onResume = { downloadManager.resumeDownload(item.id) },
                                onRemoveFromList = {
                                    coroutineScope.launch {
                                        dismissingItemIds.add(item.id)
                                        delay(300)
                                        downloadManager.removeDownload(item.id, deleteDiskFile = false)
                                        dismissingItemIds.remove(item.id)
                                    }
                                },
                                onDeleteFile = {
                                    coroutineScope.launch {
                                        dismissingItemIds.add(item.id)
                                        delay(300)
                                        downloadManager.removeDownload(item.id, deleteDiskFile = true)
                                        dismissingItemIds.remove(item.id)
                                    }
                                },
                                onPreviewMedia = {
                                    // Photos and galleries hand off to the OS image
                                    // viewer rather than rendering in-app: the
                                    // built-in preview never displayed them, and the
                                    // system viewer already does zoom, rotate and
                                    // sharing properly. Videos keep the in-app player.
                                    val isPhotoOrGallery = item.mediaType == MediaType.IMAGE ||
                                        item.mediaType == MediaType.GALLERY
                                    if (isPhotoOrGallery) {
                                        // Fall back to revealing the containing folder
                                        // if no app claims the file.
                                        if (!downloadManager.openDownloadedFile(item)) {
                                            downloadManager.openDownloadFolder(item)
                                        }
                                    } else {
                                        homeViewModel.openMediaPreview(item)
                                    }
                                },
                                onCopyLink = { getPlatformActions().setClipboardText(item.url) },
                                onOpenFolder = { downloadManager.openDownloadFolder(item) }
                            )
                        }
                }

                if (widthClass == WidthClass.EXPANDED) {
                    // A single column on a 1400 dp window is a stripe of cards
                    // down the middle of an empty screen.
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        gridItems(downloads, key = { it.id }) { item ->
                            renderDownload(item)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(downloads, key = { it.id }) { item ->
                            renderDownload(item)
                        }
                    }
                }
            }
        }
        }

        // In-App Media Player Preview Modal
        if (uiState.activePreviewItem != null) {
            MediaPlayerModal(
                item = uiState.activePreviewItem!!,
                onDismiss = homeViewModel::dismissMediaPreview,
                onOpenExternal = {
                    uiState.activePreviewItem?.let { downloadManager.openDownloadedFile(it) }
                },
                onOpenFolder = {
                    uiState.activePreviewItem?.let { downloadManager.openDownloadFolder(it) }
                }
            )
        }

        // Media picker dialog (Gallery, Single Photo, or Video Quality)
        if (uiState.showQualityPicker && uiState.previewInfo != null) {
            val preview = uiState.previewInfo!!
            when (preview.mediaType) {
                MediaType.GALLERY -> {
                    GalleryPickerDialog(
                        videoInfo = preview,
                        defaultDownloadDir = settings.downloadDirectory,
                        savedLocations = settings.savedDownloadLocations,
                        onDismiss = homeViewModel::dismissQualityPicker,
                        onConfirm = { selectedIndices, destinationDir ->
                            homeViewModel.confirmDownload(
                                format = null,
                                isAudioOnly = false,
                                destinationDir = destinationDir,
                                mediaType = MediaType.GALLERY,
                                selectedGalleryIndices = selectedIndices
                            )
                        }
                    )
                }
                MediaType.IMAGE -> {
                    PhotoPickerDialog(
                        videoInfo = preview,
                        defaultDownloadDir = settings.downloadDirectory,
                        savedLocations = settings.savedDownloadLocations,
                        onDismiss = homeViewModel::dismissQualityPicker,
                        onConfirm = { destinationDir ->
                            homeViewModel.confirmDownload(
                                format = preview.formats.firstOrNull(),
                                isAudioOnly = false,
                                destinationDir = destinationDir,
                                mediaType = MediaType.IMAGE
                            )
                        }
                    )
                }
                else -> {
                    val pairedDevices by courier.di.AppModule.deviceLinkManager.trustStore.pairedDevices.collectAsState()
                    QualityPickerDialog(
                        videoInfo = preview,
                        defaultDownloadDir = settings.downloadDirectory,
                        savedLocations = settings.savedDownloadLocations,
                        defaultQuality = settings.defaultQuality,
                        pairedDevices = pairedDevices,
                        onSendToDevice = { targetDeviceId, format, isAudioOnly ->
                            homeViewModel.sendToRemoteDevice(targetDeviceId, format, isAudioOnly)
                        },
                        onDismiss = homeViewModel::dismissQualityPicker,
                        onConfirm = { format, isAudioOnly, destinationDir, downloadPlaylist ->
                            homeViewModel.confirmDownload(
                                format = format,
                                isAudioOnly = isAudioOnly,
                                destinationDir = destinationDir,
                                downloadPlaylist = downloadPlaylist
                            )
                        }
                    )
                }
            }
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
