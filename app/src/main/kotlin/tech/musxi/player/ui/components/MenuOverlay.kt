package tech.musxi.player.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import kotlinx.coroutines.launch
import tech.musxi.player.ui.theme.Motion
import kotlin.math.roundToInt

/**
 * The shell's same-window menu layer (spec section 3): menus opened from the pages are drawn here,
 * in the glass host's overlay, so their card is real glass over the page they came from. A system
 * `Popup` is a window of its own and can only ever be solid.
 *
 * Provided by the shell to the pages only. The player, the sheets and anything else in the
 * overlay do not see it, so their menus stay `Popup`s with a solid card: what is behind them is not
 * recorded, and a blur that does not match what is on screen is worse than none.
 */
val LocalMenuOverlay = staticCompositionLocalOf<MenuOverlayState?> { null }

/** The menus the overlay is drawing, newest last. Usually one; two only while one closes as another opens. */
@Stable
class MenuOverlayState {
    internal val entries = mutableStateListOf<MenuEntry>()

    internal fun show(entry: MenuEntry) {
        if (entry !in entries) entries.add(entry)
        entry.mounted = true
    }

    internal fun remove(entry: MenuEntry) {
        entries.remove(entry)
        entry.mounted = false
    }

    /** A menu is open: the shell's own back handling waits behind it. */
    val open: Boolean get() = entries.any { it.expanded }
}

/** One menu as its anchor last described it. Written by [AnchoredMenu] after each composition. */
@Stable
internal class MenuEntry {
    var expanded by mutableStateOf(false)
    var mounted by mutableStateOf(false)
    /** The anchor's bounds in window pixels. */
    var anchor by mutableStateOf<Rect?>(null)
    var align by mutableStateOf(MenuAlign.END)
    var preferAbove by mutableStateOf(true)
    var maxHeight by mutableStateOf(Dp.Unspecified)
    var onDismiss: () -> Unit by mutableStateOf({})
    var content: @Composable ColumnScope.() -> Unit by mutableStateOf({})
}

/**
 * [AnchoredMenu] in a page: hands the menu to [overlay] and marks where its anchor is. The anchor
 * is the parent layout, as with the `Popup` it replaces, found through a zero-size child the way
 * the `Popup` does it, so the page's layout does not change.
 */
@Composable
internal fun OverlayAnchoredMenu(
    overlay: MenuOverlayState,
    expanded: Boolean,
    onDismiss: () -> Unit,
    align: MenuAlign,
    preferAbove: Boolean,
    maxHeight: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val entry = remember { MenuEntry() }
    SideEffect {
        entry.align = align
        entry.preferAbove = preferAbove
        entry.maxHeight = maxHeight
        entry.onDismiss = onDismiss
        entry.content = content
        entry.expanded = expanded
    }
    LaunchedEffect(expanded) { if (expanded) overlay.show(entry) }
    DisposableEffect(overlay, entry) { onDispose { overlay.remove(entry) } }
    // Rows of a long list each hold a closed menu: only an open (or closing) one measures its anchor.
    if (!expanded && !entry.mounted) return
    Layout(
        content = {},
        modifier = Modifier.onGloballyPositioned { child ->
            // Unclipped, as the popup measures it: a row half under the dock still opens its menu
            // from where the row really is.
            child.parentLayoutCoordinates?.let { parent ->
                val at = parent.positionInWindow()
                entry.anchor = Rect(at.x, at.y, at.x + parent.size.width, at.y + parent.size.height)
            }
        },
    ) { _, _ -> layout(0, 0) {} }
}

/**
 * Draws the menus of [state]: a clear layer that takes the outside tap (it closes the menu and
 * reaches nothing below, like the popup did), then each card beside its anchor. Put it last in the
 * glass host's overlay, above the dock and the player, so its back handler is the first to run.
 */
@Composable
fun MenuOverlayLayer(state: MenuOverlayState) {
    if (state.entries.isEmpty()) return
    val open = state.entries.lastOrNull { it.expanded }
    BackHandler(enabled = open != null) { open?.onDismiss?.invoke() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        if (open != null) {
            Box(
                Modifier.fillMaxSize().pointerInput(open) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        open.onDismiss()
                        // The rest of this touch is spent here too, so a drag does not scroll the page.
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                },
            )
        }
        for (entry in state.entries.toList()) {
            key(entry) { OverlayMenuCard(entry, origin, onGone = { state.remove(entry) }) }
        }
    }
}

@Composable
private fun OverlayMenuCard(entry: MenuEntry, windowOrigin: Offset, onGone: () -> Unit) {
    val scale = remember { Animatable(0.85f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(entry.expanded) {
        if (entry.expanded) {
            launch { scale.animateTo(1f, Motion.menu()) }
            alpha.animateTo(1f, Motion.enter())
        } else {
            launch { scale.animateTo(0.92f, Motion.menuDismiss()) }
            alpha.animateTo(0f, Motion.exit())
            onGone()
        }
    }
    val anchor = entry.anchor ?: return
    Layout(
        content = { GlassMenuCard(entry.maxHeight, entry.content) },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val margin = WindowMargin.roundToPx()
        val gap = AnchorGap.roundToPx()
        val window = IntSize(constraints.maxWidth, constraints.maxHeight)
        val card = measurables.first().measure(
            Constraints(
                maxWidth = (window.width - 2 * margin).coerceAtLeast(0),
                maxHeight = (window.height - 2 * margin).coerceAtLeast(0),
            ),
        )
        val local = IntRect(
            (anchor.left - windowOrigin.x).roundToInt(), (anchor.top - windowOrigin.y).roundToInt(),
            (anchor.right - windowOrigin.x).roundToInt(), (anchor.bottom - windowOrigin.y).roundToInt(),
        )
        val placed = placeAnchoredMenu(
            local, window, IntSize(card.width, card.height), entry.align, entry.preferAbove, gap, margin, layoutDirection,
        )
        layout(window.width, window.height) {
            card.placeWithLayer(placed.offset) {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
                transformOrigin = placed.origin
            }
        }
    }
}

/** The menu card as thin glass. Always scrolls inside its cap, so a 200% font never runs off the window. */
@Composable
private fun GlassMenuCard(maxHeight: Dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val scroll = rememberScrollState()
    Column(
        Modifier
            .semantics { paneTitle = "菜单"; isTraversalGroup = true }
            .width(IntrinsicSize.Max)
            .widthIn(min = 168.dp, max = 260.dp)
            .glass(LocalGlass.current, shape, GlassStyle.THIN)
            .then(if (maxHeight.isSpecified) Modifier.heightIn(max = maxHeight) else Modifier)
            .verticalScroll(scroll)
            .padding(vertical = 4.dp),
        content = content,
    )
}

/** Where a card of [card] size lands beside [anchor], and the corner it grows from. */
internal class MenuPlacement(val offset: IntOffset, val origin: TransformOrigin)

/**
 * The card beside its anchor and inside the window, [margin] from every edge: above when there is
 * room and [preferAbove], below otherwise; when neither side fits, the roomier one with a clamp.
 * Horizontal alignment follows the layout direction: START is the leading edge. The origin is the
 * card's corner facing the anchor, so the card grows out of the button.
 */
internal fun placeAnchoredMenu(
    anchor: IntRect,
    window: IntSize,
    card: IntSize,
    align: MenuAlign,
    preferAbove: Boolean,
    gap: Int,
    margin: Int,
    layoutDirection: LayoutDirection,
): MenuPlacement {
    val leading = (align == MenuAlign.START) == (layoutDirection == LayoutDirection.Ltr)
    val wantedX = if (leading) anchor.left else anchor.right - card.width
    val x = wantedX.coerceIn(margin, (window.width - margin - card.width).coerceAtLeast(margin))

    val roomAbove = anchor.top - gap - margin
    val roomBelow = window.height - margin - (anchor.bottom + gap)
    val above = when {
        preferAbove && card.height <= roomAbove -> true
        !preferAbove && card.height <= roomBelow -> false
        card.height <= roomAbove -> true
        card.height <= roomBelow -> false
        else -> roomAbove > roomBelow
    }
    val wantedY = if (above) anchor.top - gap - card.height else anchor.bottom + gap
    val y = wantedY.coerceIn(margin, (window.height - margin - card.height).coerceAtLeast(margin))

    val anchorCentre = (anchor.left + anchor.right) / 2f
    val fractionX = if (card.width > 0) ((anchorCentre - x) / card.width).coerceIn(0f, 1f) else 0.5f
    return MenuPlacement(IntOffset(x, y), TransformOrigin(fractionX, if (above) 1f else 0f))
}
