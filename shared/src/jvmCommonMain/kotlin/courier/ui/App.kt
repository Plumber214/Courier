package courier.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import courier.ui.layout.LocalWidthClass
import courier.ui.layout.ProvideWidthClass
import courier.ui.layout.WidthClass
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
fun App(initialTab: AppTab = AppTab.DOWNLOADS) {
    val downloadManager = AppModule.downloadManager
    val homeViewModel = remember { AppModule.provideHomeViewModel() }
    val settingsViewModel = remember { AppModule.provideSettingsViewModel() }
    val devicesViewModel = remember { AppModule.provideDevicesViewModel() }

    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }

    // A shared link has to reach the Downloads tab to be acted on, and the user
    // may well have been on Settings or Devices when they shared. Observed here
    // rather than in HomeScreen because this composable is always present.
    val pendingSharedLink by courier.share.IncomingLinks.pending.collectAsState()
    LaunchedEffect(pendingSharedLink) {
        if (pendingSharedLink != null) {
            selectedTab = AppTab.DOWNLOADS
        }
    }

    CourierTheme {
        // The one place available width is measured. Everything below reads
        // LocalWidthClass rather than asking which operating system it is on.
        ProvideWidthClass(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent
        ) {
            // A narrow window gets the bottom bar whether or not it is a phone,
            // and a tablet in landscape gets the rail.
            if (LocalWidthClass.current == WidthClass.COMPACT) {
                // Narrow layout: Bottom NavigationBar
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
                // Wider layout: Left NavigationRail
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
    Column(modifier = Modifier.fillMaxSize()) {
        courier.ui.components.UpdateReadyBanner()

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (selectedTab) {
                AppTab.DOWNLOADS -> {
                    HomeScreen(
                        homeViewModel = homeViewModel,
                        downloadManager = downloadManager,
                        onOpenSettings = { onSwitchTab(AppTab.SETTINGS) }
                    )
                }
                AppTab.DEVICES -> {
                    DevicesScreen(viewModel = devicesViewModel)
                }
                AppTab.SETTINGS -> {
                    SettingsScreen(
                        viewModel = settingsViewModel,
                        onBackClick = { onSwitchTab(AppTab.DOWNLOADS) }
                    )
                }
            }
        }
    }
}
