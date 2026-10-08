package link.hector.freellee.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import link.hector.freellee.ui.theme.AccentGreen

/**
 * The app's standard action button: an outlined button whose label uses the green accent.
 *
 * The app's [Typography] bakes a colour into several text styles.
 * Because `Text` resolves its colour as `explicit colour -> LocalTextStyle.colour ->
 * LocalContentColor`, that baked colour would override the button's own content colour. To keep
 * the accent colour we clear the inherited style colour and provide the accent directly.
 */
@Composable
fun FreelleeOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides AccentGreen,
            LocalTextStyle provides LocalTextStyle.current.copy(color = Color.Unspecified),
        ) {
            content()
        }
    }
}
