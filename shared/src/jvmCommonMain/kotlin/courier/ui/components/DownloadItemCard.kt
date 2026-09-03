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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import courier.model.DownloadItem
import courier.model.DownloadStatus
import courier.model.MediaType
import courier.ui.layout.LocalWidthClass
import courier.ui.layout.WidthClass
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
    onPause: () -> Unit,
    onResume: () -> Unit,
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

    // On a 360 dp phone the wide row left roughly 90 dp for the title, the
    // format badge and the whole progress line, after a 100 dp thumbnail, two
    // gaps and up to three 40 dp buttons.
    val isCompact = LocalWidthClass.current == WidthClass.COMPACT

    val overflow: @Composable () -> Unit = {
        ItemOverflowMenu(
            item = item,
            expanded = menuOpen,
            onExpandedChange = { menuOpen = it },
            hasFileOnDisk = filePaths.isNotEmpty(),
            includeInlineActions = isCompact,
            // Only the compact menu owns Copy link, so only it acknowledges the
            // copy; the wide layout's own copy button already does.
            justCopied = justCopied && isCompact,
            onCopyLink = {
                onCopyLink()
                justCopied = true
            },
            onOpenFolder = onOpenFolder,
            onRetry = onRetry,
            onCancel = onCancel,
            onPause = onPause,
            onResume = onResume,
            onRemoveFromList = onRemoveFromList,
            onRequestDeleteFile = { confirmDeleteFile = true }
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(18.dp))
            .border(1.dp, CardBorderDark, RoundedCornerShape(18.dp))
            .padding(if (isCompact) 14.dp else 16.dp)
    ) {
        if (isCompact) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    ItemThumbnail(
                        item = item,
                        width = 92.dp,
                        height = 66.dp,
                        onPreviewMedia = onPreviewMedia
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        ItemTitleAndBadges(item = item, titleMaxLines = 2)
                    }

                    overflow()
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Progress spans the card rather than a leftover column, which
                // is the only width on a phone that makes a percentage, a speed
                // and an ETA fit on one line together.
                ItemStatusBlock(item = item, animatedProgress = animatedProgress)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ItemThumbnail(
                    item = item,
                    width = 100.dp,
                    height = 72.dp,
                    onPreviewMedia = onPreviewMedia
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    ItemTitleAndBadges(item = item, titleMaxLines = 1)
                    Spacer(modifier = Modifier.height(10.dp))
                    ItemStatusBlock(item = item, animatedProgress = animatedProgress)
                }

                Spacer(modifier = Modifier.width(14.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Copy link is available at every status, not only when
                    // complete: a failed download's URL is exactly what you want
                    // to grab, to retry elsewhere or to report the problem.
                    CircleActionButton(
                        icon = if (justCopied) Icons.Default.Check else Icons.Default.Link,
                        contentDescription = if (justCopied) "Link copied" else "Copy link",
                        tint = if (justCopied) SuccessGreen else TextPrimary,
                        background = if (justCopied) SuccessGreen.copy(alpha = 0.22f) else SurfaceVariantDark,
                        borderColor = if (justCopied) SuccessGreen else CardBorderDark,
                        onClick = {
                            onCopyLink()
                            justCopied = true
                        }
                    )

                    if (item.status == DownloadStatus.COMPLETED) {
                        CircleActionButton(
                            icon = Icons.Default.FolderOpen,
                            contentDescription = "Open Folder",
                            tint = TextPrimary,
                            background = SurfaceVariantDark,
                            borderColor = CardBorderDark,
                            onClick = onOpenFolder
                        )
                    }

                    if (item.status == DownloadStatus.FAILED) {
                        CircleActionButton(
                            icon = Icons.Default.Refresh,
                            contentDescription = "Retry",
                            tint = Color.White,
                            background = PrimaryIndigo,
                            borderColor = null,
                            onClick = onRetry
                        )
                    }

                    if (item.status == DownloadStatus.PAUSED) {
                        CircleActionButton(
                            icon = Icons.Default.PlayArrow,
                            contentDescription = "Resume",
                            tint = Color.White,
                            background = PrimaryIndigo,
                            borderColor = null,
                            onClick = onResume
                        )
                    }

                    // Pause only while bytes are actually moving. There is
                    // nothing to keep from a queued item, and the merge is a
                    // single FFmpeg run that cannot be continued partway.
                    if (item.status == DownloadStatus.DOWNLOADING) {
                        CircleActionButton(
                            icon = Icons.Default.Pause,
                            contentDescription = "Pause",
                            tint = WarningOrange,
                            background = SurfaceVariantDark,
                            borderColor = CardBorderDark,
                            onClick = onPause
                        )
                    }

                    if (item.isActive || item.status == DownloadStatus.QUEUED) {
                        CircleActionButton(
                            icon = Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = TextSecondary,
                            background = SurfaceVariantDark,
                            borderColor = CardBorderDark,
                            onClick = onCancel
                        )
                    } else {
                        overflow()
                    }
                }
            }
        }
    }
}

@Composable
private fun CircleActionButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    background: Color,
    borderColor: Color?,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .background(background, CircleShape)
            .then(
                if (borderColor != null) Modifier.border(1.dp, borderColor, CircleShape)
                else Modifier
            )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * The thumbnail is the preview affordance — the old play button was removed in
 * favour of this, so it carries an explicit overlay to stay discoverable as a
 * tap target.
 */
@Composable
private fun ItemThumbnail(
    item: DownloadItem,
    width: Dp,
    height: Dp,
    onPreviewMedia: () -> Unit
) {
    val platformColor = Color(item.platform.brandColorHex)
    val isPhotoOrGallery = item.mediaType == MediaType.IMAGE || item.mediaType == MediaType.GALLERY
    val canPreview = item.status == DownloadStatus.COMPLETED

    Box(
        modifier = Modifier
            .size(width = width, height = height)
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
}

@Composable
private fun ItemTitleAndBadges(
    item: DownloadItem,
    titleMaxLines: Int
) {
    Text(
        text = item.title,
        color = TextPrimary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 18.sp,
        maxLines = titleMaxLines,
        overflow = TextOverflow.Ellipsis
    )

    Spacer(modifier = Modifier.height(6.dp))

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
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ItemStatusBlock(
    item: DownloadItem,
    animatedProgress: Float
) {
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
                        "${(item.progressPercent).toInt()}%" +
                            (if (item.speedFormatted != null) " • ${item.speedFormatted}" else ""),
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        DownloadStatus.PAUSED -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = WarningOrange,
                    trackColor = SurfaceVariantDark,
                    strokeCap = StrokeCap.Round
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Pause,
                        contentDescription = null,
                        tint = WarningOrange,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    // Says the partial file is kept, because the difference
                    // between this and Cancel is exactly that.
                    Text(
                        "Paused at ${item.progressPercent.toInt()}% — resumes from here",
                        color = WarningOrange,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
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

/**
 * One overflow rather than a row of buttons.
 *
 * Removing the row and deleting the media are different actions with very
 * different consequences, and a single trash icon that did both silently did
 * the destructive one. On a compact width the other actions fold in here too
 * ([includeInlineActions]) rather than competing with the title for room.
 */
@Composable
private fun ItemOverflowMenu(
    item: DownloadItem,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    hasFileOnDisk: Boolean,
    includeInlineActions: Boolean,
    justCopied: Boolean,
    onCopyLink: () -> Unit,
    onOpenFolder: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemoveFromList: () -> Unit,
    onRequestDeleteFile: () -> Unit
) {
    Box {
        IconButton(
            onClick = { onExpandedChange(true) },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = if (justCopied) Icons.Default.Check else Icons.Default.MoreVert,
                contentDescription = if (justCopied) "Link copied" else "More actions",
                tint = if (justCopied) SuccessGreen else TextMuted,
                modifier = Modifier.size(18.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.background(SurfaceCard)
        ) {
            if (includeInlineActions) {
                MenuAction(
                    label = "Copy link",
                    icon = Icons.Default.Link,
                    tint = TextSecondary,
                    labelColor = TextPrimary
                ) {
                    onExpandedChange(false)
                    onCopyLink()
                }

                if (item.status == DownloadStatus.COMPLETED) {
                    MenuAction(
                        label = "Open folder",
                        icon = Icons.Default.FolderOpen,
                        tint = TextSecondary,
                        labelColor = TextPrimary
                    ) {
                        onExpandedChange(false)
                        onOpenFolder()
                    }
                }

                if (item.status == DownloadStatus.FAILED) {
                    MenuAction(
                        label = "Retry",
                        icon = Icons.Default.Refresh,
                        tint = AccentCyan,
                        labelColor = AccentCyan
                    ) {
                        onExpandedChange(false)
                        onRetry()
                    }
                }

                if (item.status == DownloadStatus.DOWNLOADING) {
                    MenuAction(
                        label = "Pause",
                        icon = Icons.Default.Pause,
                        tint = WarningOrange,
                        labelColor = TextPrimary
                    ) {
                        onExpandedChange(false)
                        onPause()
                    }
                }

                if (item.status == DownloadStatus.PAUSED) {
                    MenuAction(
                        label = "Resume",
                        icon = Icons.Default.PlayArrow,
                        tint = AccentCyan,
                        labelColor = AccentCyan
                    ) {
                        onExpandedChange(false)
                        onResume()
                    }
                }

                if (item.isActive || item.status == DownloadStatus.QUEUED) {
                    MenuAction(
                        label = "Cancel download",
                        icon = Icons.Default.Close,
                        tint = TextSecondary,
                        labelColor = TextPrimary
                    ) {
                        onExpandedChange(false)
                        onCancel()
                    }
                }
            }

            // Removing an item mid-download would strand the running process,
            // so the history actions only appear once it has stopped.
            if (!item.isActive && item.status != DownloadStatus.QUEUED) {
                MenuAction(
                    label = "Remove from list",
                    icon = Icons.Default.Delete,
                    tint = TextSecondary,
                    labelColor = TextPrimary
                ) {
                    onExpandedChange(false)
                    onRemoveFromList()
                }

                if (hasFileOnDisk) {
                    MenuAction(
                        label = "Delete file…",
                        icon = Icons.Default.DeleteForever,
                        tint = AccentPink,
                        labelColor = AccentPink
                    ) {
                        onExpandedChange(false)
                        onRequestDeleteFile()
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuAction(
    label: String,
    icon: ImageVector,
    tint: Color,
    labelColor: Color,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(label, color = labelColor, fontSize = 13.sp) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
        },
        onClick = onClick
    )
}
