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
}

expect fun getPlatformActions(): PlatformActions
