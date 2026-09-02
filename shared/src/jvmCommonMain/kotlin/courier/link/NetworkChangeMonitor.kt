package courier.link

/**
 * Notifies when the device's network situation changes — joining Wi-Fi, an
 * interface coming up, a VPN connecting.
 *
 * Backoff alone makes reconnection feel broken: after a Wi-Fi bounce the link
 * sits idle for whatever the current delay happens to be. The event-driven kick
 * is what makes recovery feel instant, so this exists to drive
 * [DeviceLinkManager.kickNetwork].
 */
interface NetworkChangeMonitor {
    /** Begins watching. [onChange] may be invoked from any thread, and may fire spuriously. */
    fun start(onChange: () -> Unit)
    fun stop()
}

expect fun createNetworkChangeMonitor(): NetworkChangeMonitor
