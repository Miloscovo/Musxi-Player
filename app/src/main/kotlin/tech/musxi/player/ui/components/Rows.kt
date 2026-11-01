package tech.musxi.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import tech.musxi.player.ui.theme.Haptic
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.rememberHaptics

/**
 * Frame of one row inside a [SectionCard]: at least [RowSpec.minHeight] tall, [RowSpec.vertical]
 * above and below its content, so a row grows with the font scale instead of clipping. A non-null
 * [onClick] makes the whole row one press tile, as wide as the card.
 */
@Composable
fun CardRow(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    role: Role = Role.Button,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val press = if (onClick != null) {
        Modifier.pressableRow(onClick = onClick, enabled = enabled, role = role, bleed = Space.inner, corner = 0.dp)
    } else Modifier
    Row(
        modifier.fillMaxWidth().then(press)
            .heightIn(min = RowSpec.minHeight).padding(vertical = RowSpec.vertical),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Frame of one row in a list that lies on the page colour (no card). Same height rule as
 * [CardRow]; the press tile is the app's usual rounded one reaching past the page margin.
 */
@Composable
fun PlainRow(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    role: Role = Role.Button,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val press = if (onClick != null) Modifier.pressableRow(onClick = onClick, enabled = enabled, role = role) else Modifier
    Row(
        modifier.fillMaxWidth().then(press)
            .heightIn(min = RowSpec.minHeight).padding(vertical = RowSpec.vertical),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Hairline between two rows. [start] is where the row's text starts, so the line never runs under a
 * cover or an avatar: `RowDivider(start = RowSpec.textStart(RowSpec.cover))` for a 48dp cover.
 */
@Composable
fun RowDivider(modifier: Modifier = Modifier, start: Dp = 0.dp) {
    HorizontalDivider(
        modifier.padding(start = start),
        thickness = RowSpec.hairline,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** Line limit that lets a text take as many lines as it needs. */
const val ALL_LINES = Int.MAX_VALUE

/** From this font scale on, a line that is normally cut with an ellipsis gets a second line instead. */
const val LARGE_FONT_SCALE = 1.3f

@Composable
fun isLargeFont(): Boolean = LocalDensity.current.fontScale >= LARGE_FONT_SCALE

/** Lines for the title and the second line of a list row: one, or two at large font sizes. */
@Composable
fun listLines(): Int = if (isLargeFont()) 2 else 1

/**
 * Title with an optional second line, filling the width a row leaves. The second line sits right
 * under the first at the font's natural line height: no spacer, so the pair stays one block that the
 * row centres on its lead. List rows keep the default, one line each (two from [LARGE_FONT_SCALE]
 * on) and cut with an ellipsis; a line whose loss would hurt (a setting's explanation, a status
 * sentence) is given [ALL_LINES] or a fixed number.
 */
@Composable
fun RowScope.RowText(
    title: AnnotatedString,
    modifier: Modifier = Modifier,
    subtitle: AnnotatedString? = null,
    titleStyle: TextStyle = MaterialTheme.typography.titleSmall,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    subtitleStyle: TextStyle = MaterialTheme.typography.bodySmall,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleLines: Int = listLines(),
    subtitleLines: Int = listLines(),
) {
    Column(modifier.weight(1f)) {
        Text(title, style = titleStyle, color = titleColor, maxLines = titleLines, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) {
            Text(
                subtitle, style = subtitleStyle, color = subtitleColor,
                maxLines = subtitleLines, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun RowScope.RowText(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleStyle: TextStyle = MaterialTheme.typography.titleSmall,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    subtitleStyle: TextStyle = MaterialTheme.typography.bodySmall,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleLines: Int = listLines(),
    subtitleLines: Int = listLines(),
) = RowText(
    AnnotatedString(title), modifier, subtitle?.let { AnnotatedString(it) },
    titleStyle, titleColor, subtitleStyle, subtitleColor, titleLines, subtitleLines,
)

/** Title and a switch. The row is the tap target. An optional second line explains the setting. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    val haptic = rememberHaptics()
    CardRow(
        onClick = {
            haptic(Haptic.TICK)
            onCheckedChange(!checked)
        },
        modifier = Modifier.semantics { stateDescription = if (checked) "已开启" else "已关闭" },
        role = Role.Switch,
    ) {
        RowText(title, subtitle = subtitle, titleLines = ALL_LINES, subtitleLines = ALL_LINES)
        Spacer(Modifier.width(RowSpec.trailGap))
        RowSwitch(checked)
    }
}

/**
 * Title on the left, current value on the right. With a chevron it opens a sub-page; without one it
 * runs an action in place. A null [onClick] leaves the row inert. [leading] sits just before the
 * value, for a small mark such as the accent swatch.
 */
@Composable
fun ValueRow(
    title: String,
    value: String,
    onClick: (() -> Unit)?,
    chevron: Boolean = onClick != null,
    leading: (@Composable () -> Unit)? = null,
) {
    CardRow(onClick) {
        RowText(title, titleLines = ALL_LINES)
        Spacer(Modifier.width(RowSpec.trailGap))
        if (leading != null) {
            leading()
            Spacer(Modifier.width(RowSpec.chevronGap))
        }
        Text(
            value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (chevron) {
            Spacer(Modifier.width(RowSpec.chevronGap))
            RowChevron()
        }
    }
}

/** One choice of a single-choice list: label, and a check on the chosen one. Picking the chosen row does nothing. */
@Composable
fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    val haptic = rememberHaptics()
    CardRow(
        onClick = {
            if (!selected) {
                haptic(Haptic.TICK)
                onSelect()
            }
        },
        modifier = Modifier.semantics { this.selected = selected },
        role = Role.RadioButton,
    ) {
        RowText(label, titleLines = ALL_LINES)
        if (selected) RowCheck()
    }
}

/**
 * A round mark with the first character of [label], for a row whose picture is not known yet. Grey
 * on grey: it says "someone" without competing with the text, and gives way to a real avatar later.
 * The letter is sized in dp, like the circle, so a larger font does not break the circle.
 */
@Composable
fun Monogram(label: String, modifier: Modifier = Modifier, size: Dp = RowSpec.avatar) {
    val cs = MaterialTheme.colorScheme
    val letter = with(LocalDensity.current) { (size * 0.4f).toSp() }
    Box(
        // The letter repeats what the text beside it says, so a screen reader skips it.
        modifier.size(size).clip(CircleShape).background(cs.onSurface.copy(alpha = 0.07f))
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        OpticallyCentredText(
            monogramOf(label),
            MaterialTheme.typography.titleMedium.copy(fontSize = letter, lineHeight = letter),
            color = cs.onSurfaceVariant,
        )
    }
}

/**
 * An account's round picture. Signed in: the platform's avatar [url], drawn over the [label]'s
 * [Monogram], so the letter shows while the picture loads and stays if it cannot be loaded.
 * Nobody signed in: [DefaultAvatar]. Decorative; the name beside it is what a screen reader reads.
 */
@Composable
fun Avatar(label: String, url: String?, signedIn: Boolean, modifier: Modifier = Modifier, size: Dp = RowSpec.avatar) {
    if (!signedIn) {
        DefaultAvatar(modifier, size)
        return
    }
    Box(modifier.size(size).clip(CircleShape)) {
        Monogram(label, size = size)
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/**
 * The picture of nobody: a grey head and shoulders on the same grey circle as [Monogram], the
 * shoulders cut by the circle. Drawn, so it stays sharp at every size and in both themes.
 */
@Composable
fun DefaultAvatar(modifier: Modifier = Modifier, size: Dp = RowSpec.avatar) {
    val cs = MaterialTheme.colorScheme
    val figure = cs.onSurface.copy(alpha = 0.22f)
    Canvas(
        modifier.size(size).clip(CircleShape).background(cs.onSurface.copy(alpha = 0.07f))
            .clearAndSetSemantics { },
    ) {
        val d = this.size.minDimension
        drawCircle(figure, radius = d * 0.19f, center = Offset(d / 2f, d * 0.39f))
        drawCircle(figure, radius = d * 0.36f, center = Offset(d / 2f, d * 1.02f))
    }
}

/** First character of [label], upper-cased for Latin, whole for a character outside the BMP; empty for a blank label. */
fun monogramOf(label: String): String {
    val trimmed = label.trim()
    if (trimmed.isEmpty()) return ""
    return String(Character.toChars(trimmed.codePointAt(0))).uppercase()
}
