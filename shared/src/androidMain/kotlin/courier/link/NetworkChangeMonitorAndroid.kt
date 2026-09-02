package courier.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import courier.platform.AppContextHolder

/**
 * Wraps [ConnectivityManager]'s default-network callback.
 *
 * `ACCESS_NETWORK_STATE` is already declared in the manifest, so this needs no
 * new permission.
 */
class NetworkChangeMonitorAndroid : NetworkChangeMonitor {

    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun start(onChange: () -> Unit) {
        stop()
        if (!AppContextHolder.isInitialized) return

        val manager = AppContextHolder.appContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange()
            override fun onLost(network: Network) = onChange()
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                // Fires when a network becomes validated, which is the moment
                // it is actually usable — onAvailable can precede that.
                onChange()
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                manager.registerDefaultNetworkCallback(cb)
            } else {
                val request = android.net.NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                manager.registerNetworkCallback(request, cb)
            }
            callback = cb
        } catch (e: Exception) {
            Log.w("Courier", "Could not register network callback", e)
        }
    }

    override fun stop() {
        val cb = callback ?: return
        callback = null
        try {
            val manager = AppContextHolder.appContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            manager?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }
}

actual fun createNetworkChangeMonitor(): NetworkChangeMonitor = NetworkChangeMonitorAndroid()
