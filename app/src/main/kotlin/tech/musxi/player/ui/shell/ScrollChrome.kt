package tech.musxi.player.ui.shell

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.musxi.player.ui.components.LocalGlass
import tech.musxi.player.ui.components.glassBar
import tech.musxi.player.ui.nav.LocalNavPageExit
import tech.musxi.player.ui.nav.LocalNavPageOnTop
import tech.musxi.player.ui.theme.Motion
import tech.musxi.player.ui.theme.Space

/** The top bar's own height, below the status bar: the playlist page's old bar, so nothing moves. */
val TopBarHeight = 56.dp

/** Glass and small title come in over this much scrolling (spec section 10). */
private val TitleFade = 24.dp

/** Downward scrolling, summed, that folds the dock into one row (spec section 10). */
private val CollapseAfter = 48.dp

/** Upward scrolling that brings the tabs back: "a little", not a whole flick. */
private val ExpandAfter = 8.dp

/**
 * The shell's scroll linkage (spec section 10): the pages' vertical scrolling folds the dock, and
 * the page on top names the glass bar along the top edge.
 *
 * [connection] goes on the box that holds the pages, so every list in every tab reports to it
 * without knowing it exists. Only scrolling that really moved something counts, which is why a page
 * that fits on one screen never folds the dock: it has nothing to consume.
 */
@Stable
class ScrollChrome {
    /** The dock is folded into its one-row capsule. */
    var collapsed by mutableStateOf(false)
        private set

    /** Screen density, set by the shell; the thresholds are in dp. */
    var density: Float = 1f

    internal val bars = mutableStateListOf<TopBarEntry>()

    /** The bar of the page on top; null when that page has no large title (a folder page, an account). */
    internal val bar: TopBarEntry? get() = bars.lastOrNull()

    private var down = 0f
    private var up = 0f

    /** Back to the full dock: a new page, another tab, or a tap on the folded capsule. */
    fun expand() {
        collapsed = false
        down = 0f
        up = 0f
    }

    internal fun scrolled(dy: Float, pullAtTop: Boolean) {
        when {
            dy < 0f -> {
                down -= dy
                up = 0f
                if (!collapsed && down >= CollapseAfter.value * density) collapsed = true
            }
            dy > 0f -> {
                up += dy
                down = 0f
                if (collapsed && up >= ExpandAfter.value * density) expand()
            }
            // Pulling down at the very top: the page is home, so is the dock.
            pullAtTop -> expand()
        }
    }

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            scrolled(consumed.y, pullAtTop = available.y > 0f)
            return Offset.Zero
        }
    }
}

/** The shell's [ScrollChrome], provided to the pages only. Null in the design sheets and tests. */
val LocalScrollChrome = staticCompositionLocalOf<ScrollChrome?> { null }

/** Where a page's large title is. Put [largeTitle] on the title itself. */
@Stable
class TitleProbe {
    /**
     * Top edge of the title in window pixels. NaN before the first layout; minus infinity once the
     * title has left the composition while its page stays, which in a lazy list means scrolled away.
     */
    var top by mutableFloatStateOf(Float.NaN)
        internal set

    /**
     * The page's own word, from its scroll position, that the title is away. Wins over [top]: a
     * lazy list restored scrolled down never composes its title, and one scrolled past it may keep
     * it composed at its last place.
     */
    var away: () -> Boolean = { false }
}

@Composable
fun rememberTitleProbe(): TitleProbe = remember { TitleProbe() }

/** Reports where this title is to [probe], and that it is gone once a lazy list lets it go. */
fun Modifier.largeTitle(probe: TitleProbe): Modifier = this then LargeTitleElement(probe)

private data class LargeTitleElement(val probe: TitleProbe) : ModifierNodeElement<LargeTitleNode>() {
    override fun create() = LargeTitleNode(probe)
    override fun update(node: LargeTitleNode) {
        node.probe = probe
    }
}

private class LargeTitleNode(var probe: TitleProbe) : Modifier.Node(), GlobalPositionAwareModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        probe.top = coordinates.positionInWindow().y
    }

    override fun onDetach() {
        probe.top = Float.NEGATIVE_INFINITY
    }
}

/** One page's wish for the top bar. Written by [PageTopBar], drawn by [ShellTopBar]. */
@Stable
internal class TopBarEntry {
    var title by mutableStateOf("")
    var probe by mutableStateOf<TitleProbe?>(null)
    var scrolled by mutableStateOf<(() -> Float)?>(null)
    var leading by mutableStateOf<(@Composable () -> Unit)?>(null)
    var trailing by mutableStateOf<(@Composable () -> Unit)?>(null)
    /** The page's exit from its stack (see LocalNavPageExit), read in draw blocks. */
    var exit: () -> Float = { 0f }
}

/**
 * Asks the shell's top bar for [title], shown once the large title marked with [probe] has gone
 * under the bar. The glass comes in with the title; with [scrolled] (pixels the page has moved) it
 * comes in as soon as content goes under the bar instead, for a page whose bar carries buttons.
 * [leading] and [trailing] are those buttons; the page leaves [TopBarHeight] free for them.
 *
 * Only the top page of a stack is heard. Outside the shell this does nothing, so the page has to
 * draw its own buttons there (see [hasShellTopBar]).
 */
@Composable
fun PageTopBar(
    title: String,
    probe: TitleProbe,
    scrolled: (() -> Float)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val chrome = LocalScrollChrome.current ?: return
    val onTop = LocalNavPageOnTop.current
    val exit = LocalNavPageExit.current
    val entry = remember { TopBarEntry() }
    SideEffect {
        entry.exit = exit
        entry.title = title
        entry.probe = probe
        entry.scrolled = scrolled
        entry.leading = leading
        entry.trailing = trailing
    }
    DisposableEffect(chrome, onTop) {
        if (onTop) chrome.bars.add(entry)
        onDispose { chrome.bars.remove(entry) }
    }
}

/** True under the shell, where [PageTopBar] draws the page's top buttons. */
@Composable
fun hasShellTopBar(): Boolean = LocalScrollChrome.current != null

/**
 * The thin glass bar along the top edge, in the shell's overlay so it is real glass over the page.
 * Clear while the page is at rest; it takes touches only while it shows glass or carries buttons,
 * so a control under the clear bar (the library's density button) still works.
 */
@Composable
fun ShellTopBar(chrome: ScrollChrome, modifier: Modifier = Modifier) {
    val entry = chrome.bar ?: return
    key(entry) { TopBarOf(entry, modifier) }
}

@Composable
private fun TopBarOf(entry: TopBarEntry, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val fade = with(density) { TitleFade.toPx() }
    val barHeight = with(density) { TopBarHeight.toPx() }
    // Top of the bar's own row, just under the status bar.
    var rowTop by remember { mutableFloatStateOf(Float.NaN) }
    val buttons = entry.leading != null || entry.trailing != null
    // The small title starts as the large one starts to leave: at the page's top edge, or below
    // the buttons when the bar carries some, and is in fully [TitleFade] later.
    val titleShown: () -> Float = {
        val probe = entry.probe
        val top = probe?.top ?: Float.NaN
        val edge = rowTop + if (buttons) barHeight else 0f
        when {
            probe == null || rowTop.isNaN() -> 0f
            // A lazy list may keep a scrolled-off title composed but unplaced, at its last place.
            probe.away() -> 1f
            top.isNaN() -> 0f
            else -> ((edge - top) / fade).coerceIn(0f, 1f)
        }
    }
    // A leaving page takes its bar with it; a page being pushed brings its own in.
    val present: () -> Float = { (1f - 2f * entry.exit()).coerceIn(0f, 1f) }
    val glass: () -> Float = {
        present() * (entry.scrolled?.let { (it() / fade).coerceIn(0f, 1f) } ?: titleShown())
    }
    val blocks by remember(entry) { derivedStateOf { glass() > 0.5f } }
    val showTitle by remember(entry) { derivedStateOf { titleShown() > 0f } }
    val hairline = cs.outlineVariant.copy(alpha = 0.5f)
    Box(
        modifier
            .fillMaxWidth()
            .then(
                if (blocks || buttons) {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            // Over glass the page below is not what the finger sees, so it gets nothing.
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                            } while (event.changes.any { it.pressed })
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = glass() }
                .glassBar(LocalGlass.current)
                .drawWithContent {
                    drawContent()
                    val y = size.height - 0.25.dp.toPx()
                    drawLine(hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.5.dp.toPx())
                },
        )
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .onGloballyPositioned { rowTop = it.positionInWindow().y }
                .heightIn(min = TopBarHeight)
                .graphicsLayer { alpha = present() },
        ) {
            // Where the page's own top bar had its buttons, inside the page margin.
            entry.leading?.let { Box(Modifier.align(Alignment.CenterStart).padding(start = Space.page)) { it() } }
            if (showTitle) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 72.dp)
                        .graphicsLayer { alpha = titleShown() }
                        .semantics { heading() },
                )
            }
            entry.trailing?.let { Box(Modifier.align(Alignment.CenterEnd).padding(end = Space.page)) { it() } }
        }
    }
}
