package courier.platform

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File

class PlatformActionsDesktop : PlatformActions {
    override fun getClipboardText(): String? {
        return try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                clipboard.getData(DataFlavor.stringFlavor) as? String
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun setClipboardText(text: String) {
        try {
            val selection = StringSelection(text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
        } catch (e: Exception) {
            println("Error setting clipboard: ${e.message}")
        }
    }

    override fun openFile(filePath: String): Boolean {
        val file = File(filePath)
        if (!file.exists()) return false

        return try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(file)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            println("Error opening file: ${e.message}")
            false
        }
    }

    override fun openFolder(folderPath: String): Boolean {
        val folder = File(folderPath)
        val target = if (folder.isFile) folder.parentFile else folder
        if (target == null || !target.exists()) return false

        return try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(target)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            println("Error opening folder: ${e.message}")
            false
        }
    }

    override fun deleteFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (file.exists()) {
                file.delete()
            } else {
                true
            }
        } catch (e: Exception) {
            println("Error deleting file: ${e.message}")
            false
        }
    }

    override fun getDefaultDownloadDirectory(): String {
        val userHome = System.getProperty("user.home")
        val downloadsDir = File(userHome, "Downloads/Courier")
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }
        return downloadsDir.absolutePath
    }

    override fun getStandardMediaRoots(): List<String> {
        val userHome = File(System.getProperty("user.home"))
        val roots = mutableListOf(
            File(userHome, "Downloads"),
            File(userHome, "Videos"),
            File(userHome, "Pictures"),
            File(userHome, "Music"),
            File(userHome, "Documents")
        )
        // Every drive as well, so a second disk is reachable from the picker.
        // Without these, "somewhere on D:" could only be expressed through the
        // Swing chooser this replaces.
        try {
            for (root in File.listRoots()) {
                if (root != null && root.exists()) roots.add(root)
            }
        } catch (_: Exception) {}

        return roots.filter { it.exists() }.map { it.absolutePath }.distinct()
    }

    override fun canBrowseFilesystem(): Boolean = true

    override fun listSubdirectories(path: String): List<String> {
        return try {
            File(path).listFiles()
                ?.filter { it.isDirectory && !it.isHidden }
                ?.sortedBy { it.name.lowercase() }
                ?.map { it.absolutePath }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun parentDirectory(path: String): String? {
        return try {
            File(path).absoluteFile.parentFile?.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    override fun createSubdirectory(parent: String, name: String): Result<String> {
        return try {
            val clean = name.trim()
            // Rejected rather than sanitised: silently creating "Clips" when
            // the user typed "../Clips" would put files somewhere they did not
            // ask for.
            if (clean.isBlank() || clean.contains('/') || clean.contains('\\') || clean == "..") {
                return Result.failure(Exception("Folder name cannot contain a path separator"))
            }
            val dir = File(parent, clean)
            if (dir.exists()) {
                if (dir.isDirectory) Result.success(dir.absolutePath)
                else Result.failure(Exception("A file of that name already exists"))
            } else if (dir.mkdirs()) {
                Result.success(dir.absolutePath)
            } else {
                Result.failure(Exception("Could not create ${dir.absolutePath}"))
            }
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Could not create folder"))
        }
    }

    override suspend fun probeDirectoryWritable(path: String): Result<Unit> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val dir = File(path)
            if (!dir.exists()) {
                val created = dir.mkdirs()
                if (!created && !dir.exists()) {
                    return@withContext Result.failure(Exception("Cannot create directory: $path"))
                }
            }
            val testFile = File(dir, ".courier_probe_${System.currentTimeMillis()}.tmp")
            testFile.writeText("courier_probe_ok", Charsets.UTF_8)
            if (!testFile.exists() || testFile.readText() != "courier_probe_ok") {
                testFile.delete()
                return@withContext Result.failure(Exception("Write test failed at $path"))
            }
            testFile.delete()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("Directory is not writable: ${e.message}"))
        }
    }

    override fun getBuildTimestamp(): String? {
        return try {
            val source = PlatformActionsDesktop::class.java
                .protectionDomain?.codeSource?.location ?: return null
            val file = File(source.toURI())
            if (!file.exists()) return null
            // Running from a jar: the jar's mtime. Running from loose classes
            // (Gradle run): the classes dir mtime, which is still the last build.
            val instant = java.time.Instant.ofEpochMilli(file.lastModified())
            java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm")
                .withZone(java.time.ZoneId.systemDefault())
                .format(instant)
        } catch (e: Exception) {
            null
        }
    }

    override fun getAppStorageDirectory(): String {
        val appData = System.getenv("APPDATA")
        val baseDir = if (!appData.isNullOrBlank()) {
            File(appData, "Courier")
        } else {
            File(System.getProperty("user.home"), ".courier")
        }
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        return baseDir.absolutePath
    }

    override fun getDefaultDeviceName(): String = "Courier Desktop"
}

private val desktopPlatformActionsInstance: PlatformActions = PlatformActionsDesktop()
actual fun getPlatformActions(): PlatformActions = desktopPlatformActionsInstance
