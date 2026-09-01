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
import courier.di.AppModule
import courier.ui.screens.HomeScreen
import courier.ui.screens.SettingsScreen
import courier.ui.theme.CourierTheme

enum class Screen {
    HOME,
    SETTINGS
}

@Composable
fun App() {
    val downloadManager = AppModule.downloadManager
    val homeViewModel = remember { AppModule.provideHomeViewModel() }
    val settingsViewModel = remember { AppModule.provideSettingsViewModel() }

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
