package courier.engine

import courier.model.GalleryEntry
import courier.model.MediaType
import courier.model.Platform
import courier.model.VideoFormat
import courier.model.VideoInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One candidate video rendition from yt-dlp's `formats` array. */
private data class FormatCandidate(
    val height: Int,
    val formatId: String,
    val vcodec: String?,
    val acodec: String?,
    val ext: String,
    val fileSizeBytes: Long?,
    val fps: Int?
)

object YtDlpJsonParser {

    private val json = Json { ignoreUnknownKeys = true }
    private val VIDEO_EXTS = setOf("mp4", "webm", "mkv", "mov", "m4v", "avi")
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic")

    /**
     * Widest thumbnail worth fetching for display. The largest surface that
     * shows one is a dialog preview a few hundred dp across, so 640 is generous
     * while still an order of magnitude cheaper to decode than a 1080px+ original.
     */
    private const val DISPLAY_THUMB_MAX_WIDTH = 640

    /** Throws IllegalArgumentException if [rawJson] is not a JSON object. */
    fun parse(rawJson: String, url: String): VideoInfo {
        require(rawJson.trimStart().startsWith("{")) { "Not a JSON object" }
        val root = json.parseToJsonElement(rawJson).jsonObject
        val platform = Platform.fromUrl(url)

        val entriesArray = root["entries"]?.let { runCatching { it.jsonArray }.getOrNull() }

        val galleryEntries = entriesArray.orEmpty().mapIndexed { idx, elem ->
            val obj = elem.jsonObject
            GalleryEntry(
                index = idx + 1,
                id = obj.str("id") ?: "item_${idx + 1}",
                title = obj.str("title"),
                thumbnailUrl = obj.displayThumbnail(),
                directUrl = obj.str("url"),
                isVideo = obj.looksLikeVideo()
            )
        }

        val mediaType = when {
            galleryEntries.size > 1 -> MediaType.GALLERY
            galleryEntries.size == 1 ->
                if (galleryEntries.first().isVideo) MediaType.VIDEO else MediaType.IMAGE
            root.looksLikeImage() -> MediaType.IMAGE
            else -> MediaType.VIDEO
        }

        val channel = root.str("channel") ?: root.str("uploader")
        val rawTitle = root.str("title")
        val title = cleanTitle(rawTitle, mediaType, channel, platform)

        return VideoInfo(
            id = root.str("id") ?: "media_${url.hashCode()}",
            url = url,
            title = title,
            uploader = root.str("uploader") ?: channel,
            durationSeconds = root["duration"]?.jsonPrimitive?.longOrNull,
            thumbnailUrl = galleryEntries.firstOrNull()?.thumbnailUrl ?: root.displayThumbnail(),
            platform = platform,
            formats = buildFormats(root, mediaType),
            // The untouched original, kept for reference. Not used to download —
            // the image path passes --write-thumbnail and lets yt-dlp resolve it.
            directVideoUrl = if (mediaType == MediaType.IMAGE) root.fullResThumbnail() else null,
            mediaType = mediaType,
            galleryEntries = galleryEntries
        )
    }

    // --- classification ------------------------------------------------------

    /**
     * A post is an image when yt-dlp found no playable formats but did find
     * thumbnails, and reported no duration.
     *
     * All three conditions are required: a login-walled *video* also yields an
     * empty formats array, and must NOT be misfiled as a photo.
     */
    private fun JsonObject.looksLikeImage(): Boolean {
        val ext = str("ext")?.lowercase()
        if (ext != null && ext in IMAGE_EXTS) return true
        if (ext != null && ext in VIDEO_EXTS) return false

        val hasFormats = this["formats"]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.isNotEmpty() == true
        val hasThumbs = this["thumbnails"]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.isNotEmpty() == true
        val hasDuration = this["duration"]?.jsonPrimitive?.longOrNull != null

        return !hasFormats && hasThumbs && !hasDuration
    }

    private fun JsonObject.looksLikeVideo(): Boolean = !looksLikeImage()

    /** Largest available thumbnail. yt-dlp orders `thumbnails` smallest-first. */
    private fun JsonObject.fullResThumbnail(): String? =
        this["thumbnails"]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.lastOrNull()?.jsonObject?.str("url")
            ?: str("thumbnail")

    /**
     * A thumbnail sized for on-screen display rather than the largest available.
     *
     * Every consumer of `thumbnailUrl` renders into a small surface — a 105dp
     * grid cell, a 100x72dp list card, a dialog preview. Handing them the
     * full-resolution original meant downloading and decoding a 1080px+ image
     * per cell and holding up to 50 of them in the bitmap cache, which is what
     * made the gallery picker stutter.
     *
     * Downloads are unaffected: the image path uses `--write-thumbnail`, so
     * yt-dlp picks the full-resolution original itself and never consults this.
     */
    private fun JsonObject.displayThumbnail(): String? {
        val candidates = this["thumbnails"]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.mapNotNull { elem ->
                val obj = runCatching { elem.jsonObject }.getOrNull() ?: return@mapNotNull null
                val url = obj.str("url") ?: return@mapNotNull null
                url to obj.thumbnailWidth()
            }
            .orEmpty()

        if (candidates.isEmpty()) return str("thumbnail")

        val sized = candidates.filter { it.second != null }
        if (sized.isNotEmpty()) {
            // Largest that still fits the display budget, else the smallest we have.
            return sized.lastOrNull { it.second!! <= DISPLAY_THUMB_MAX_WIDTH }?.first
                ?: sized.minByOrNull { it.second!! }!!.first
        }

        // No width metadata anywhere (single Instagram photos report none).
        // The array is ordered smallest-first, so take roughly two thirds up:
        // comfortably larger than a grid cell, well short of the original.
        return candidates[(candidates.size * 2) / 3].first
    }

    /** Thumbnail width from the explicit field, else parsed from an `sWxH` URL segment. */
    private fun JsonObject.thumbnailWidth(): Int? {
        this["width"]?.jsonPrimitive?.intOrNull?.let { return it }
        val url = str("url") ?: return null
        return Regex("""[/_]s(\d+)x\d+""").find(url)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * yt-dlp labels every Instagram post "Video by <user>", including photos.
     * See `format_field(product_info, [('user','username',...)], 'Video by %s')`.
     */
    private fun cleanTitle(
        raw: String?,
        mediaType: MediaType,
        channel: String?,
        platform: Platform
    ): String {
        val fallbackNoun = when (mediaType) {
            MediaType.IMAGE -> "Photo"
            MediaType.GALLERY -> "Post"
            MediaType.AUDIO -> "Audio"
            MediaType.VIDEO -> "Video"
        }
        if (raw.isNullOrBlank()) {
            return if (channel != null) "$fallbackNoun by $channel"
                   else "${platform.displayName} $fallbackNoun"
        }
        if (mediaType == MediaType.IMAGE || mediaType == MediaType.GALLERY) {
            if (raw.startsWith("Video by ", ignoreCase = true)) {
                return "$fallbackNoun by ${raw.removePrefix("Video by ").removePrefix("video by ")}"
            }
        }
        return raw
    }

    // --- formats -------------------------------------------------------------

    private fun buildFormats(root: JsonObject, mediaType: MediaType): List<VideoFormat> {
        when (mediaType) {
            MediaType.IMAGE -> return listOf(
                VideoFormat(
                    formatId = "photo",
                    qualityLabel = "Original Photo",
                    resolution = "Original",
                    ext = root.str("ext")?.takeIf { it in IMAGE_EXTS } ?: "jpg"
                )
            )
            MediaType.GALLERY -> return listOf(
                VideoFormat("gallery", "All Items", resolution = "Original", ext = "jpg")
            )
            else -> Unit
        }

        val out = mutableListOf(
            VideoFormat("best", "Best Available Quality", resolution = "Highest", ext = "mp4")
        )

        // Collect every video-bearing format, then pick ONE per height.
        //
        // Previously this took whichever format appeared first for a given
        // height, which on YouTube is frequently the AV1 rendition — so picking
        // "1080p" handed back format 399 (av01), which Premiere cannot import.
        // Prefer H.264 within each height instead.
        val candidates = root["formats"]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.mapNotNull { elem ->
                val f = elem.jsonObject
                val height = f["height"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val fmtId = f.str("format_id") ?: return@mapNotNull null
                if (height < 240) return@mapNotNull null
                FormatCandidate(
                    height = height,
                    formatId = fmtId,
                    vcodec = f.str("vcodec")?.takeIf { it != "none" },
                    acodec = f.str("acodec")?.takeIf { it != "none" },
                    ext = f.str("ext") ?: "mp4",
                    fileSizeBytes = f["filesize"]?.jsonPrimitive?.longOrNull,
                    fps = f["fps"]?.jsonPrimitive?.intOrNull
                )
            }
            .orEmpty()

        candidates
            .groupBy { it.height }
            .toSortedMap(compareByDescending { it })
            .forEach { (height, group) ->
                val chosen = group.firstOrNull { FormatSelector.isEditorFriendlyCodec(it.vcodec) }
                    ?: group.first()
                val tier = when {
                    height >= 1080 -> " Full HD"
                    height >= 720 -> " HD"
                    else -> " SD"
                }
                out.add(
                    VideoFormat(
                        // Constrain the audio leg too: a bare `bestaudio` selects
                        // Opus on YouTube, which cannot be muxed into a usable MP4.
                        formatId = "${chosen.formatId}+bestaudio[acodec^=mp4a]/bestaudio/best",
                        qualityLabel = "${height}p$tier",
                        resolution = "${height}p",
                        ext = chosen.ext,
                        fileSizeBytes = chosen.fileSizeBytes,
                        fps = chosen.fps,
                        vcodec = chosen.vcodec,
                        acodec = chosen.acodec
                    )
                )
            }
        if (out.size <= 1) {
            out += listOf(
                VideoFormat("1080p", "1080p Full HD", resolution = "1080p", ext = "mp4"),
                VideoFormat("720p", "720p HD", resolution = "720p", ext = "mp4"),
                VideoFormat("480p", "480p SD", resolution = "480p", ext = "mp4"),
                VideoFormat("360p", "360p Standard", resolution = "360p", ext = "mp4")
            )
        }
        out += VideoFormat("bestaudio", "Best Audio Quality (M4A)", ext = "m4a", isAudioOnly = true)
        out += VideoFormat("mp3", "MP3 Audio (Converted 320kbps)", ext = "mp3", isAudioOnly = true)
        return out
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
}
