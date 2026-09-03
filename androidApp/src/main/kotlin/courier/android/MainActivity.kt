package courier.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import courier.engine.UrlValidator
import courier.platform.AppContextHolder
import courier.share.IncomingLinks
import courier.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppContextHolder.appContext = applicationContext

        requestNotificationPermission()
        handleIncomingIntent(intent)

        val initialTab = if (intent?.getStringExtra("EXTRA_INITIAL_TAB") == "DEVICES") {
            courier.ui.AppTab.DEVICES
        } else {
            courier.ui.AppTab.DOWNLOADS
        }

        setContent {
            App(initialTab = initialTab)
        }
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                val extracted = UrlValidator.extractUrl(sharedText)
                if (!extracted.isNullOrBlank()) {
                    // Explicit state, not the clipboard. The old route wrote the
                    // link to the clipboard and depended on a one-shot effect in
                    // HomeScreen to notice it — which does not re-run on
                    // onNewIntent, so sharing into a running Courier silently
                    // did nothing except clobber what the user had copied.
                    IncomingLinks.offer(extracted)
                }
            }
        }
    }
}
