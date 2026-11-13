package tech.musxi.player.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.musxi.core.settings.LyricsFontSize
import tech.musxi.player.ui.components.AnchoredMenu
import tech.musxi.player.ui.components.MenuAlign
import tech.musxi.player.ui.components.MenuItem
import tech.musxi.player.ui.player.lyricsTextSizes
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.Haptic
import tech.musxi.player.ui.theme.LocalMusxi
import tech.musxi.player.ui.theme.rememberHaptics
import tech.musxi.player.ui.theme.tones

/**
 * The page's one-of-a-few settings. Each opens a small menu beside its row instead of a page of its
 * own: there are only a handful of choices, and the user stays where they were in the list.
 */
internal enum class MePicker { QUALITY, FONT_SIZE, THEME_MODE, ACCENT, LIBRARY_LOCK }

/** Tag of each "歌词" sample in the 字号 menu; the sample is hidden from accessibility, so tests find it by this. */
internal const val MeFontSampleTag = "me-font-size-sample"

/**
 * A value row whose value is picked from a menu anchored to it: the same floating card as the
 * player's "更多" menu. The row keeps the value row's look without the chevron, since nothing is
 * pushed. Picking closes the menu and only a real change is reported, so tapping the value already
 * chosen just closes it.
 */
@Composable
internal fun <T> MePickerRow(
    title: String,
    choices: List<T>,
    selected: T,
    label: (T) -> String,
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
    /** Mark just before the row's value, such as the accent's dot. */
    rowLeading: (@Composable () -> Unit)? = null,
    /** Mark before each choice's label. */
    choiceLeading: (@Composable (T) -> Unit)? = null,
    /** Shown at the end of each choice, ahead of the check (the font size's sample). */
    choiceSample: (@Composable (T) -> Unit)? = null,
) {
    val haptic = rememberHaptics()
    // AnchoredMenu anchors to the bounds of the Box it is in, which is then exactly the row.
    Box {
        MeValueRow(title, label(selected), onClick = onExpand, chevron = false, leading = rowLeading)
        AnchoredMenu(
            expanded = expanded,
            onDismiss = onDismiss,
            align = MenuAlign.END,
            // Drops below the row like a pull-down; a row near the bottom of the window gets it above.
            preferAbove = false,
        ) {
            for (choice in choices) {
                val chosen = choice == selected
                MenuItem(
                    label = label(choice),
                    onClick = {
                        onDismiss()
                        if (!chosen) {
                            haptic(Haptic.TICK)
                            onSelect(choice)
                        }
                    },
                    selected = chosen,
                    leading = choiceLeading?.let { lead -> @Composable { lead(choice) } },
                    trailing = choiceSample?.let { sample ->
                        @Composable { SampleAndCheck(chosen) { sample(choice) } }
                    },
                )
            }
        }
    }
}

/**
 * MenuItem drops its own check when given a trailing slot, so the check is drawn here. Its room is
 * kept on every row, otherwise the chosen row's sample would sit further left than the others.
 */
@Composable
private fun SampleAndCheck(chosen: Boolean, sample: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        sample()
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            if (chosen) {
                Icon(
                    Icons.Filled.Check, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** The accent as it looks in the current brightness, so the dot matches the page it sits on. */
@Composable
internal fun AccentDot(accent: Accent, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(accent.tones(LocalMusxi.current.dark).primary))
}

/**
 * "歌词" in the lyrics page's line style at [size], so the menu shows the difference instead of
 * only naming it. The setting is for the lyrics alone, hence lyrics sizes, not the app's type scale.
 */
@Composable
internal fun LyricsSizeSample(size: LyricsFontSize) {
    val sizes = lyricsTextSizes(size)
    Text(
        "歌词",
        style = MaterialTheme.typography.bodyLarge.copy(
            fontSize = sizes.line, lineHeight = sizes.lineHeight, fontWeight = FontWeight.Medium,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        // The label beside it already says the size; read aloud, the sample would only add noise.
        modifier = Modifier.clearAndSetSemantics { testTag = MeFontSampleTag },
    )
}
