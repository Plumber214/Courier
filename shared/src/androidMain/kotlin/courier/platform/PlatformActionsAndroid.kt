package courier.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

class PlatformActionsAndroid : PlatformActions {
    private val context: Context
        get() = AppContextHolder.appContext

    override fun getClipboardText(): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip ?: return null
        if (clip.itemCount > 0) {
            val item = clip.getItemAt(0)
            return item.text?.toString() ?: item.uri?.toString()
        }
        return null
    }

    override fun setClipboardText(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("Courier Link", text)
        clipboard?.setPrimaryClip(clip)
    }

    override fun openFile(filePath: String): Boolean {
        val file = File(filePath)
        if (!file.exists()) return false

        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val mimeType = when (file.extension.lowercase()) {
                "mp4", "mkv", "webm", "mov", "m4v", "avi" -> "video/*"
                "mp3", "m4a", "aac", "wav", "opus", "flac", "ogg" -> "audio/*"
                // Without these, photos resolved to */* and the system offered a
                // file-handler chooser instead of opening the gallery viewer.
                "jpg", "jpeg", "png", "webp", "gif", "heic", "bmp" -> "image/*"
                else -> "*/*"
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e("Courier", "Failed to open file: $filePath", e)
            false
        }
    }

    override fun openFolder(folderPath: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path), "*/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e("Courier", "Failed to open folder: $folderPath", e)
            false
        }
    }

    override fun deleteFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            val deleted = if (file.exists()) file.delete() else true

            // Trigger MediaScanner so the deleted media is removed from Gallery / Photos app
            try {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(filePath),
                    null,
                    null
                )
            } catch (scanErr: Exception) {
                Log.w("Courier", "MediaScanner deletion notification error", scanErr)
            }

            deleted
        } catch (e: Exception) {
            Log.e("Courier", "Failed to delete file: $filePath", e)
            false
        }
    }

    override suspend fun chooseDirectory(): String? {
        // Android uses the state-driven DownloadLocationPickerDialog
        return null
    }

    override fun getStandardMediaRoots(): List<String> {
        val roots = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        )
        return roots.map { it.absolutePath }
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
                return@withContext Result.failure(Exception("Write verification failed at: $path"))
            }
            testFile.delete()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("Courier", "Probe failed for $path", e)
            Result.failure(Exception("Location is not writable: ${e.message}"))
        }
    }

    override fun getDefaultDownloadDirectory(): String {
        val courierDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Courier"
        )
        if (!courierDir.exists()) {
            courierDir.mkdirs()
        }
        return courierDir.absolutePath
    }

    override fun getBuildTimestamp(): String? {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            formatter.format(java.util.Date(info.lastUpdateTime))
        } catch (e: Exception) {
            Log.w("Courier", "Could not read build timestamp", e)
            null
        }
    }

    override fun onDownloadStarted(title: String) {
        try {
            val intent = Intent().apply {
                setClassName(context.packageName, "courier.android.DownloadService")
                action = "courier.action.START_DOWNLOAD_SERVICE"
                putExtra("extra_title", title)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.w("Courier", "Failed to start DownloadService", e)
        }
    }

    override fun onDownloadProgress(title: String, progress: Float, speed: String?, eta: String?) {
        try {
            val intent = Intent().apply {
                setClassName(context.packageName, "courier.android.DownloadService")
                action = "courier.action.UPDATE_DOWNLOAD_PROGRESS"
                putExtra("extra_title", title)
                putExtra("extra_progress", progress.toInt())
                putExtra("extra_speed", speed)
                putExtra("extra_eta", eta)
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.w("Courier", "Failed to update DownloadService progress", e)
        }
    }

    override fun onDownloadStopped() {
        try {
            val intent = Intent().apply {
                setClassName(context.packageName, "courier.android.DownloadService")
                action = "courier.action.STOP_DOWNLOAD_SERVICE"
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.w("Courier", "Failed to stop DownloadService", e)
        }
    }

    override fun getAppStorageDirectory(): String {
        return context.filesDir.absolutePath
    }

    override fun isAndroid(): Boolean = true
}

private val androidPlatformActionsInstance: PlatformActions by lazy { PlatformActionsAndroid() }
actual fun getPlatformActions(): PlatformActions = androidPlatformActionsInstance
