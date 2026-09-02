package courier.update

data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null
) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int {
        if (this.major != other.major) return this.major.compareTo(other.major)
        if (this.minor != other.minor) return this.minor.compareTo(other.minor)
        if (this.patch != other.patch) return this.patch.compareTo(other.patch)
        
        // Non-prerelease is newer than prerelease (e.g. 1.5.0 > 1.5.0-rc1)
        if (this.preRelease == null && other.preRelease != null) return 1
        if (this.preRelease != null && other.preRelease == null) return -1
        if (this.preRelease != null && other.preRelease != null) {
            return this.preRelease.compareTo(other.preRelease)
        }
        return 0
    }

    override fun toString(): String {
        return if (preRelease != null) "$major.$minor.$patch-$preRelease" else "$major.$minor.$patch"
    }

    companion object {
        fun parse(versionStr: String): SemanticVersion? {
            val cleaned = versionStr.trim().removePrefix("v").removePrefix("V")
            val parts = cleaned.split("-", limit = 2)
            val versionParts = parts[0].split(".")
            if (versionParts.isEmpty()) return null

            val major = versionParts.getOrNull(0)?.toIntOrNull() ?: return null
            val minor = versionParts.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = versionParts.getOrNull(2)?.toIntOrNull() ?: 0
            val preRelease = parts.getOrNull(1)?.takeIf { it.isNotBlank() }

            return SemanticVersion(major, minor, patch, preRelease)
        }
    }
}