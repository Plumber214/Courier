package courier.model

import kotlinx.serialization.Serializable

@Serializable
enum class Platform(val displayName: String, val brandColorHex: Long) {
    YOUTUBE("YouTube", 0xFFFF0000),
    TIKTOK("TikTok", 0xFF00F2FE),
    INSTAGRAM("Instagram", 0xFFE1306C),
    FACEBOOK("Facebook", 0xFF1877F2),
    OTHER("Web Video", 0xFF7D9BB8);

    companion object {
        fun fromUrl(url: String): Platform {
            val lowercase = url.lowercase().trim()
            return when {
                lowercase.contains("youtube.com") || lowercase.contains("youtu.be") -> YOUTUBE
                lowercase.contains("tiktok.com") -> TIKTOK
                lowercase.contains("instagram.com") || lowercase.contains("instagr.am") -> INSTAGRAM
                lowercase.contains("facebook.com") || lowercase.contains("fb.watch") || lowercase.contains("fb.com") -> FACEBOOK
                else -> OTHER
            }
        }
    }
}
