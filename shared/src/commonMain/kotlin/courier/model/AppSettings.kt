package courier.model

import kotlinx.serialization.Serializable

/**
 * How downloaded video should be encoded.
 *
 * YouTube publishes no H.264 above 1080p — 1440p and 2160p exist only as VP9
 * and AV1, neither of which Adobe Premiere Pro can import ("unsupported
 * compression type av01"/"VP09"). That platform constraint forces a choice,
 * so it is surfaced as a setting rather than guessed at.
 */
@Serializable
enum class OutputProfile {
    /** H.264 + AAC, no re-encode. Always imports into editors. Capped ~1080p on YouTube. */
    EDITING_NATIVE,

    /** Highest resolution available (AV1/VP9). Will NOT import into most editors. */
    MAX_QUALITY,

    /** Highest resolution, then transcoded to an edit-ready codec. Slow, larger files. */
    EDITING_TRANSCODE
}

/** Target codec used when [OutputProfile.EDITING_TRANSCODE] re-encodes. */
@Serializable
enum class TranscodeCodec {
    /** libx264 CRF 18 in MP4. Moderate size, universally importable. */
    H264,

    /** Apple ProRes 422 HQ in MOV. Very large, best scrubbing performance. */
    PRORES,

    /** DNxHR HQ in MOV. Very large, Avid/Premiere native. */
    DNXHR
}

@Serializable
data class AppSettings(
    val defaultQuality: String = "best", // "best", "1080p", "720p", "480p", "audio_only"
    val downloadDirectory: String = "",
    val savedDownloadLocations: List<String> = emptyList(),
    val maxConcurrentDownloads: Int = 3,
    val selectedCookieBrowser: String = "None", // "None", "chrome", "firefox", "edge", "brave"
    val isFirstLaunchCompleted: Boolean = false,

    // Defaults to EDITING_NATIVE: a download that will not import into an editor
    // is not a successful download for this app's purpose.
    val outputProfile: OutputProfile = OutputProfile.EDITING_NATIVE,
    val transcodeCodec: TranscodeCodec = TranscodeCodec.H264,
    val lastEngineUpdateCheckEpochMs: Long = 0L,
    val autoCheckAppUpdates: Boolean = true,
    val lastAppUpdateCheckEpochMs: Long = 0L,

    /**
     * Whether Device Link runs at all.
     *
     * The subsystem is already dormant with no paired devices and the Devices
     * tab closed, but there was no way to say "never" — someone who does not
     * want a listening socket on their machine had to unpair everything and
     * hope.
     */
    val deviceLinkEnabled: Boolean = true,

    // Media options. Off by default: each one costs time or a re-mux, and the
    // v1.6 behaviour is what existing users are expecting from an update.
    val writeSubtitles: Boolean = false,
    val subtitleLanguages: List<String> = listOf("en"),
    val embedChapters: Boolean = false,
    val embedThumbnail: Boolean = false,
    val embedMetadata: Boolean = false
)
