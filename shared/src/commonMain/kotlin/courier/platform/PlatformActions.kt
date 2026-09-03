package courier.platform

interface PlatformActions {
    fun getClipboardText(): String?
    fun setClipboardText(text: String)
    fun openFile(filePath: String): Boolean
    fun openFolder(folderPath: String): Boolean
    fun deleteFile(filePath: String): Boolean
    fun getDefaultDownloadDirectory(): String
    fun getAppStorageDirectory(): String
    fun getStandardMediaRoots(): List<String> = emptyList()

    /**
     * Whether directories under [getStandardMediaRoots] can be listed.
     *
     * False on Android, where scoped storage means the app cannot enumerate
     * arbitrary directories — there the picker offers a media root plus a
     * subfolder name instead of a browser.
     */
    fun canBrowseFilesystem(): Boolean = false

    /** Immediate, non-hidden subdirectories of [path], as absolute paths. */
    fun listSubdirectories(path: String): List<String> = emptyList()

    /** The containing directory of [path], or null at a filesystem root. */
    fun parentDirectory(path: String): String? = null

    /** Creates [name] inside [parent]; returns the new directory's path. */
    fun createSubdirectory(parent: String, name: String): Result<String> =
        Result.failure(UnsupportedOperationException("Not supported on this platform"))

    suspend fun probeDirectoryWritable(path: String): Result<Unit> = Result.success(Unit)
    fun onDownloadStarted(title: String) {}
    fun onDownloadProgress(title: String, progress: Float, speed: String?, eta: String?) {}
    fun onDownloadStopped() {}
    fun onDeviceLinkStateChanged(
        pairedCount: Int,
        connectedCount: Int,
        primaryDeviceName: String?,
        primaryDeviceStatus: String?
    ) {}

    /**
     * When the artifact currently executing was built, as "yyyy-MM-dd HH:mm",
     * or null if it cannot be determined (e.g. running from loose classes).
     *
     * Read from the running jar/APK, not baked in at compile time, so it always
     * describes what is actually running.
     */
    fun getBuildTimestamp(): String?
    fun getDefaultDeviceName(): String = "Courier Desktop"
    fun isAndroid(): Boolean = false
}

expect fun getPlatformActions(): PlatformActions
