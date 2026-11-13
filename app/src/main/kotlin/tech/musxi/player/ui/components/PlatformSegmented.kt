package tech.musxi.player.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tech.musxi.core.account.Platform
import tech.musxi.player.ui.theme.displayName

/**
 * Platform picker shaped like an iOS segmented control. The track, thumb and its slide live in
 * [SegmentedTabs]; this only names the segments after the platforms. The selection tick is played
 * there too, so callers add no haptic of their own.
 */
@Composable
fun PlatformSegmented(
    platforms: List<Platform>,
    selected: Platform,
    onSelect: (Platform) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (platforms.isEmpty()) return
    SegmentedTabs(
        labels = platforms.map { it.displayName },
        // -1 for a platform not in the list: the thumb rests on the first segment, none is selected.
        selectedIndex = platforms.indexOf(selected),
        onSelect = { onSelect(platforms[it]) },
        modifier = modifier,
        enabled = enabled,
    )
}
