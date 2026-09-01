package courier.engine

import courier.model.OutputProfile
import courier.model.TranscodeCodec

/**
 * Builds yt-dlp format selectors and post-processing arguments.
 *
 * Lives in commonMain so Desktop and Android cannot drift apart — the previous
 * duplicated implementations are why a codec fix in one place did not reach the
 * other.
 *
 * ## Why codec filtering matters
 *
 * `ext=mp4` says nothing about the video codec. YouTube serves AV1 and VP9
 * inside MP4 containers, and neither imports into Adobe Premiere Pro
 * ("The file has an unsupported compression type"). Filtering on container
 * alone — which this app did — reliably produced unusable files:
 *
 *     -f "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best"
 *       -> 401+140  vcodec=av01.0.13M.08  3840x2160
 *
 * Worse, the unconstrained `bestvideo+bestaudio` fallback selects Opus audio,
 * which `--merge-output-format mp4` then muxes into an MP4 that no editor can
 * open at all:
 *
 *     -f "bestvideo+bestaudio"  ->  401+251  vcodec=av01  acodec=opus
 *
 * Hence the rule enforced throughout this file: **every alternative in a format
 * chain carries its own constraints.** A trailing unconstrained fallback
 * silently reintroduces the bug the first alternative prevents.
 */
object FormatSelector {

    /** yt-dlp height ceilings for the app's named quality presets. */
    private val PRESET_HEIGHTS = mapOf(
        "1080p" to 1080,
        "720p" to 720,
        "480p" to 480,
        "360p" to 360
    )

    /** True if [vcodec] is a codec mainstream editors can import directly. */
    fun isEditorFriendlyCodec(vcodec: String?): Boolean {
        val v = vcodec?.lowercase() ?: return false
        return v.startsWith("avc1") || v.startsWith("h264") ||
            v.startsWith("hvc1") || v.startsWith("hev1")
    }

    /**
     * The `-f` value for a video download.
     *
     * [formatId] is either null/"best", one of the named presets, or a raw
     * yt-dlp expression taken from the extracted resolution ladder (which
     * `YtDlpJsonParser` has already constrained).
     */
    fun videoFormatArg(formatId: String?, profile: OutputProfile): String {
        // A raw expression from the ladder is already fully specified.
        if (formatId != null && (formatId.contains("+") || formatId.contains("/"))) {
            return formatId
        }

        val height = PRESET_HEIGHTS[formatId]
        val h = if (height != null) "[height<=$height]" else ""

        return when (profile) {
            OutputProfile.EDITING_NATIVE -> listOf(
                "bestvideo[vcodec^=avc1]$h[ext=mp4]+bestaudio[acodec^=mp4a][ext=m4a]",
                "bestvideo[vcodec^=avc1]$h+bestaudio[acodec^=mp4a]",
                "best[vcodec^=avc1][acodec^=mp4a]$h",
                // Last resort: some sites publish no H.264 at all. Downloading
                // something beats failing; EDITING_TRANSCODE exists for that case.
                "best$h[ext=mp4]",
                "best$h"
            ).joinToString("/")

            // Reach for maximum resolution, but never mux Opus into MP4.
            OutputProfile.MAX_QUALITY,
            OutputProfile.EDITING_TRANSCODE -> listOf(
                "bestvideo$h+bestaudio[acodec^=mp4a]",
                "bestvideo$h+bestaudio",
                "best$h"
            ).joinToString("/")
        }
    }

    /**
     * Container to merge into before any re-encode.
     *
     * When a re-encode will follow, this deliberately differs from the recode
     * target. `--recode-video` is a *container* operation: if the merged file is
     * already in the target container yt-dlp skips it outright —
     *
     *     [VideoConvertor] Not converting media file "...mp4";
     *                      already is in target format mp4
     *
     * — and the AV1 stream survives untouched. Merging to MKV first guarantees
     * the conversion actually runs. When no re-encode is planned, merge straight
     * to MP4 so nothing needless happens.
     */
    fun mergeOutputFormat(
        profile: OutputProfile,
        selectedVcodec: String?,
        codec: TranscodeCodec
    ): String = if (needsTranscode(profile, selectedVcodec, codec)) "mkv" else "mp4"

    /**
     * Whether a download selected with [profile] needs re-encoding.
     *
     * [selectedVcodec] is the codec of the format the user picked, when known.
     * If it is already editor-friendly there is nothing to gain from a lossy
     * H.264 -> H.264 pass, so it is skipped.
     */
    fun needsTranscode(
        profile: OutputProfile,
        selectedVcodec: String?,
        codec: TranscodeCodec
    ): Boolean {
        if (profile != OutputProfile.EDITING_TRANSCODE) return false
        // ProRes/DNxHR are mezzanine targets - always transcode when asked,
        // even from H.264, because the point is edit performance not import.
        if (codec != TranscodeCodec.H264) return true
        // Codec unknown (a preset like "best" carries no vcodec): transcode
        // rather than gamble, since the user explicitly asked for edit-ready
        // output and MAX_QUALITY selection may well have picked AV1.
        if (selectedVcodec == null) return true
        return !isEditorFriendlyCodec(selectedVcodec)
    }

    /**
     * yt-dlp arguments that re-encode the finished file to [codec].
     *
     * `-fps_mode cfr` is deliberate: some source is variable-frame-rate, which
     * Premiere handles badly (progressive audio drift). Forcing constant frame
     * rate avoids it. `-pix_fmt yuv420p` on the H.264 path guards against 10-bit
     * output that older Premiere builds reject.
     */
    fun transcodeArgs(codec: TranscodeCodec): List<String> = when (codec) {
        TranscodeCodec.H264 -> listOf(
            "--recode-video", "mp4",
            "--postprocessor-args",
            "VideoConvertor:-c:v libx264 -preset medium -crf 18 -pix_fmt yuv420p " +
                "-c:a aac -b:a 192k -movflags +faststart -fps_mode cfr"
        )

        TranscodeCodec.PRORES -> listOf(
            "--recode-video", "mov",
            "--postprocessor-args",
            "VideoConvertor:-c:v prores_ks -profile:v 3 -pix_fmt yuv422p10le " +
                "-c:a pcm_s16le -fps_mode cfr"
        )

        TranscodeCodec.DNXHR -> listOf(
            "--recode-video", "mov",
            "--postprocessor-args",
            "VideoConvertor:-c:v dnxhd -profile:v dnxhr_hq -pix_fmt yuv422p " +
                "-c:a pcm_s16le -fps_mode cfr"
        )
    }
}
