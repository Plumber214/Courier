package courier.platform

expect fun saveTextFile(fileName: String, content: String)
expect fun readTextFile(fileName: String): String?
expect fun fileExists(filePath: String): Boolean
expect fun deleteFile(filePath: String): Boolean
