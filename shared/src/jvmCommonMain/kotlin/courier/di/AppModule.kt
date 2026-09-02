package courier.di

import courier.data.DownloadRepository
import courier.data.SettingsRepository
import courier.engine.BinaryManager
import courier.engine.DownloadEngine
import courier.engine.createBinaryManager
import courier.engine.createDownloadEngine
import courier.manager.DownloadManager
import courier.viewmodel.HomeViewModel
import courier.viewmodel.SettingsViewModel

object AppModule {
    val settingsRepository: SettingsRepository by lazy { SettingsRepository() }
    val downloadRepository: DownloadRepository by lazy { DownloadRepository() }
    val binaryManager: BinaryManager by lazy { createBinaryManager() }
    val downloadEngine: DownloadEngine by lazy { createDownloadEngine() }

    val downloadManager: DownloadManager by lazy {
        DownloadManager(
            engine = downloadEngine,
            repository = downloadRepository,
            settingsRepository = settingsRepository,
            binaryManager = binaryManager
        )
    }

    val deviceLinkManager: courier.link.DeviceLinkManager by lazy {
        courier.link.DeviceLinkManager.getInstance().apply {
            start()
        }
    }

    val linkDownloadBridge: courier.link.LinkDownloadBridge by lazy {
        courier.link.LinkDownloadBridge(
            linkManager = deviceLinkManager,
            downloadManager = downloadManager
        ).apply {
            start()
        }
    }

    val clipboardSyncManager: courier.link.ClipboardSyncManager by lazy {
        courier.link.ClipboardSyncManager(
            linkManager = deviceLinkManager
        ).apply {
            start()
        }
    }

    val appUpdateManager: courier.update.AppUpdateManager by lazy {
        courier.update.AppUpdateManager(
            settingsRepository = settingsRepository
        ).apply {
            if (!courier.platform.getPlatformActions().isAndroid()) {
                checkForUpdates(manual = false)
            }
        }
    }

    fun provideHomeViewModel(): HomeViewModel = HomeViewModel(
        downloadManager = downloadManager,
        engine = downloadEngine
    )

    fun provideSettingsViewModel(): SettingsViewModel = SettingsViewModel(
        settingsRepository = settingsRepository,
        binaryManager = binaryManager
    )

    fun provideDevicesViewModel(): courier.viewmodel.DevicesViewModel {
        linkDownloadBridge // Ensure bridge is active
        clipboardSyncManager // Ensure clipboard sync is active
        return courier.viewmodel.DevicesViewModel(linkManager = deviceLinkManager)
    }
}
