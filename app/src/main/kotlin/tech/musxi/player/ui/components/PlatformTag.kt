package tech.musxi.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import tech.musxi.core.account.Platform
import tech.musxi.player.ui.theme.displayName
import tech.musxi.player.ui.theme.identityColor

/**
 * The one way this app says "which platform".
 *
 * A 6dp dot immediately before the platform name, and nothing else: no coloured initials badge, no
 * full-width badge, no bare wordmark. Four different spellings used to coexist across the account,
 * library, player and source panels, which broke the rule that platform colour is only ever a small
 * mark.
 *
 * `showName = false` is for rows whose own caption already ends with the platform name.
 */
@Composable
fun PlatformTag(
    platform: Platform,
    dark: Boolean,
    modifier: Modifier = Modifier,
    showName: Boolean = true,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(6.dp).clip(CircleShape)
                .background(platform.identityColor(dark)),
        )
        if (showName) {
            Text(
                platform.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
