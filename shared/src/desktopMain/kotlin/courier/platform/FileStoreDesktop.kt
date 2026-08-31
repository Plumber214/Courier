package courier.platform

import java.io.File

actual fun saveTextFile(fileName: String, content: String) {
    try {
        val dir = File(getPlatformActions().getAppStorageDirectory())
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val file = File(dir, fileName)
        file.writeText(content, Charsets.UTF_8)
    } catch (e: Exception) {
        println("Error saving file $fileName: ${e.message}")
    }
}

actual fun readTextFile(fileName: String): String? {
    return try {
        val dir = File(getPlatformActions().getAppStorageDirectory())
        val file = File(dir, fileName)
        if (file.exists()) {
            file.readText(Charsets.UTF_8)
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}

actual fun fileExists(filePath: String): Boolean {
    return try {
        File(filePath).exists()
    } catch (e: Exception) {
        false
    }
}

actual fun deleteFile(filePath: String): Boolean {
    return try {
        File(filePath).delete()
    } catch (e: Exception) {
        false
    }
}
