package courier.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun rememberMediaPlayerController(filePath: String, isAudioOnly: Boolean): MediaPlayerController

@Composable
expect fun PlatformVideoPlayerSurface(
    controller: MediaPlayerController,
    modifier: Modifier = Modifier
)
