package courier.platform

import android.content.Context

object AppContextHolder {
    lateinit var appContext: Context

    val isInitialized: Boolean
        get() = ::appContext.isInitialized
}
