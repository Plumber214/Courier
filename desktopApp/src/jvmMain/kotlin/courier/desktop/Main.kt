package courier.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import courier.ui.App

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Courier",
        state = rememberWindowState(width = 720.dp, height = 820.dp)
    ) {
        App()
    }
}
