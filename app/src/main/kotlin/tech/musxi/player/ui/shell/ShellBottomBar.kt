package tech.musxi.player.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.musxi.player.ui.components.OpticallyCentredText
import tech.musxi.player.ui.theme.chromeBarColor
import tech.musxi.player.ui.theme.nowPlayingAccent

/**
 * The three top-level destinations. Each carries a filled and an outlined icon: selected tabs fill
 * in, which is a shape-level signal on top of colour. Colour alone was too weak — unselected and
 * selected used to differ only in tint.
 */
enum class ShellTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    LIBRARY("曲库", Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic),
    SEARCH("搜索", Icons.Filled.Search, Icons.Outlined.Search),
    ME("我的", Icons.Filled.Person, Icons.Outlined.Person),
}

/** How the selected tab marks itself, besides colour. */
enum class SelectionMark { NONE, DOT, STAIRS, BAR }

/** How the bar sits against the mini player above it. */
enum class BarShape {
    /** Full width, same as the card above: the two read as one stacked unit. */
    FULL_WIDTH,

    /** Content-hugging pill, floating and centred, the way the reference app does it. */
    PILL,

    /** Pill language, but inset only a little so it stays close to the card's width. */
    WIDE_PILL,
}

/**
 * Tab bar. Steps one shade darker than the mini player so the two chrome layers stay distinct
 * without a shadow.
 *
 * Height is fixed rather than content-wrapped: an earlier `heightIn(min = ...)` let the parent
 * shrink the bar until the icons and labels disappeared entirely.
 */
@Composable
fun ShellBottomBar(
    selected: ShellTab,
    onSelect: (ShellTab) -> Unit,
    modifier: Modifier = Modifier,
    shape: BarShape = BarShape.FULL_WIDTH,
    /** True when a dock already draws the container. */
    embedded: Boolean = false,
    mark: SelectionMark = SelectionMark.NONE,
) {
    val cs = MaterialTheme.colorScheme
    val pill = shape != BarShape.FULL_WIDTH
    val widthModifier = when (shape) {
        BarShape.FULL_WIDTH -> Modifier.fillMaxWidth()
        // Padding then clip: the visual pill is inset, the layout still fills the row.
        BarShape.WIDE_PILL -> Modifier.fillMaxWidth().padding(horizontal = 24.dp)
        BarShape.PILL -> Modifier.widthIn(max = 260.dp)
    }
    Row(
        modifier
            .then(if (embedded) Modifier.fillMaxWidth() else widthModifier)
            .height(HEIGHT)
            .then(
                if (embedded) Modifier else Modifier
                    .clip(if (pill) CircleShape else MaterialTheme.shapes.extraLarge)
                    .background(
                        // A floating pill passes over content, so it needs a touch of translucency
                        // to read as chrome rather than as another card.
                        if (pill) cs.chromeBarColor().copy(alpha = 0.95f) else cs.chromeBarColor(),
                    ),
            )
            .padding(horizontal = if (pill) 8.dp else 6.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (pill) Arrangement.SpaceEvenly else Arrangement.SpaceEvenly,
    ) {
        ShellTab.entries.forEach { tab ->
            TabItem(
                tab = tab, selected = tab == selected, compact = pill, mark = mark,
                onSelect = { onSelect(tab) },
            )
        }
    }
}

/**
 * 64dp, not 68dp. The mini player above grew to 72dp, and chrome should get *lighter* toward the
 * bottom of the screen — the earlier 64/68 pairing inverted that and made the card look cramped
 * against a bar that felt half empty.
 */
private val HEIGHT = 64.dp

@Composable
private fun TabItem(
    tab: ShellTab,
    selected: Boolean,
    compact: Boolean,
    mark: SelectionMark,
    onSelect: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // Selected tabs take the now-playing accent, so the bar changes colour with the song.
    val tint = if (selected) nowPlayingAccent() else cs.onSurfaceVariant
    Column(
        Modifier.selectable(selected = selected, onClick = onSelect, role = Role.Tab)
            .padding(horizontal = if (compact) 10.dp else 18.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            if (selected) tab.selectedIcon else tab.unselectedIcon,
            null, tint = tint, modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(3.dp))
        // labelMedium + Medium instead of labelSmall + Normal: at 11sp Normal the three labels read
        // as a nearly empty strip, which is what made the bar feel hollow.
        OpticallyCentredText(
            tab.label,
            MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = tint,
        )
    }
}
