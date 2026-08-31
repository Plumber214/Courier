package courier.player

import kotlinx.coroutines.flow.StateFlow

enum class MediaType {
    VIDEO,
    AUDIO
}

interface MediaPlayerController {
    val isPlaying: StateFlow<Boolean>
    val currentPositionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val volume: StateFlow<Float>
    val isMuted: StateFlow<Boolean>
    val isBuffering: StateFlow<Boolean>
    val mediaType: StateFlow<MediaType>

    fun play()
    fun pause()
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun skipForward(seconds: Int = 10)
    fun skipBackward(seconds: Int = 10)
    fun setVolume(volume: Float)
    fun toggleMute()
    fun release()
}
