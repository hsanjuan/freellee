package link.hector.freellee.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import link.hector.freellee.ui.theme.CardBackground
import link.hector.freellee.ui.theme.StatusError
import link.hector.freellee.ui.theme.TextPrimary
import link.hector.freellee.ui.theme.TextSecondary
import link.hector.freellee.ui.theme.TextTertiary

/**
 * Card row shared by the Counters and Activities tabs.
 *
 * The leading circle shows [leadingEmoji] once a meaning/type has been assigned, and falls back
 * to a muted [leadingIcon] while the row still needs the user's attention. [trailing] carries the
 * primary value (a counter value or a duration); while [isPendingDelete] it is replaced by an
 * undo hint.
 */
@Composable
fun RecordCard(
    title: String,
    subtitle: String,
    leadingIcon: ImageVector,
    leadingEmoji: String?,
    trailing: String,
    isPendingDelete: Boolean,
    onClick: () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val assigned = leadingEmoji != null
    val indicatorColor = if (assigned) accent else TextTertiary

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPendingDelete) StatusError.copy(alpha = 0.22f) else CardBackground,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(indicatorColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                if (leadingEmoji != null) {
                    Text(leadingEmoji, style = MaterialTheme.typography.titleLarge)
                } else {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = indicatorColor,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (assigned) TextPrimary else TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(12.dp))

            if (isPendingDelete) {
                Text(
                    text = "Tap to undo",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                )
            } else {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.titleMedium,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}
