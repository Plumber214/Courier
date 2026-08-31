package courier.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import javafx.application.Platform as JfxPlatform
import javafx.embed.swing.JFXPanel
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer as JfxMediaPlayer
import javafx.scene.media.MediaView
import javafx.scene.paint.Color as JfxColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class DesktopMediaPlayerController(
    val filePath: String,
    val isAudio: Boolean
) : MediaPlayerController {
    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    override val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _volume = MutableStateFlow(1.0f)
    override val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    override val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    override val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _mediaType = MutableStateFlow(if (isAudio) MediaType.AUDIO else MediaType.VIDEO)
    override val mediaType: StateFlow<MediaType> = _mediaType.asStateFlow()

    var jfxPlayer: JfxMediaPlayer? = null
        private set

    init {
        initJavaFX {
            try {
                val file = File(filePath)
                if (file.exists()) {
                    val media = Media(file.toURI().toString())
                    val player = JfxMediaPlayer(media)
                    jfxPlayer = player

                    player.setOnReady {
                        _durationMs.value = player.totalDuration.toMillis().toLong()
                        play()
                    }

                    player.currentTimeProperty().addListener { _, _, newTime ->
                        _currentPositionMs.value = newTime.toMillis().toLong()
                    }

                    player.setOnEndOfMedia {
                        _isPlaying.value = false
                        player.seek(javafx.util.Duration.ZERO)
                    }

                    player.setOnPlaying { _isPlaying.value = true }
                    player.setOnPaused { _isPlaying.value = false }
                    player.setOnStopped { _isPlaying.value = false }
                }
            } catch (e: Exception) {
                println("Error initializing Desktop media player: ${e.message}")
            }
        }
    }

    override fun play() {
        JfxPlatform.runLater {
            jfxPlayer?.play()
            _isPlaying.value = true
        }
    }

    override fun pause() {
        JfxPlatform.runLater {
            jfxPlayer?.pause()
            _isPlaying.value = false
        }
    }

    override fun togglePlayPause() {
        if (_isPlaying.value) pause() else play()
    }

    override fun seekTo(positionMs: Long) {
        JfxPlatform.runLater {
            jfxPlayer?.seek(javafx.util.Duration.millis(positionMs.toDouble()))
            _currentPositionMs.value = positionMs
        }
    }

    override fun skipForward(seconds: Int) {
        val target = (_currentPositionMs.value + seconds * 1000L).coerceAtMost(_durationMs.value)
        seekTo(target)
    }

    override fun skipBackward(seconds: Int) {
        val target = (_currentPositionMs.value - seconds * 1000L).coerceAtLeast(0L)
        seekTo(target)
    }

    override fun setVolume(vol: Float) {
        val clamped = vol.coerceIn(0f, 1f)
        _volume.value = clamped
        JfxPlatform.runLater {
            jfxPlayer?.volume = clamped.toDouble()
        }
    }

    override fun toggleMute() {
        val newMuted = !_isMuted.value
        _isMuted.value = newMuted
        JfxPlatform.runLater {
            jfxPlayer?.isMute = newMuted
        }
    }

    override fun release() {
        JfxPlatform.runLater {
            try {
                jfxPlayer?.stop()
                jfxPlayer?.dispose()
            } catch (e: Exception) {
                // ignore
            }
            jfxPlayer = null
        }
    }

    companion object {
        private var isJfxInitialized = false
        fun initJavaFX(onReady: () -> Unit) {
            if (isJfxInitialized) {
                onReady()
                return
            }
            try {
                JfxPlatform.startup {
                    isJfxInitialized = true
                    onReady()
                }
            } catch (e: IllegalStateException) {
                isJfxInitialized = true
                onReady()
            }
        }
    }
}

@Composable
actual fun rememberMediaPlayerController(filePath: String, isAudioOnly: Boolean): MediaPlayerController {
    val controller = remember(filePath) {
        DesktopMediaPlayerController(filePath, isAudioOnly)
    }

    DisposableEffect(controller) {
        onDispose {
            controller.release()
        }
    }

    return controller
}

@Composable
actual fun PlatformVideoPlayerSurface(
    controller: MediaPlayerController,
    modifier: Modifier
) {
    val desktopController = controller as? DesktopMediaPlayerController

    SwingPanel(
        modifier = modifier.fillMaxSize(),
        factory = {
            val jfxPanel = JFXPanel()
            DesktopMediaPlayerController.initJavaFX {
                val player = desktopController?.jfxPlayer
                val mediaView = MediaView(player).apply {
                    isPreserveRatio = true
                }

                val root = Group(mediaView)
                val scene = Scene(root, JfxColor.BLACK)
                jfxPanel.scene = scene

                jfxPanel.addComponentListener(object : java.awt.event.ComponentAdapter() {
                    override fun componentResized(e: java.awt.event.ComponentEvent) {
                        val w = jfxPanel.width.toDouble()
                        val h = jfxPanel.height.toDouble()
                        mediaView.fitWidth = w
                        mediaView.fitHeight = h
                    }
                })
            }
            jfxPanel
        },
        update = { jfxPanel ->
            DesktopMediaPlayerController.initJavaFX {
                val mediaView = (jfxPanel.scene?.root as? Group)?.children?.firstOrNull() as? MediaView
                if (mediaView != null && mediaView.mediaPlayer != desktopController?.jfxPlayer) {
                    mediaView.mediaPlayer = desktopController?.jfxPlayer
                }
            }
        }
    )
}
