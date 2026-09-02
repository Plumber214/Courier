package courier.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.NetworkInterface

/**
 * The JVM offers no notification for interface changes, so this polls.
 *
 * A three-second poll is cheap — enumerating interfaces is a local call — and
 * bounds the delay between plugging in a cable or joining Wi-Fi and the link
 * retrying. The alternative is waiting out the reconnect backoff, which is the
 * behaviour this exists to avoid.
 */
class NetworkChangeMonitorDesktop(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : NetworkChangeMonitor {

    private var pollJob: Job? = null

    override fun start(onChange: () -> Unit) {
        stop()
        pollJob = scope.launch {
            var lastSignature = snapshot()
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                // null means the poll itself failed; hold the previous
                // signature so the next success is not read as a change.
                val current = snapshot() ?: continue
                if (lastSignature != null && current != lastSignature) {
                    onChange()
                }
                lastSignature = current
            }
        }
    }

    override fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * A stable description of every usable interface address. Compared as a
     * whole, so an address appearing or disappearing counts as a change.
     */
    private fun snapshot(): String? = try {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { !it.isLoopback && it.isUp }
            .flatMap { iface -> iface.inetAddresses.asSequence().map { "${iface.name}:${it.hostAddress}" } }
            .sorted()
            .joinToString(",")
    } catch (_: Exception) {
        // Null, not empty: a transient enumeration failure must not be read as
        // "every interface vanished" and then "everything came back".
        null
    }

    companion object {
        private const val POLL_INTERVAL_MS = 3_000L
    }
}

actual fun createNetworkChangeMonitor(): NetworkChangeMonitor = NetworkChangeMonitorDesktop()
