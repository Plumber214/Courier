package courier.ui.layout

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * How much horizontal room the app actually has.
 *
 * Until v1.7.0 the only adaptive decision in the tree was `isAndroid()`
 * choosing a bottom navigation bar over a rail. That is a question about the
 * operating system, not about the window: a desktop window dragged narrow got
 * the wide layout anyway, and a tablet in landscape got the phone one. Every
 * screen below that then hard-coded a single column and a 22 dp gutter.
 *
 * This is resolved once, at the root, and read through [LocalWidthClass].
 */
enum class WidthClass {
    /** Phone portrait, or a deliberately narrow window. One column, dense. */
    COMPACT,

    /** Tablet, or a normal desktop window. One column, comfortable. */
    MEDIUM,

    /** A wide desktop window. Two columns, with the content capped. */
    EXPANDED
}

/** Below this, a 100 dp thumbnail plus three 40 dp buttons does not fit a row. */
const val MEDIUM_MIN_DP = 600

/**
 * Below this, two download columns are each too narrow for the wide row —
 * higher than Material's 840 dp expanded breakpoint, because what this class
 * gates is the two-column list, not a generic notion of "large".
 */
const val EXPANDED_MIN_DP = 1000

/** The widest the content column is allowed to grow before it just centres. */
const val CONTENT_MAX_WIDTH_DP = 1180

fun widthClassFor(widthDp: Int): WidthClass = when {
    widthDp < MEDIUM_MIN_DP -> WidthClass.COMPACT
    widthDp < EXPANDED_MIN_DP -> WidthClass.MEDIUM
    else -> WidthClass.EXPANDED
}

/**
 * Defaults to [WidthClass.COMPACT]: if a subtree is ever composed outside the
 * provider, the dense layout is the one that survives at any size.
 */
val LocalWidthClass = staticCompositionLocalOf { WidthClass.COMPACT }

@Composable
fun ProvideWidthClass(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        val widthClass = widthClassFor(maxWidth.value.toInt())
        CompositionLocalProvider(LocalWidthClass provides widthClass) {
            content()
        }
    }
}
