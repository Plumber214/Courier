package courier.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import courier.di.AppModule
import courier.platform.getPlatformActions
import courier.ui.screens.DevicesScreen
import courier.ui.screens.HomeScreen
import courier.ui.screens.SettingsScreen
import courier.ui.theme.CourierTheme
import courier.ui.theme.GlassBackground
import courier.ui.theme.PrimaryContainer
import courier.ui.theme.TextMuted
import courier.ui.theme.TextPrimary

enum class AppTab(val title: String, val icon: ImageVector) {
    DOWNLOADS("Downloads", Icons.Default.Download),
    DEVICES("Devices", Icons.Default.Devices),
    SETTINGS("Settings", Icons.Default.Settings)
}

@Composable
fun App() {
    val downloadManager = AppModule.downloadManager
    val homeViewModel = remember { AppModule.provideHomeViewModel() }
    val settingsViewModel = remember { AppModule.provideSettingsViewModel() }
    val devicesViewModel = remember { AppModule.provideDevicesViewModel() }

    var selectedTab by rememberSaveable { mutableStateOf(AppTab.DOWNLOADS) }
    val isAndroid = remember { getPlatformActions().isAndroid() }

    CourierTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent
        ) {
            if (isAndroid) {
                // Mobile layout: Bottom NavigationBar
                Scaffold(
                    containerColor = Color.Transparent,
                    bottomBar = {
                        NavigationBar(
                            containerColor = GlassBackground,
                            contentColor = TextPrimary,
                            tonalElevation = 0.dp
                        ) {
                            for (tab in AppTab.values()) {
                                val isSelected = selectedTab == tab
                                NavigationBarItem(
                                    selected = isSelected,
                                    onClick = { selectedTab = tab },
                                    icon = {
                                        Icon(
                                            imageVector = tab.icon,
                                            contentDescription = tab.title,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = tab.title,
                                            fontSize = 11.sp,
                                            color = if (isSelected) TextPrimary else TextMuted
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = TextPrimary,
                                        selectedTextColor = TextPrimary,
                                        indicatorColor = PrimaryContainer,
                                        unselectedIconColor = TextMuted,
                                        unselectedTextColor = TextMuted
                                    )
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        TabContent(
                            selectedTab = selectedTab,
                            homeViewModel = homeViewModel,
                            downloadManager = downloadManager,
                            settingsViewModel = settingsViewModel,
                            devicesViewModel = devicesViewModel,
                            onSwitchTab = { selectedTab = it }
                        )
                    }
                }
            } else {
                // Desktop layout: Left NavigationRail
                Row(modifier = Modifier.fillMaxSize()) {
                    NavigationRail(
                        containerColor = GlassBackground,
                        contentColor = TextPrimary,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        for (tab in AppTab.values()) {
                            val isSelected = selectedTab == tab
                            NavigationRailItem(
                                selected = isSelected,
                                onClick = { selectedTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = tab.title,
                                        modifier = Modifier.size(22.dp)
                                    )
                                },
                                label = {
                                    Text(
                                        text = tab.title,
                                        fontSize = 11.sp,
                                        color = if (isSelected) TextPrimary else TextMuted
                                    )
                                },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = TextPrimary,
                                    selectedTextColor = TextPrimary,
                                    indicatorColor = PrimaryContainer,
                                    unselectedIconColor = TextMuted,
                                    unselectedTextColor = TextMuted
                                )
                            )
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        TabContent(
                            selectedTab = selectedTab,
                            homeViewModel = homeViewModel,
                            downloadManager = downloadManager,
                            settingsViewModel = settingsViewModel,
                            devicesViewModel = devicesViewModel,
                            onSwitchTab = { selectedTab = it }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabContent(
    selectedTab: AppTab,
    homeViewModel: courier.viewmodel.HomeViewModel,
    downloadManager: courier.manager.DownloadManager,
    settingsViewModel: courier.viewmodel.SettingsViewModel,
    devicesViewModel: courier.viewmodel.DevicesViewModel,
    onSwitchTab: (AppTab) -> Unit
) {
    // Preserve composition state per tab so scrolling position survives tab switches (§2.Stage D.3)
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = selectedTab == AppTab.DOWNLOADS,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            HomeScreen(
                homeViewModel = homeViewModel,
                downloadManager = downloadManager,
                onOpenSettings = { onSwitchTab(AppTab.SETTINGS) }
            )
        }

        AnimatedVisibility(
            visible = selectedTab == AppTab.DEVICES,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            DevicesScreen(viewModel = devicesViewModel)
        }

        AnimatedVisibility(
            visible = selectedTab == AppTab.SETTINGS,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            SettingsScreen(
                viewModel = settingsViewModel,
                onBackClick = { onSwitchTab(AppTab.DOWNLOADS) }
            )
        }
    }
}
