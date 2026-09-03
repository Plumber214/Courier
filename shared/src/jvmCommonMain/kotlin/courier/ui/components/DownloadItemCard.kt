package courier.ui.components

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import courier.model.DownloadItem
import courier.model.DownloadStatus
import courier.model.MediaType
import courier.model.Platform
import courier.ui.theme.AccentCyan
import courier.ui.theme.AccentPink
import courier.ui.theme.CardBorderDark
import courier.ui.theme.PrimaryIndigo
import courier.ui.theme.PrimaryIndigoLight
import courier.ui.theme.SuccessGreen
import courier.ui.theme.SurfaceCard
import courier.ui.theme.SurfaceVariantDark
import courier.ui.theme.TextMuted
import courier.ui.theme.TextPrimary
import courier.ui.theme.TextSecondary
import courier.ui.theme.WarningOrange

/**
 * Confirms deleting the media itself.
 *
 * Names the files rather than asking abstractly: "Delete this download?" does
 * not tell you whether the file on disk is going with it, which is the whole
 * question.
 */
@Composable
private fun DeleteFileConfirmation(
    filePaths: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val names = remember(filePaths) {
        filePaths.map { it.replace('\\', '/').substringAfterLast('/') }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceCard,
        title = {
            Text(
                if (names.size == 1) "Delete this file?" else "Delete these ${names.size} files?",
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    "This permanently removes the downloaded media from disk, not just " +
                        "from the list.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                for (name in names.take(5)) {
                    Text(
                        text = name,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
                if (names.size > 5) {
                    Text(
                        "and ${names.size - 5} more",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = AccentPink)
            ) {
                Text("Delete file", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

@Composable
fun DownloadItemCard(
    item: DownloadItem,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemoveFromList: () -> Unit,
    onDeleteFile: () -> Unit,
    onPreviewMedia: () -> Unit,
    onCopyLink: () -> Unit,
    onOpenFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = (item.progressPercent / 100f).coerceIn(0f, 1f),
        label = "DownloadProgress"
    )

    val platformColor = Color(item.platform.brandColorHex)
    val isPhotoOrGallery = item.mediaType == MediaType.IMAGE || item.mediaType == MediaType.GALLERY
    val canPreview = item.status == DownloadStatus.COMPLETED

    var justCopied by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(justCopied) {
        if (justCopied) {
            kotlinx.coroutines.delay(1500)
            justCopied = false
        }
    }

    var menuOpen by remember(item.id) { mutableStateOf(false) }
    var confirmDeleteFile by remember(item.id) { mutableStateOf(false) }

    val filePaths = remember(item.outputPath, item.outputPaths) {
        (item.outputPaths + listOfNotNull(item.outputPath)).distinct().filter { it.isNotBlank() }
    }
    val hasFileOnDisk = filePaths.isNotEmpty()

    if (confirmDeleteFile) {
        DeleteFileConfirmation(
            filePaths = filePaths,
            onConfirm = {
                confirmDeleteFile = false
                onDeleteFile()
            },
            onDismiss = { confirmDeleteFile = false }
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(18.dp))
            .border(1.dp, CardBorderDark, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Live Video/Photo Thumbnail / Platform Fallback.
            // The thumbnail itself is the preview affordance — the old play
            // button was removed in favour of this, so it carries an explicit
            // overlay to stay discoverable as a tap target.
            Box(
                modifier = Modifier
                    .size(width = 100.dp, height = 72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceVariantDark)
                    .border(1.dp, CardBorderDark, RoundedCornerShape(12.dp))
                    .clickable(
                        enabled = canPreview,
                        role = Role.Button,
                        onClickLabel = if (isPhotoOrGallery) "View photo" else "Play video"
                    ) { onPreviewMedia() },
                contentAlignment = Alignment.Center
            ) {
                NetworkImage(
                    url = item.thumbnailUrl,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Fallback placeholder icon based on media type
                    val placeholderIcon = when (item.mediaType) {
                        MediaType.GALLERY -> Icons.Default.Collections
                        MediaType.IMAGE -> Icons.Default.Image
                        MediaType.AUDIO -> Icons.Default.MusicNote
                        MediaType.VIDEO -> Icons.Default.VideoLibrary
                    }
                    Icon(
                        imageVector = placeholderIcon,
                        contentDescription = null,
                        tint = platformColor.copy(alpha = 0.9f),
                        modifier = Modifier.size(32.dp)
                    )
                }

                if (canPreview) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.28f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPhotoOrGallery) Icons.Default.Visibility else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // Platform tag in bottom corner
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .background(Color(0xDD090A10), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        item.platform.displayName,
                        color = platformColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Details and Progress
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Title
                Text(
                    text = item.title,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Format & Info Badges
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (item.formatLabel != null) {
                        Box(
                            modifier = Modifier
                                .background(PrimaryIndigo.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                                .border(1.dp, PrimaryIndigo.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Text(
                                item.formatLabel,
                                color = AccentCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (item.totalSizeFormatted != null) {
                        Text(
                            item.totalSizeFormatted,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Status & Progress View
                when (item.status) {
                    DownloadStatus.DOWNLOADING -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = AccentCyan,
                                trackColor = SurfaceVariantDark,
                                strokeCap = StrokeCap.Round
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "${(item.progressPercent).toInt()}%" + (if (item.speedFormatted != null) " • ${item.speedFormatted}" else ""),
                                    color = AccentCyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                if (item.etaFormatted != null) {
                                    Text(
                                        "ETA: ${item.etaFormatted}",
                                        color = TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                    DownloadStatus.MERGING -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = AccentCyan,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Merging audio & video...",
                                color = AccentCyan,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    DownloadStatus.QUEUED -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.HourglassEmpty,
                                contentDescription = null,
                                tint = WarningOrange,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Queued",
                                color = WarningOrange,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    DownloadStatus.COMPLETED -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = SuccessGreen,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Completed",
                                color = SuccessGreen,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    DownloadStatus.FAILED -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = AccentPink,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                item.errorMessage ?: "Download failed",
                                color = AccentPink,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    DownloadStatus.CANCELLED -> {
                        Text(
                            "Cancelled",
                            color = TextMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    DownloadStatus.FETCHING_INFO -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = PrimaryIndigoLight,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Analyzing media...",
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Action Buttons with 12dp spacing
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Copy link is available at every status, not only when complete:
                // a failed download's URL is exactly what you want to grab, to
                // retry elsewhere or to report the problem.
                IconButton(
                    onClick = {
                        onCopyLink()
                        justCopied = true
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            if (justCopied) SuccessGreen.copy(alpha = 0.22f) else SurfaceVariantDark,
                            CircleShape
                        )
                        .border(
                            1.dp,
                            if (justCopied) SuccessGreen else CardBorderDark,
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = if (justCopied) Icons.Default.Check else Icons.Default.Link,
                        contentDescription = if (justCopied) "Link copied" else "Copy link",
                        tint = if (justCopied) SuccessGreen else TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                if (item.status == DownloadStatus.COMPLETED) {
                    IconButton(
                        onClick = onOpenFolder,
                        modifier = Modifier
                            .size(40.dp)
                            .background(SurfaceVariantDark, CircleShape)
                            .border(1.dp, CardBorderDark, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Open Folder",
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                if (item.status == DownloadStatus.FAILED) {
                    IconButton(
                        onClick = onRetry,
                        modifier = Modifier
                            .size(40.dp)
                            .background(PrimaryIndigo, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                if (item.isActive || item.status == DownloadStatus.QUEUED) {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .size(40.dp)
                            .background(SurfaceVariantDark, CircleShape)
                            .border(1.dp, CardBorderDark, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    // One overflow rather than two buttons: removing the row and
                    // deleting the media are different actions with very
                    // different consequences, and a single trash icon that did
                    // both silently did the destructive one.
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More actions",
                                tint = TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            modifier = Modifier.background(SurfaceCard)
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text("Remove from list", color = TextPrimary, fontSize = 13.sp)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = TextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onRemoveFromList()
                                }
                            )

                            if (hasFileOnDisk) {
                                DropdownMenuItem(
                                    text = {
                                        Text("Delete file…", color = AccentPink, fontSize = 13.sp)
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.DeleteForever,
                                            contentDescription = null,
                                            tint = AccentPink,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        confirmDeleteFile = true
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
