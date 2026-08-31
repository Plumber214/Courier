package courier.engine

import courier.model.Platform

object UrlValidator {
    private val urlRegex = Regex("""(https?://[^\s]+)""")

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

    fun isSupportedVideoUrl(text: String): Boolean {
        val url = extractUrl(text) ?: return false
        val platform = Platform.fromUrl(url)
        if (platform != Platform.OTHER) return true
        
        // Also support general video sites (e.g. Twitter/X, Reddit, Vimeo, Dailymotion, Twitch, etc.)
        val lowercase = url.lowercase()
        return lowercase.contains("twitter.com") || lowercase.contains("x.com") ||
               lowercase.contains("reddit.com") || lowercase.contains("v.redd.it") ||
               lowercase.contains("vimeo.com") || lowercase.contains("twitch.tv") ||
               lowercase.contains("dailymotion.com") || lowercase.contains("threads.net") ||
               lowercase.contains("pinterest.com") || lowercase.contains("pin.it") ||
               lowercase.contains(".mp4") || lowercase.contains(".m3u8") ||
               lowercase.startsWith("http")
    }

    fun cleanUrl(rawUrl: String): String {
        val extracted = extractUrl(rawUrl) ?: rawUrl.trim()
        // Strip trailing punctuation often caught in share messages (e.g. url, or url.)
        return extracted.trimEnd('.', ',', '!', '?', ';', ':', ')', ']', '}')
    }
}
