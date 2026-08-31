package courier.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import courier.data.DownloadRepository
import courier.data.SettingsRepository
import courier.engine.createBinaryManager
import courier.engine.createDownloadEngine
import courier.manager.DownloadManager
import courier.ui.screens.HomeScreen
import courier.ui.screens.SettingsScreen
import courier.ui.theme.BgDark
import courier.ui.theme.CourierTheme
import courier.viewmodel.HomeViewModel
import courier.viewmodel.SettingsViewModel

enum class Screen {
    HOME,
    SETTINGS
}

@Composable
fun App() {
    val settingsRepository = remember { SettingsRepository() }
    val downloadRepository = remember { DownloadRepository() }
    val binaryManager = remember { createBinaryManager() }
    val downloadEngine = remember { createDownloadEngine() }

    val downloadManager = remember {
        DownloadManager(
            engine = downloadEngine,
            repository = downloadRepository,
            settingsRepository = settingsRepository,
            binaryManager = binaryManager
        )
    }

    val homeViewModel = remember {
        HomeViewModel(
            downloadManager = downloadManager,
            engine = downloadEngine
        )
    }

    val settingsViewModel = remember {
        SettingsViewModel(
            settingsRepository = settingsRepository,
            binaryManager = binaryManager
        )
    }

    var currentScreen by remember { mutableStateOf(Screen.HOME) }

    CourierTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = androidx.compose.ui.graphics.Color.Transparent
        ) {
            Crossfade(targetState = currentScreen, label = "ScreenTransition") { screen ->
                when (screen) {
                    Screen.HOME -> {
                        HomeScreen(
                            homeViewModel = homeViewModel,
                            downloadManager = downloadManager,
                            onOpenSettings = { currentScreen = Screen.SETTINGS }
                        )
                    }
                    Screen.SETTINGS -> {
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            onBackClick = { currentScreen = Screen.HOME }
                        )
                    }
                }
            }
        }
    }
}
