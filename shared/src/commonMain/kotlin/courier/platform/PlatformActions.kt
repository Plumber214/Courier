package courier.platform

interface PlatformActions {
    fun getClipboardText(): String?
    fun setClipboardText(text: String)
    fun openFile(filePath: String): Boolean
    fun openFolder(folderPath: String): Boolean
    fun deleteFile(filePath: String): Boolean
    fun chooseDirectory(): String?
    fun getDefaultDownloadDirectory(): String
    fun getAppStorageDirectory(): String

    /**
     * When the artifact currently executing was built, as "yyyy-MM-dd HH:mm",
     * or null if it cannot be determined (e.g. running from loose classes).
     *
     * Read from the running jar/APK, not baked in at compile time, so it always
     * describes what is actually running.
     */
    fun getBuildTimestamp(): String?
    fun isAndroid(): Boolean = false
}

expect fun getPlatformActions(): PlatformActions
