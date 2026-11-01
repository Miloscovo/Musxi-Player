package tech.musxi.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import tech.musxi.player.ui.theme.LocalMusxi
import tech.musxi.player.ui.theme.MusxiShapes
import tech.musxi.player.ui.theme.Space

/*
 * One vocabulary for grouped content, used by every page.
 *
 * Rule: a set of modules that belong together sits in a card (the library's home modules, each group
 * on the 我的 page). A long list does not (playlists, songs, search results, the queue): it lies on
 * the page colour. Both kinds of group carry the same heading ([SectionTitle]), the same rows
 * ([RowSpec]) and the same divider ([RowDivider], which starts at the text, never under a cover).
 *
 * Corner tiers: card [MusxiShapes.large]; cover 56dp and up `medium`; cover 48dp and under `small`.
 * Platform marks: a platform that stands on its own gets a `PlatformTag` (dot and name); a platform
 * inside a line of grey metadata is written as plain grey text ("网易云 · 会员"), without a dot.
 */

/** The card colour: the lightest surface in light mode, one step up from the page in dark. */
@Composable
fun sectionCardColor(): Color {
    val cs = MaterialTheme.colorScheme
    return if (LocalMusxi.current.dark) cs.surfaceContainerHigh else cs.surfaceContainerLowest
}

/**
 * Heading of a group, flush with the page margin, above the card or the list it names. It is grey
 * and one step quieter than the rows below it, so the rows stay the loudest text of the group.
 * [trailing] is for a text action at the far end ("编辑"); it sits on the heading's line.
 */
@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(bottom = RowSpec.headingGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        trailing?.invoke(this)
    }
}

/** Grey note under a card, flush with the page margin like the heading above it. */
@Composable
fun SectionFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(top = RowSpec.headingGap),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The rounded card. Rows inside use [CardRow], which bleeds its press tile out by the card's inner
 * padding so it reaches the card edge. A [padded] card has no side padding at all: its content
 * supplies the inset itself, so a horizontal strip can scroll to the card edges.
 *
 * The light card is almost the page colour, so it gets a hairline edge; in dark mode the card is a
 * step above the page and needs none.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    padded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val dark = LocalMusxi.current.dark
    Column(
        modifier.fillMaxWidth()
            .clip(MusxiShapes.large)
            .background(sectionCardColor())
            .then(
                if (dark) Modifier
                else Modifier.border(RowSpec.hairline, cs.outlineVariant.copy(alpha = 0.6f), MusxiShapes.large),
            )
            .then(if (padded) Modifier.padding(horizontal = Space.inner) else Modifier),
        content = content,
    )
}

/**
 * Heading, card and footer as one unit. A null [title] leaves the group without a heading (a picker
 * page whose page title already names it); a null [footer] leaves out the note.
 */
@Composable
fun SectionGroup(
    title: String?,
    modifier: Modifier = Modifier,
    footer: String? = null,
    padded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) SectionTitle(title)
        SectionCard(padded = padded, content = content)
        if (footer != null) SectionFooter(footer)
    }
}
