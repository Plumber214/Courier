package courier.platform

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

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

    override fun chooseDirectory(): String? {
        return try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
            val chooser = JFileChooser().apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                dialogTitle = "Select Download Destination Folder"
                isAcceptAllFileFilterUsed = false
            }
            val result = chooser.showOpenDialog(null)
            if (result == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile.absolutePath
            } else {
                null
            }
        } catch (e: Exception) {
            println("Error choosing directory: ${e.message}")
            null
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
}

actual fun getPlatformActions(): PlatformActions = PlatformActionsDesktop()
