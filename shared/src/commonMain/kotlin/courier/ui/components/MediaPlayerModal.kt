package courier.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import courier.model.DownloadItem
import courier.player.PlatformVideoPlayerSurface
import courier.player.rememberMediaPlayerController
import courier.ui.theme.AccentCyan
import courier.ui.theme.CardBorderDark
import courier.ui.theme.GlassBorderGradient
import courier.ui.theme.PrimaryIndigo
import courier.ui.theme.SurfaceVariantDark
import courier.ui.theme.TextMuted
import courier.ui.theme.TextPrimary
import courier.ui.theme.TextSecondary

@Composable
fun MediaPlayerModal(
    item: DownloadItem,
    onDismiss: () -> Unit,
    onOpenExternal: () -> Unit,
    onOpenFolder: () -> Unit
) {
    val filePath = item.outputPath ?: ""
    val isAudio = item.isAudioOnly || filePath.endsWith(".mp3", ignoreCase = true) || filePath.endsWith(".m4a", ignoreCase = true) || filePath.endsWith(".wav", ignoreCase = true) || filePath.endsWith(".opus", ignoreCase = true)
    
    val controller = rememberMediaPlayerController(filePath = filePath, isAudioOnly = isAudio)
    val isPlaying by controller.isPlaying.collectAsState()
    val positionMs by controller.currentPositionMs.collectAsState()
    val durationMs by controller.durationMs.collectAsState()
    val volume by controller.volume.collectAsState()
    val isMuted by controller.isMuted.collectAsState()

    var isDraggingSlider by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xBA000000))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .clip(RoundedCornerShape(22.dp))
                    .border(1.5.dp, GlassBorderGradient, RoundedCornerShape(22.dp))
                    .clickable(enabled = false) {}, // Prevent dismiss on modal click
                color = Color(0xF20E1120),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    // Header Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(Color(item.platform.brandColorHex).copy(alpha = 0.22f), RoundedCornerShape(8.dp))
                                    .border(1.dp, Color(item.platform.brandColorHex).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    item.platform.displayName,
                                    color = Color(item.platform.brandColorHex),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Text(
                                text = item.title,
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = onOpenFolder,
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(SurfaceVariantDark, CircleShape)
                                    .border(1.dp, CardBorderDark, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = "Open Folder",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            IconButton(
                                onClick = onOpenExternal,
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(SurfaceVariantDark, CircleShape)
                                    .border(1.dp, CardBorderDark, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = "Open in External App",
                                    tint = AccentCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

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
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Viewport: Video or Audio Visualizer
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF06070B))
                            .border(1.dp, CardBorderDark, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isAudio) {
                            // Audio visualizer mode
                            AudioVisualizerView(
                                item = item,
                                isPlaying = isPlaying
                            )
                        } else {
                            // Video playback surface
                            PlatformVideoPlayerSurface(
                                controller = controller,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Scrubber Bar & Timestamps
                    val progressRatio = if (durationMs > 0) {
                        if (isDraggingSlider) dragProgress else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    } else 0f

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Slider(
                            value = progressRatio,
                            onValueChange = {
                                isDraggingSlider = true
                                dragProgress = it
                            },
                            onValueChangeFinished = {
                                isDraggingSlider = false
                                val targetMs = (dragProgress * durationMs).toLong()
                                controller.seekTo(targetMs)
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = AccentCyan,
                                activeTrackColor = AccentCyan,
                                inactiveTrackColor = SurfaceVariantDark
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(22.dp)
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val currentDisplayMs = if (isDraggingSlider) (dragProgress * durationMs).toLong() else positionMs
                            Text(
                                text = formatDuration(currentDisplayMs),
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = formatDuration(durationMs),
                                color = TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Player Control Buttons & Volume
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left: Volume Controls
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.width(140.dp)
                        ) {
                            IconButton(
                                onClick = { controller.toggleMute() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = if (isMuted || volume == 0f) Icons.AutoMirrored.Filled.VolumeMute else Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = "Mute",
                                    tint = if (isMuted) TextMuted else AccentCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Slider(
                                value = if (isMuted) 0f else volume,
                                onValueChange = {
                                    if (isMuted) controller.toggleMute()
                                    controller.setVolume(it)
                                },
                                colors = SliderDefaults.colors(
                                    thumbColor = TextPrimary,
                                    activeTrackColor = PrimaryIndigo,
                                    inactiveTrackColor = SurfaceVariantDark
                                ),
                                modifier = Modifier.height(18.dp)
                            )
                        }

                        // Center: Skip -10s, Play/Pause, Skip +10s
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            IconButton(
                                onClick = { controller.skipBackward(10) },
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(SurfaceVariantDark, CircleShape)
                                    .border(1.dp, CardBorderDark, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Replay10,
                                    contentDescription = "Skip back 10s",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .background(PrimaryIndigo, CircleShape)
                                    .border(1.5.dp, AccentCyan.copy(alpha = 0.8f), CircleShape)
                                    .clickable { controller.togglePlayPause() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(30.dp)
                                )
                            }

                            IconButton(
                                onClick = { controller.skipForward(10) },
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(SurfaceVariantDark, CircleShape)
                                    .border(1.dp, CardBorderDark, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Forward10,
                                    contentDescription = "Skip forward 10s",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        // Right: Media Type Pill
                        Box(
                            modifier = Modifier
                                .background(PrimaryIndigo.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                                .border(1.dp, PrimaryIndigo.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isAudio) Icons.Default.MusicNote else Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = AccentCyan,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isAudio) "Audio Only" else (item.formatLabel ?: "Video"),
                                    color = AccentCyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioVisualizerView(
    item: DownloadItem,
    isPlaying: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "Visualizer")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "VinylRotation"
    )

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Spinning Vinyl Thumbnail
        Box(
            modifier = Modifier
                .size(120.dp)
                .rotate(if (isPlaying) rotation else 0f)
                .clip(CircleShape)
                .background(Color(0xFF141724))
                .border(2.dp, AccentCyan.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            NetworkImage(
                url = item.thumbnailUrl,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.size(48.dp)
                )
            }

            // Center spindle hole
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(Color(0xFF090A10), CircleShape)
                    .border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape)
            )
        }

        Spacer(modifier = Modifier.width(36.dp))

        // Animated Frequency Waveform Bars
        Row(
            modifier = Modifier.height(70.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val barCount = 12
            for (i in 0 until barCount) {
                val waveHeight by infiniteTransition.animateFloat(
                    initialValue = 0.2f,
                    targetValue = 1.0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 400 + (i * 90), easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "WaveBar_$i"
                )

                val activeFactor = if (isPlaying) waveHeight else 0.15f
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .fillMaxHeight(fraction = activeFactor)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (i % 2 == 0) AccentCyan else PrimaryIndigo)
                )
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    if (millis <= 0) return "00:00"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hours = minutes / 60
    return if (hours > 0) {
        val remainingMinutes = minutes % 60
        "${hours.toString().padStart(2, '0')}:${remainingMinutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}
