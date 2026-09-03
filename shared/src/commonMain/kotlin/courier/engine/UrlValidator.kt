package courier.engine

import courier.model.Platform

object UrlValidator {
    private val urlRegex = Regex("""(https?://[^\s]+)""")

    /**
     * Sites yt-dlp handles that [Platform] does not name.
     *
     * Matched by host suffix, never by `contains`. A substring test on
     * "x.com" also matches `notx.com` and `x.com.phishing.example`, which is
     * how a link-detector starts volunteering on things it does not support.
     */
    private val SUPPORTED_HOSTS = setOf(
        "twitter.com", "x.com",
        "reddit.com", "redd.it",
        "vimeo.com",
        "twitch.tv",
        "dailymotion.com", "dai.ly",
        "threads.net",
        "pinterest.com", "pin.it",
        "soundcloud.com",
        "bilibili.com",
        "streamable.com",
        "bsky.app"
    )

    /** Extensions that are a media file rather than a page describing one. */
    private val MEDIA_EXTENSIONS = listOf(
        ".mp4", ".m3u8", ".webm", ".mkv", ".mov", ".m4v", ".mp3", ".m4a", ".opus"
    )

    fun extractUrl(text: String): String? {
        val trimmed = text.trim()
        val match = urlRegex.find(trimmed)
        return match?.value?.trim()
    }

    fun isValidUrl(url: String): Boolean {
        val extracted = extractUrl(url) ?: return false
        val lowercase = extracted.lowercase()
        return lowercase.startsWith("http://") || lowercase.startsWith("https://")
    }

    /**
     * The host of [url], lowercased and without a leading `www.`, or null if it
     * has none.
     *
     * Hand-parsed rather than using java.net.URI so this stays in commonMain
     * alongside the rest of the validator.
     */
    fun hostOf(url: String): String? {
        val afterScheme = url.trim().substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        val authority = afterScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        val host = authority
            .substringAfterLast('@') // discard any userinfo
            .substringBefore(':')    // discard any port
            .lowercase()
            .removePrefix("www.")
        return host.ifBlank { null }
    }

    private fun hostMatches(host: String, candidate: String): Boolean =
        host == candidate || host.endsWith(".$candidate")

    /**
     * Whether Courier should *volunteer* that this link looks downloadable.
     *
     * Deliberately narrow. This drives the clipboard banner, so a false positive
     * means the app announces that it noticed something the user copied — a
     * bank page, a ticket, an internal document. Until v1.7.0 the final clause
     * of this check was `startsWith("http")`, which is true of every URL, so the
     * banner fired on everything.
     *
     * The Fetch button stays willing to attempt any URL the user pastes
     * deliberately; this governs only what the app offers unprompted.
     */
    fun isSupportedVideoUrl(text: String): Boolean {
        val url = extractUrl(text) ?: return false
        if (Platform.fromUrl(url) != Platform.OTHER) return true

        val host = hostOf(url) ?: return false
        if (SUPPORTED_HOSTS.any { hostMatches(host, it) }) return true

        val path = url.lowercase().substringBefore('?').substringBefore('#')
        return MEDIA_EXTENSIONS.any { path.endsWith(it) }
    }

    fun cleanUrl(rawUrl: String): String {
        val extracted = extractUrl(rawUrl) ?: rawUrl.trim()
        // Strip trailing punctuation often caught in share messages (e.g. url, or url.)
        return extracted.trimEnd('.', ',', '!', '?', ';', ':', ')', ']', '}')
    }

    /**
     * The playlist id in [url], if it names one.
     *
     * A link copied from inside a playlist carries `list=`, and yt-dlp expands
     * that into every video in the playlist by default — turning one intended
     * download into dozens. Courier passes `--no-playlist` unless the user asks
     * otherwise; this is how the picker knows there is something to ask about.
     *
     * YouTube's auto-generated mixes (`RD…`) are excluded: they are an endless
     * radio-style feed rather than a finite list, so offering to download "all"
     * of one would be a mistake.
     */
    fun playlistIdOf(url: String): String? {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return null

        val id = query.split('&')
            .firstOrNull { it.startsWith("list=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }
            ?: return null

        if (id.startsWith("RD", ignoreCase = true)) return null
        return id
    }

    enum class MediaHint { LIKELY_VIDEO, LIKELY_PHOTO, UNKNOWN }

    fun hintFor(url: String): MediaHint {
        val lower = url.lowercase()
        return when {
            lower.contains("/reel/") || lower.contains("/reels/") || lower.contains("/tv/") ||
            lower.contains("watch?v=") || lower.contains("/videos/") || lower.contains("fb.watch") -> MediaHint.LIKELY_VIDEO
            lower.contains("/photo/") || lower.contains("photo.php") || lower.contains("fbid=") || lower.contains("/media/set/") -> MediaHint.LIKELY_PHOTO
            else -> MediaHint.UNKNOWN
        }
    }
}
