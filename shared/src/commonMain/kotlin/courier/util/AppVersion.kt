package courier.util

object AppVersion {
    const val MAJOR = 1
    const val MINOR = 1
    const val PATCH = 0
    const val BUILD_NUMBER = 14
    const val VERSION_NAME = "1.1.0"
    const val RELEASE_DATE = "2026.08.30"
    const val GIT_BRANCH = "main"

    val DISPLAY_STRING: String
        get() = "Courier v$VERSION_NAME (Build $BUILD_NUMBER • $RELEASE_DATE)"

    val FOOTER_STRING: String
        get() = "v$VERSION_NAME • Build $BUILD_NUMBER"
}
