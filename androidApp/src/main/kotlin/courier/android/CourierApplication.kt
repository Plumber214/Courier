package courier.android

import android.app.Application
import courier.engine.DownloadEngineAndroid
import courier.platform.AppContextHolder
import kotlin.concurrent.thread

class CourierApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.appContext = this
        thread(start = true, isDaemon = true, name = "Engine-Init") {
            DownloadEngineAndroid.ensureInitialized()
        }
    }
}
