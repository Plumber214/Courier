package courier.share

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A link handed to Courier from outside — currently Android's share sheet.
 *
 * Shares used to be delivered by writing the link to the system clipboard and
 * relying on `HomeScreen`'s one-shot `LaunchedEffect(Unit)` to notice it. That
 * effect runs once at composition and does not re-fire on `onNewIntent`, and
 * `MainActivity` is `singleTask` — so every share into an already-running
 * Courier did nothing at all, having first overwritten whatever the user had
 * copied.
 *
 * A share is an explicit instruction, so it goes through explicit state rather
 * than a side channel the app also reads for unrelated reasons.
 *
 * [pending] holds at most one link. It is consumed once — a share should open
 * the picker when it arrives, not again on the next recomposition.
 */
object IncomingLinks {
    private val _pending = MutableStateFlow<String?>(null)

    /** The link waiting to be handled, if any. */
    val pending: StateFlow<String?> = _pending.asStateFlow()

    /** Records a link received from outside the app. */
    fun offer(url: String) {
        val trimmed = url.trim()
        if (trimmed.isNotEmpty()) {
            _pending.value = trimmed
        }
    }

    /** Takes the pending link, leaving none behind. Returns null if there was none. */
    fun consume(): String? {
        val current = _pending.value ?: return null
        _pending.value = null
        return current
    }

    /** Discards any pending link. For tests, and for a share the user cancels. */
    fun clear() {
        _pending.value = null
    }
}
