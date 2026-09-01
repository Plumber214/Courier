package courier.engine

import courier.model.Platform

object ExtractionError {
    fun friendlyMessage(err: Throwable, url: String): String {
        val raw = err.message.orEmpty()
        val platform = Platform.fromUrl(url)
        return when {
            raw.contains("There is no video in this post", ignoreCase = true) ->
                "This post contains no video. Courier could not read its photos — try again, or set a cookie browser in Settings."

            raw.contains("empty media response", ignoreCase = true) ||
            raw.contains("login", ignoreCase = true) ||
            raw.contains("rate-limit", ignoreCase = true) ->
                "${platform.displayName} requires you to be signed in for this post. " +
                "Open Settings and pick the browser you are signed into under \"Cookies from browser\"."

            raw.contains("Unsupported URL", ignoreCase = true) && platform == Platform.FACEBOOK ->
                "Courier cannot download Facebook photos yet. Facebook videos and reels work."

            raw.contains("Unsupported URL", ignoreCase = true) ->
                "This link isn't supported. If it's a share link, try opening it and copying the full URL."

            raw.contains("timed out", ignoreCase = true) ->
                "Timed out reading this link. Check your connection and try again."

            raw.isBlank() -> "Could not read this link."
            else -> raw.removePrefix("ERROR:").trim().take(300)
        }
    }
}
