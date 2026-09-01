package courier.player

import android.media.MediaPlayer as AndroidAudioVideoPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AndroidMediaPlayerController(
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

    var mediaPlayer: AndroidAudioVideoPlayer? = null
        private set

    private var progressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    init {
        try {
            val player = AndroidAudioVideoPlayer().apply {
                setDataSource(filePath)
                setOnPreparedListener { mp ->
                    _durationMs.value = mp.duration.toLong()
                    mp.start()
                    _isPlaying.value = true
                    startProgressTracker()
                }
                setOnCompletionListener {
                    _isPlaying.value = false
                    _currentPositionMs.value = 0L
                }
                prepareAsync()
            }
            mediaPlayer = player
        } catch (e: Exception) {
            println("Error initializing Android MediaPlayer: ${e.message}")
        }
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                mediaPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        _currentPositionMs.value = mp.currentPosition.toLong()
                        _isPlaying.value = true
                    }
                }
                delay(250)
            }
        }
    }

    override fun play() {
        mediaPlayer?.let {
            if (!it.isPlaying) {
                it.start()
                _isPlaying.value = true
            }
        }
    }

    override fun pause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                _isPlaying.value = false
            }
        }
    }

    override fun togglePlayPause() {
        if (_isPlaying.value) pause() else play()
    }

    override fun seekTo(positionMs: Long) {
        mediaPlayer?.let {
            it.seekTo(positionMs.toInt())
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
        if (!_isMuted.value) {
            mediaPlayer?.setVolume(clamped, clamped)
        }
    }

    override fun toggleMute() {
        val newMuted = !_isMuted.value
        _isMuted.value = newMuted
        if (newMuted) {
            mediaPlayer?.setVolume(0f, 0f)
        } else {
            mediaPlayer?.setVolume(_volume.value, _volume.value)
        }
    }

    override fun release() {
        progressJob?.cancel()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            // ignore
        }
        mediaPlayer = null
    }
}

@Composable
actual fun rememberMediaPlayerController(filePath: String, isAudioOnly: Boolean): MediaPlayerController {
    val controller = remember(filePath) {
        AndroidMediaPlayerController(filePath, isAudioOnly)
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
    val androidController = controller as? AndroidMediaPlayerController

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        androidController?.mediaPlayer?.setDisplay(holder)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        androidController?.mediaPlayer?.setDisplay(null)
                    }
                })
            }
        },
        update = { surfaceView ->
            if (surfaceView.holder.surface.isValid) {
                androidController?.mediaPlayer?.setDisplay(surfaceView.holder)
            }
        }
    )
}
