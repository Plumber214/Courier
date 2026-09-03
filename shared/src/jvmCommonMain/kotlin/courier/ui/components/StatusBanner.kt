package courier.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import courier.ui.theme.AccentCyan
import courier.ui.theme.PrimaryContainer
import courier.ui.theme.PrimaryIndigo
import courier.ui.theme.TextMuted
import courier.ui.theme.TextPrimary

/**
 * A transient, dismissible confirmation strip.
 *
 * Extracted from the Devices tab, where it already reported clipboard sends, so
 * that "sent to that device" and "queued because that device is offline" can be
 * told the same way on the Downloads tab — previously sending a download to a
 * paired device closed the picker and said nothing at all, whether it had gone
 * out or merely been queued.
 */
@Composable
fun StatusBanner(
    message: String?,
    icon: ImageVector,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = AccentCyan,
    borderColor: Color = PrimaryIndigo,
    containerColor: Color = PrimaryContainer.copy(alpha = 0.9f)
) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .background(containerColor, RoundedCornerShape(10.dp))
                .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = message ?: "",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
