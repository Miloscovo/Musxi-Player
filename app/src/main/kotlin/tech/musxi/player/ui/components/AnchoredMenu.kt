package tech.musxi.player.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** Which edge of the menu lines up with the matching edge of its anchor button. */
enum class MenuAlign { START, END }

/** Gap between the anchor and the card. */
private val AnchorGap = 8.dp

/** The card never gets closer to a window edge than this. */
private val WindowMargin = 12.dp

/**
 * Transparent room around the card inside the popup window. A shadow is drawn by the card itself
 * and the window is exactly the size of its content, so without this the blur is cut off at the
 * card's own edge and the menu looks outlined rather than lifted.
 */
private val ShadowRoom = 24.dp

/**
 * A small floating menu next to the button that opened it, instead of a sheet from the screen edge.
 *
 * Put it in the same [Box] as the trigger, as the trigger's sibling: the popup anchors to the
 * bounds of its parent, which is then exactly the button. It sits above the anchor when there is
 * room ([preferAbove]) and below otherwise, and the card is kept [WindowMargin] inside the window.
 *
 * The popup stays composed until the exit animation has finished, so closing is not a pop.
 *
 * [maxHeight] caps the whole card; when it is set, rows that do not fit scroll inside it. Left
 * unspecified the card is exactly as tall as its rows.
 */
@Composable
fun AnchoredMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    align: MenuAlign = MenuAlign.END,
    preferAbove: Boolean = true,
    maxHeight: Dp = Dp.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = expanded
    if (!(state.currentState || state.targetState)) return

    // Written by the position provider once it knows where the card really landed, read by the
    // animation so the card grows out of the corner nearest the button. Before the first layout
    // the guess below is used, which only matters for a frame that is still fully transparent.
    var origin by remember { mutableStateOf(guessOrigin(align, preferAbove)) }
    val density = LocalDensity.current
    val provider = remember(align, preferAbove, density) {
        AnchoredMenuPositionProvider(
            align = align,
            preferAbove = preferAbove,
            gap = with(density) { AnchorGap.roundToPx() },
            margin = with(density) { WindowMargin.roundToPx() },
            room = with(density) { ShadowRoom.roundToPx() },
            onOrigin = { origin = it },
        )
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, clippingEnabled = false),
    ) {
        AnimatedVisibility(
            visibleState = state,
            enter = fadeIn(tween(120)) + scaleIn(
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 600f),
                initialScale = 0.85f,
                transformOrigin = origin,
            ),
            exit = fadeOut(tween(100)) + scaleOut(
                animationSpec = tween(100),
                targetScale = 0.92f,
                transformOrigin = origin,
            ),
        ) {
            Box(Modifier.padding(ShadowRoom)) { MenuCard(maxHeight, content) }
        }
    }
}

/** Corner nearest the anchor, as far as can be told before the first layout. */
private fun guessOrigin(align: MenuAlign, above: Boolean) =
    TransformOrigin(if (align == MenuAlign.START) 0f else 1f, if (above) 1f else 0f)

@Composable
private fun MenuCard(maxHeight: Dp, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val dark = cs.background.luminance() < 0.5f
    // Always remembered, so a card that gains or loses a height cap keeps its scroll position.
    val scroll = rememberScrollState()
    Column(
        Modifier
            // Natural width of the widest row, kept between 168 and 260dp.
            .width(IntrinsicSize.Max)
            .widthIn(min = 168.dp, max = 260.dp)
            .shadow(
                elevation = 12.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.10f),
                spotColor = Color.Black.copy(alpha = 0.18f),
            )
            .background(if (dark) cs.surfaceContainerHigh else cs.surfaceContainerLowest, shape)
            // A dark card on a dark page has no shadow worth speaking of; a hairline separates it.
            .then(
                if (dark) Modifier.border(BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.5f)), shape)
                else Modifier,
            )
            // The cap sits inside the background and the scroll before the padding, so the padding
            // scrolls with the rows and the card as a whole never exceeds [maxHeight].
            .then(
                if (maxHeight.isSpecified) Modifier.heightIn(max = maxHeight).verticalScroll(scroll)
                else Modifier,
            )
            .padding(vertical = 4.dp),
        content = content,
    )
}

/**
 * Places the card beside the anchor and keeps it inside the window.
 *
 * The popup window is the card plus [room] on every side, so the card's own rectangle is worked out
 * first and the window is offset by [room] afterwards. Horizontal alignment follows the layout
 * direction: START means the leading edge.
 */
private class AnchoredMenuPositionProvider(
    private val align: MenuAlign,
    private val preferAbove: Boolean,
    private val gap: Int,
    private val margin: Int,
    private val room: Int,
    private val onOrigin: (TransformOrigin) -> Unit,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val cardWidth = (popupContentSize.width - 2 * room).coerceAtLeast(0)
        val cardHeight = (popupContentSize.height - 2 * room).coerceAtLeast(0)

        val leading = (align == MenuAlign.START) == (layoutDirection == LayoutDirection.Ltr)
        val wantedX = if (leading) anchorBounds.left else anchorBounds.right - cardWidth
        val x = wantedX.coerceIn(margin, (windowSize.width - margin - cardWidth).coerceAtLeast(margin))

        val roomAbove = anchorBounds.top - gap - margin
        val roomBelow = windowSize.height - margin - (anchorBounds.bottom + gap)
        val above = when {
            preferAbove && cardHeight <= roomAbove -> true
            !preferAbove && cardHeight <= roomBelow -> false
            // Neither fits where it was wanted: take the roomier side and let the clamp do the rest.
            cardHeight <= roomAbove -> true
            cardHeight <= roomBelow -> false
            else -> roomAbove > roomBelow
        }
        val wantedY = if (above) anchorBounds.top - gap - cardHeight else anchorBounds.bottom + gap
        val y = wantedY.coerceIn(margin, (windowSize.height - margin - cardHeight).coerceAtLeast(margin))

        // Grow out of the card's corner that faces the button: the horizontal fraction follows where
        // the anchor's centre falls across the card after clamping, the vertical one the side.
        val anchorCentre = (anchorBounds.left + anchorBounds.right) / 2f
        val fractionX = if (cardWidth > 0) ((anchorCentre - x) / cardWidth).coerceIn(0f, 1f) else 0.5f
        onOrigin(TransformOrigin(fractionX, if (above) 1f else 0f))

        return IntOffset(x - room, y - room)
    }
}

/** Small grey label above a group of rows, with an optional second line. */
@Composable
fun MenuHeader(title: String, supporting: String? = null) {
    val cs = MaterialTheme.colorScheme
    // 18dp = the card's 6dp row inset + the row's own 12dp, so the title starts where row content does.
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        if (!supporting.isNullOrBlank()) {
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One row. The press highlight is a rounded tile inset 6dp inside the card, not a bar to its edges.
 * A [trailing] slot replaces the check mark of a selected row.
 *
 * [iconTint] overrides the icon colour (a liked heart keeps its own red). [leading] is a custom
 * 20dp lead such as a small cover; it takes the place of [icon] when both are given, and the row
 * keeps the same text start as an icon row.
 */
@Composable
fun MenuItem(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    selected: Boolean = false,
    supporting: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    iconTint: Color? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .alpha(if (enabled) 1f else 0.38f)
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { this.selected = selected }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                // 20dp box with a 10dp gap: the text starts at the same x as after an 18dp icon + 12dp gap.
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { leading() }
                Spacer(Modifier.width(10.dp))
            } else if (icon != null) {
                Icon(
                    icon, contentDescription = null,
                    tint = iconTint ?: if (selected) cs.primary else cs.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) cs.primary else cs.onSurface,
                    fontWeight = if (selected) FontWeight.Medium else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!supporting.isNullOrBlank()) {
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            } else if (selected) {
                Spacer(Modifier.width(12.dp))
                Icon(Icons.Filled.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Hairline between groups of rows. */
@Composable
fun MenuDivider() {
    HorizontalDivider(
        Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/** The arrow at the end of a row that opens another page of the same menu. */
@Composable
fun MenuChevron() {
    Icon(
        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
    )
}
