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

    fun provideHomeViewModel(): HomeViewModel = HomeViewModel(
        downloadManager = downloadManager,
        engine = downloadEngine
    )

    fun provideSettingsViewModel(): SettingsViewModel = SettingsViewModel(
        settingsRepository = settingsRepository,
        binaryManager = binaryManager
    )
}
