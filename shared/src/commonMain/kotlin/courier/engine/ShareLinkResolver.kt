package courier.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object ShareLinkResolver {

    private val SHARE_PATTERNS = listOf(
        "/share/",
        "fb.watch/",
        "instagr.am/",
        "pin.it/",
        "vm.tiktok.com/",
        "vt.tiktok.com/"
    )

    fun isShareLink(url: String): Boolean {
        val lower = url.lowercase()
        return SHARE_PATTERNS.any { lower.contains(it) }
    }

    suspend fun resolve(rawUrl: String): String = withContext(Dispatchers.IO) {
        if (!isShareLink(rawUrl)) return@withContext rawUrl

        try {
            var currentUrl = rawUrl
            var redirects = 0
            val maxRedirects = 5

            while (redirects < maxRedirects) {
                val connection = URL(currentUrl).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.requestMethod = "HEAD"
                connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                )

                val responseCode = connection.responseCode
                if (responseCode in 300..399) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (!location.isNullOrBlank()) {
                        currentUrl = if (location.startsWith("http")) location else URL(URL(currentUrl), location).toString()
                        redirects++
                    } else {
                        break
                    }
                } else {
                    connection.disconnect()
                    break
                }
            }
            currentUrl
        } catch (_: Exception) {
            rawUrl
        }
    }
}
