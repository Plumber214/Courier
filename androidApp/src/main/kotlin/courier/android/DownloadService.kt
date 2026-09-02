package courier.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class DownloadService : Service {

    constructor() : super()

    companion object {
        const val CHANNEL_ID = "courier_downloads_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "courier.action.START_DOWNLOAD_SERVICE"
        const val ACTION_UPDATE = "courier.action.UPDATE_DOWNLOAD_PROGRESS"
        const val ACTION_STOP = "courier.action.STOP_DOWNLOAD_SERVICE"
        const val ACTION_CANCEL_ALL = "courier.action.CANCEL_ALL_DOWNLOADS"
        const val ACTION_UPDATE_DEVICE_LINK = "courier.action.UPDATE_DEVICE_LINK"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_ETA = "extra_eta"

        const val EXTRA_PAIRED_COUNT = "extra_paired_count"
        const val EXTRA_CONNECTED_COUNT = "extra_connected_count"
        const val EXTRA_PRIMARY_NAME = "extra_primary_name"
        const val EXTRA_PRIMARY_STATUS = "extra_primary_status"

        fun start(context: Context, initialTitle: String? = null) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                if (initialTitle != null) {
                    putExtra(EXTRA_TITLE, initialTitle)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateProgress(
            context: Context,
            title: String,
            progressPercent: Float,
            speedFormatted: String?,
            etaFormatted: String?
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_PROGRESS, progressPercent.toInt())
                putExtra(EXTRA_SPEED, speedFormatted)
                putExtra(EXTRA_ETA, etaFormatted)
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }
    }

    private var notificationManager: NotificationManager? = null

    // Download state
    private var activeDownloadTitle: String? = null
    private var activeDownloadProgress: Int = 0
    private var activeDownloadSpeed: String? = null
    private var activeDownloadEta: String? = null

    // Device link state (F4)
    private var pairedCount: Int = 0
    private var connectedCount: Int = 0
    private var primaryDeviceName: String? = null
    private var primaryDeviceStatus: String? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                activeDownloadTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Downloading media..."
                activeDownloadProgress = 0
                renderForegroundNotification()
            }
            ACTION_UPDATE -> {
                activeDownloadTitle = intent.getStringExtra(EXTRA_TITLE) ?: activeDownloadTitle ?: "Downloading media..."
                activeDownloadProgress = intent.getIntExtra(EXTRA_PROGRESS, activeDownloadProgress)
                activeDownloadSpeed = intent.getStringExtra(EXTRA_SPEED)
                activeDownloadEta = intent.getStringExtra(EXTRA_ETA)
                renderForegroundNotification()
            }
            ACTION_STOP -> {
                activeDownloadTitle = null
                activeDownloadProgress = 0
                activeDownloadSpeed = null
                activeDownloadEta = null
                renderForegroundNotification()
            }
            ACTION_UPDATE_DEVICE_LINK -> {
                pairedCount = intent.getIntExtra(EXTRA_PAIRED_COUNT, 0)
                connectedCount = intent.getIntExtra(EXTRA_CONNECTED_COUNT, 0)
                primaryDeviceName = intent.getStringExtra(EXTRA_PRIMARY_NAME)
                primaryDeviceStatus = intent.getStringExtra(EXTRA_PRIMARY_STATUS)
                renderForegroundNotification()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun renderForegroundNotification() {
        val hasDownload = activeDownloadTitle != null
        val hasPaired = pairedCount > 0

        if (!hasDownload && !hasPaired) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val notification = if (hasDownload) {
            buildDownloadNotification(
                title = activeDownloadTitle ?: "Downloading media...",
                progress = activeDownloadProgress,
                speed = activeDownloadSpeed,
                eta = activeDownloadEta
            )
        } else {
            buildDeviceLinkNotification(
                pairedCount = pairedCount,
                connectedCount = connectedCount,
                primaryName = primaryDeviceName
            )
        }

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (hasDownload && hasPaired) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else if (hasDownload) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
        } catch (e: Exception) {
            // Gracefully ignore notification start rejection (e.g. POST_NOTIFICATIONS refused §2.Stage 3.4)
            println("Foreground service notification notice: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Courier Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live download progress and Device Link connectivity"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildDownloadNotification(
        title: String,
        progress: Int,
        speed: String?,
        eta: String?
    ): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val detailText = when {
            speed != null && eta != null -> "$speed • ETA $eta"
            speed != null -> speed
            eta != null -> "ETA $eta"
            else -> "Downloading..."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(detailText)
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildDeviceLinkNotification(
        pairedCount: Int,
        connectedCount: Int,
        primaryName: String?
    ): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_INITIAL_TAB", "DEVICES")
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "Courier Device Link"
        val text = when {
            connectedCount > 0 && !primaryName.isNullOrBlank() -> {
                if (pairedCount == 1) "$primaryName Connected" else "$primaryName + ${pairedCount - 1} connected"
            }
            connectedCount > 0 -> "$connectedCount device(s) connected"
            !primaryName.isNullOrBlank() -> "$primaryName (searching LAN)"
            else -> "$pairedCount paired device(s) (searching LAN)"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}