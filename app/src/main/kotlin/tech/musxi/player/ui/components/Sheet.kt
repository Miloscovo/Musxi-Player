package tech.musxi.player.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tech.musxi.player.ui.theme.Gestures
import tech.musxi.player.ui.theme.Haptic
import tech.musxi.player.ui.theme.Motion
import tech.musxi.player.ui.theme.isDarkSurface
import tech.musxi.player.ui.theme.rememberHaptics
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Sheet heights (spec section 9). MEDIUM is 56% of the available height. FULL is the available
 * height minus the status bar and an 8dp strip, so a sliver of the page stays visible above it.
 */
enum class SheetDetent(val fraction: Float) { MEDIUM(0.56f), FULL(1f) }

/** Test handles: the sheet surface and the grab area at its top. */
internal const val SheetTag = "musxi-sheet"
internal const val SheetDragTag = "musxi-sheet-drag"

private val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/** The grab handle and the strip under it that start a drag (spec section 6). */
private val DragZone = 56.dp

/** Page strip left visible above a FULL sheet. */
private val TopStrip = 8.dp

/** Extra travel below the screen so the shadow of a hidden sheet is out of sight too. */
private val ShadowMargin = 32.dp

/** A fast downward fling this close to MEDIUM closes the sheet instead of stopping at MEDIUM. */
private val SettleSlack = 6.dp
private const val ScrimMaxAlpha = 0.25f

/**
 * Drag and animation state of one sheet. [offset] is how far the sheet sits below its fully open
 * position, in px. Dragging writes it directly (the finger is the source of truth, no spring in
 * between); show, hide and release animate it and any new touch cancels the animation, so the
 * sheet can always be caught mid-flight.
 */
private class SheetController(private val scope: CoroutineScope) {
    // Geometry in px, refreshed on every composition.
    var fullPx = 0f
    var mediumPx = 0f
    var hiddenPx = 0f
    var flingPxPerSecond = 0f
    var slackPx = 0f

    // Latest inputs from the caller, refreshed on every composition.
    var wantsVisible = false
    var onDismiss: () -> Unit = {}
    var onDetentChange: (SheetDetent) -> Unit = {}
    var haptic: (Haptic) -> Unit = {}

    /** Distance below the open position. Infinity means "still below the screen". */
    var offset by mutableFloatStateOf(Float.POSITIVE_INFINITY)
        private set

    /** True from the first show until the exit animation has ended. */
    var mounted by mutableStateOf(false)
        private set

    /** The detent the sheet last settled on. */
    var detent = SheetDetent.MEDIUM
        private set

    private var job: Job? = null
    private var dragging = false
    private var hiding = false
    private var thresholdFired = false

    /** True while the exit animation runs. A second dismissal in that time has nothing to do. */
    val isHiding: Boolean get() = hiding

    fun shown(): Float = offset.coerceIn(0f, hiddenPx)
    private fun visibleHeight(): Float = (fullPx - shown()).coerceAtLeast(0f)
    private fun detentOffset(d: SheetDetent): Float =
        if (d == SheetDetent.FULL) 0f else (fullPx - mediumPx).coerceAtLeast(0f)

    /** Closing is decided below this visible height: pulled down by 25% of the MEDIUM height. */
    private fun dismissLine(): Float = mediumPx * (1f - Gestures.SheetDismissFraction)

    /** Black scrim strength, linear in the revealed height and 0.25 at FULL. */
    fun scrimAlpha(): Float =
        if (fullPx <= 0f) 0f else ScrimMaxAlpha * (visibleHeight() / fullPx).coerceIn(0f, 1f)

    private fun animateTo(target: Float, spec: FiniteAnimationSpec<Float>, velocity: Float = 0f, then: () -> Unit = {}) {
        job?.cancel()
        job = scope.launch {
            animate(shown(), target, velocity, spec) { value, _ -> offset = value }
            then()
        }
    }

    /** Slides in from below to [target]. Also takes over a running exit. */
    fun show(target: SheetDetent) {
        hiding = false
        dragging = false
        mounted = true
        detent = target
        animateTo(detentOffset(target), Motion.standard())
    }

    /** Slides out (scrim tap, back, or the caller hiding it) and unmounts when done. */
    fun hide() {
        if (hiding) return
        hiding = true
        dragging = false
        animateTo(hiddenPx, Motion.standard()) { finishHide() }
    }

    private fun finishHide() {
        hiding = false
        if (wantsVisible) {
            // The caller ignored onDismiss: come back instead of leaving an invisible live sheet.
            show(detent)
        } else {
            mounted = false
            offset = Float.POSITIVE_INFINITY
        }
    }

    private fun beginDrag() {
        job?.cancel()
        dragging = true
        // Grabbed while still sliding in from below: it has not "crossed" anything, so stay quiet.
        thresholdFired = visibleHeight() < dismissLine()
    }

    /** Moves the sheet by [delta] px (positive = down) and returns how much of it was used. */
    fun dragBy(delta: Float): Float {
        if (hiding || hiddenPx <= 0f) return 0f
        if (!dragging) beginDrag()
        val before = shown().coerceAtMost(fullPx)
        val after = (before + delta).coerceIn(0f, fullPx)
        offset = after
        if (!thresholdFired && visibleHeight() < dismissLine()) {
            thresholdFired = true
            haptic(Haptic.THRESHOLD)
        }
        return after - before
    }

    /**
     * Finger lifted, [velocity] in px/s with down positive. Close when pulled past the dismiss line
     * or flung down hard; a hard fling down from FULL stops at MEDIUM first; a hard fling up goes
     * to FULL; anything else settles on the nearest detent. At most one haptic per drag: the
     * threshold buzz while dragging, or the settle tick here, never both.
     */
    fun release(velocity: Float) {
        if (!dragging || hiding) return
        dragging = false
        val visible = visibleHeight()
        val target: SheetDetent? = when {
            velocity < -flingPxPerSecond -> SheetDetent.FULL
            visible < dismissLine() -> null
            velocity > flingPxPerSecond ->
                if (visible > mediumPx + slackPx) SheetDetent.MEDIUM else null
            visible - mediumPx < fullPx - visible -> SheetDetent.MEDIUM
            else -> SheetDetent.FULL
        }
        if (target == null) {
            hiding = true
            onDismiss()
            animateTo(hiddenPx, Motion.release(), velocity.coerceAtLeast(0f)) { finishHide() }
            return
        }
        if (target != detent) {
            detent = target
            onDetentChange(target)
            if (!thresholdFired) haptic(Haptic.SETTLE)
        }
        val end = detentOffset(target)
        if (abs(shown() - end) < 0.5f) {
            job?.cancel()
            offset = end
        } else {
            animateTo(end, Motion.release(), velocity)
        }
    }

    /**
     * Nested scrolling (spec section 6): a list inside the sheet scrolls only when the sheet is
     * FULL. Swiping up lifts the sheet first; pulling down on a list that is already at its top
     * moves the sheet. The release fling goes to the sheet whenever the sheet was moved.
     */
    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (source == NestedScrollSource.UserInput && available.y < 0f && shown() > 0f) {
                Offset(0f, dragBy(available.y))
            } else {
                Offset.Zero
            }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (source == NestedScrollSource.UserInput && available.y > 0f) {
                Offset(0f, dragBy(available.y))
            } else {
                Offset.Zero
            }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (!dragging) return Velocity.Zero
            // Already fully open and flung upward: the list keeps the momentum.
            val listKeepsIt = shown() <= 0f && available.y < 0f
            release(available.y)
            return if (listKeepsIt) Velocity.Zero else available
        }
    }
}

/**
 * The glass this sheet draws with. Inside a [GlassHost] that is the host's; outside one `glass()`
 * alone would paint the light-mode white in every theme, so the solid fallback is built here with
 * the theme's own colours (spec section 3: white in light, surfaceContainerHigh in dark).
 */
@Composable
internal fun sheetGlass(): GlassScope {
    val host = LocalGlass.current
    if (host != null) return host
    val cs = MaterialTheme.colorScheme
    val dark = cs.background.isDarkSurface()
    val haze = remember { HazeState() }
    return GlassScope(haze, blurs = false, dark = dark, solid = if (dark) cs.surfaceContainerHigh else Color.White)
}

/**
 * The app's sheet (spec section 9): regular glass that slides up from the bottom and rests on one
 * of two heights. Used for the source switch, and meant for long lists and the song info.
 *
 * Placement: call it from the `overlay` slot of [GlassHost] (or any full-window layer), never inside
 * its `content`. The glass can only blur what the host recorded as page content, and the overlay is
 * what makes this fill the whole window, status bar included. Over the player, use the `overlay`
 * of the player's [GlassLayer], so the glass shows the player and not the tab under it. Without a
 * host it draws solid.
 *
 * Contract (callers rely on it, the body may change):
 * - [visible] shows or hides it. Showing slides it up from the bottom to [initialDetent] with
 *   `Motion.standard()`; hiding slides it out and only then stops composing it, so keep the call
 *   in the tree and drive it with [visible]. [initialDetent] is read when it appears.
 * - [onDismiss] is called when the user pulls it down, taps the scrim or presses back. The sheet
 *   does not hide itself: set [visible] to false in the callback. (If the caller refuses, a pulled
 *   down sheet slides back.) Back is held for as long as the sheet is on screen, the slide-out
 *   included, so a press during the exit never reaches the host's own handler.
 * - [onDetentChange] reports each detent the sheet settles on after the user moved it, not the
 *   initial one and not closing.
 * - [content] sits under the grab handle in a Column that fills the sheet. It handles its own
 *   bottom inset (navigation bar), and a scrolling body (a list, or `verticalScroll`) should take
 *   `Modifier.weight(1f)` so it hands drags over to the sheet at its top (nested scroll). Buttons
 *   and lists keep their own touches; the handle and the first 56dp that no child handles drag the
 *   sheet.
 *
 * Detents: MEDIUM is 56% of the window height, FULL is the window height minus the status bar and 8dp.
 * Release (Gestures): closes when pulled below 25% of the MEDIUM height or flung down above
 * 1000dp/s; a hard fling down from FULL stops at MEDIUM first; a hard fling up goes to FULL;
 * otherwise the nearest detent. Haptics: Haptic.THRESHOLD the first time a drag crosses the close
 * line, Haptic.SETTLE when a release lands on another detent, at most one per drag.
 */
@Composable
fun MusxiSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialDetent: SheetDetent = SheetDetent.MEDIUM,
    onDetentChange: (SheetDetent) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ctl = remember { SheetController(scope) }
    ctl.wantsVisible = visible
    ctl.onDismiss = onDismiss
    ctl.onDetentChange = onDetentChange
    ctl.haptic = rememberHaptics()
    if (!visible && !ctl.mounted) return
    // Registered while the sheet is composed, so it is the newest callback and wins over the host's
    // own. It stays registered through the exit animation and then does nothing: a back press while
    // the sheet slides out is consumed, not passed on to close the page behind it.
    BackHandler { if (visible && !ctl.isHiding) onDismiss() }

    val cs = MaterialTheme.colorScheme
    val glass = sheetGlass()
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val containerPx = constraints.maxHeight.toFloat()
        ctl.fullPx = (containerPx - statusTop - with(density) { TopStrip.toPx() }).coerceAtLeast(0f)
        ctl.mediumPx = (containerPx * SheetDetent.MEDIUM.fraction).coerceAtMost(ctl.fullPx)
        ctl.hiddenPx = ctl.fullPx + with(density) { ShadowMargin.toPx() }
        ctl.flingPxPerSecond = with(density) { Gestures.FlingDpPerSecond.dp.toPx() }
        ctl.slackPx = with(density) { SettleSlack.toPx() }
        LaunchedEffect(visible) {
            if (visible) ctl.show(initialDetent) else ctl.hide()
        }

        // Scrim: black, as strong as the sheet is tall (0.25 at FULL); a tap closes.
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(Color.Black.copy(alpha = ctl.scrimAlpha())) }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "关闭",
                    role = Role.Button,
                ) { if (visible && !ctl.isHiding) onDismiss() },
        )

        val dragState = rememberDraggableState { delta -> ctl.dragBy(delta) }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(with(density) { ctl.fullPx.toDp() })
                .offset { IntOffset(0, ctl.shown().roundToInt()) }
                .testTag(SheetTag)
                .glass(glass, SheetShape, GlassStyle.REGULAR)
                .nestedScroll(ctl.connection)
                // Swallows taps on the sheet itself so they never reach the scrim behind it.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            // Drag area, drawn first so buttons and lists in front of it keep their own touches.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(DragZone)
                    .testTag(SheetDragTag)
                    .draggable(dragState, Orientation.Vertical, onDragStopped = { ctl.release(it) }),
            )
            CompositionLocalProvider(LocalContentColor provides cs.onSurface) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(width = 36.dp, height = 5.dp)
                                .clip(CircleShape)
                                .background(cs.onSurface.copy(alpha = 0.30f)),
                        )
                    }
                    content()
                }
            }
        }
    }
}
