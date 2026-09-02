package courier.platform

interface PlatformActions {
    fun getClipboardText(): String?
    fun setClipboardText(text: String)
    fun openFile(filePath: String): Boolean
    fun openFolder(folderPath: String): Boolean
    fun deleteFile(filePath: String): Boolean
    suspend fun chooseDirectory(): String?
    fun getDefaultDownloadDirectory(): String
    fun getAppStorageDirectory(): String
    fun getStandardMediaRoots(): List<String> = emptyList()
    suspend fun probeDirectoryWritable(path: String): Result<Unit> = Result.success(Unit)
    fun onDownloadStarted(title: String) {}
    fun onDownloadProgress(title: String, progress: Float, speed: String?, eta: String?) {}
    fun onDownloadStopped() {}

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
