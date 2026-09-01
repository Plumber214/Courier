package courier.util

import courier.platform.getPlatformActions

object AppVersion {
    // Keep VERSION_NAME and BUILD_NUMBER in sync with gradle.properties.
    // The root `checkVersionConsistency` task fails the build if they drift.
    const val MAJOR = 1
    const val MINOR = 3
    const val PATCH = 0
    const val BUILD_NUMBER = 19
    const val VERSION_NAME = "1.3.0"
    const val RELEASE_DATE = "2026.09.01"
    const val GIT_BRANCH = "main"

    val DISPLAY_STRING: String
        get() = "Courier v$VERSION_NAME (Build $BUILD_NUMBER • $RELEASE_DATE)"

    val FOOTER_STRING: String
        get() = "v$VERSION_NAME • Build $BUILD_NUMBER"

    /**
     * When the artifact currently running was actually built, read from the
     * running jar/APK itself rather than baked in at compile time.
     *
     * A compile-time constant tells you what the source said; this tells you
     * what you are actually running. Those differed for an entire release cycle,
     * so this is the value worth surfacing.
     */
    val BUILD_TIMESTAMP: String
        get() = getPlatformActions().getBuildTimestamp() ?: "unknown"

    /** Full identity line for Settings — version, build, and artifact age. */
    val BUILD_IDENTITY: String
        get() = "Courier v$VERSION_NAME (Build $BUILD_NUMBER) • built $BUILD_TIMESTAMP"
}
