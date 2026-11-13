package tech.musxi.player.ui.me

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import tech.musxi.player.ui.components.CardRow
import tech.musxi.player.ui.components.RowDivider
import tech.musxi.player.ui.components.SectionGroup
import tech.musxi.player.ui.components.SwitchRow
import tech.musxi.player.ui.components.ValueRow

/*
 * The "我的" page's grouped list is built from the shared group and row components in
 * `ui/components/` (Sections.kt, Rows.kt), the same ones the library home uses. The names below are
 * the page's vocabulary and only forward. Settings with a few choices use [MePickerRow] instead of a
 * sub-page.
 */

/**
 * One group of a grouped list: a grey heading above, a rounded card of rows, an optional grey footer
 * below. A null [title] leaves the group without a heading.
 */
@Composable
fun MeGroup(
    title: String?,
    modifier: Modifier = Modifier,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) = SectionGroup(title = title, modifier = modifier, footer = footer, content = content)

/** Hairline between two rows of one card. */
@Composable
fun MeDivider() = RowDivider()

/** The frame every row shares; see `CardRow`. */
@Composable
fun MeRowFrame(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    role: Role = Role.Button,
    content: @Composable RowScope.() -> Unit,
) = CardRow(onClick = onClick, modifier = modifier, role = role, content = content)

/** Title (with an optional line of explanation) and a switch. The row is the tap target. */
@Composable
fun MeSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) = SwitchRow(title, checked, onCheckedChange, subtitle)

/** Title on the left, current value on the right; with a chevron it opens a page. */
@Composable
fun MeValueRow(
    title: String,
    value: String,
    onClick: (() -> Unit)?,
    chevron: Boolean = onClick != null,
    leading: (@Composable () -> Unit)? = null,
) = ValueRow(title, value, onClick, chevron, leading)
