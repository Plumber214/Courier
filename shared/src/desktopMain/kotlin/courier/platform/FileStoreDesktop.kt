package courier.platform

import java.io.File

actual fun saveTextFile(fileName: String, content: String) {
    try {
        val dir = File(getPlatformActions().getAppStorageDirectory())
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val targetFile = File(dir, fileName)
        val tempFile = File(dir, "$fileName.tmp")
        val backupFile = File(dir, "$fileName.bak")

        // 1. Write to temporary file with flush and sync
        java.io.FileOutputStream(tempFile).use { fos ->
            val bytes = content.toByteArray(Charsets.UTF_8)
            fos.write(bytes)
            fos.flush()
            fos.fd.sync()
        }

        // 2. If target file already exists and is non-empty, back it up
        if (targetFile.exists() && targetFile.length() > 0) {
            try {
                targetFile.copyTo(backupFile, overwrite = true)
            } catch (_: Exception) {}
        }

        // 3. Atomically replace targetFile with tempFile
        try {
            java.nio.file.Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            if (!tempFile.renameTo(targetFile)) {
                targetFile.delete()
                tempFile.renameTo(targetFile)
            }
        }
    } catch (e: Exception) {
        println("Error saving file $fileName: ${e.message}")
    }
}

actual fun readTextFile(fileName: String): String? {
    return try {
        val dir = File(getPlatformActions().getAppStorageDirectory())
        val targetFile = File(dir, fileName)
        val backupFile = File(dir, "$fileName.bak")

        if (targetFile.exists() && targetFile.length() > 0) {
            targetFile.readText(Charsets.UTF_8)
        } else if (backupFile.exists() && backupFile.length() > 0) {
            backupFile.readText(Charsets.UTF_8)
        } else {
            null
        }
    } catch (e: Exception) {
        try {
            val dir = File(getPlatformActions().getAppStorageDirectory())
            val backupFile = File(dir, "$fileName.bak")
            if (backupFile.exists() && backupFile.length() > 0) {
                backupFile.readText(Charsets.UTF_8)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
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
