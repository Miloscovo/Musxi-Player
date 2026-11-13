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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
import tech.musxi.player.ui.theme.rememberReducedMotion
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
 * Release and overdrag rules of [MusxiSheet], kept free of Compose so they can be unit tested.
 * All values are px and px/s; velocity is positive downward (closing), as the drag reports it.
 */
internal object SheetPhysics {
    /**
     * Where the sheet is drawn for a finger offset of [raw] px below FULL. Above FULL ([raw] < 0)
     * the sheet follows through [Gestures.rubberBand] and never rises more than [extent]. A pure
     * function of [raw], so pulling back retraces the same path and never jumps.
     */
    fun overdragShown(raw: Float, extent: Float): Float =
        if (raw >= 0f) raw else -Gestures.rubberBand(-raw, extent)

    /** Inverse of [overdragShown]: the finger offset that draws the sheet at [shown]. */
    fun overdragRaw(shown: Float, extent: Float): Float {
        if (shown >= 0f) return shown
        if (extent <= 0f) return 0f
        // Inverted up to three quarters of the band: deeper the inverse runs away (at 0.999 of the
        // extent it is ~1800 extents), and a finger caught there would have to travel that far back
        // before the sheet moved. Deeper than that it resumes from three quarters, a jump of at most
        // a quarter of the (small) band.
        val d = (-shown).coerceAtMost(extent * 0.75f)
        return -(extent / Gestures.RubberBandCoefficient) * d / (extent - d)
    }

    /**
     * How fast the drawn sheet moves for a finger moving at 1 px/s at [raw] (the band's local slope).
     * Release velocity out of the band is scaled by this, not by drawn/raw: the secant overstates it
     * several times, so the return spring would overshoot into the hard stop.
     */
    fun overdragSlope(raw: Float, extent: Float): Float {
        if (raw >= 0f) return 1f
        if (extent <= 0f) return 0f
        val c = Gestures.RubberBandCoefficient
        val denominator = extent + c * -raw
        return extent * c * extent / (denominator * denominator)
    }

    /**
     * The detent a released sheet goes to, or null to close it (spec section 9).
     *
     * The sheet "coasts" to `visible − velocity × SheetProjectionSeconds` (a release slower than
     * [minProjectionPxPerSecond] counts as still, so a slow lift lands where the finger left it) and
     * the stop nearest that point wins (closed, MEDIUM or FULL). When that is the stop the sheet is
     * already nearest to, the sheet has moved past it in the release's direction and the release is
     * a deliberate flick (at least [stepPxPerSecond]), it goes on to the next stop that way: a flick
     * up from MEDIUM opens FULL even when it was short (iOS sheets do the same).
     *
     * Closing is then held to the spec: below the dismiss line (MEDIUM × (1 − SheetDismissFraction))
     * and not moving up it closes; above the line only a hard downward fling (at least
     * [flingPxPerSecond]) released at or around MEDIUM ([slackPx]) closes. So a light pull from FULL
     * never closes, and a hard fling down from FULL lands on MEDIUM first.
     *
     * @param visible how much of the sheet shows above its bottom edge (FULL = [fullPx]).
     * @param velocity px/s, positive downward (closing).
     */
    fun releaseTarget(
        visible: Float,
        velocity: Float,
        fullPx: Float,
        mediumPx: Float,
        minProjectionPxPerSecond: Float,
        stepPxPerSecond: Float,
        flingPxPerSecond: Float,
        slackPx: Float,
    ): SheetDetent? {
        val dismissLine = mediumPx * (1f - Gestures.SheetDismissFraction)
        val effective = if (abs(velocity) < minProjectionPxPerSecond) 0f else velocity
        // Below the close line and not on its way back up.
        if (visible < dismissLine && effective >= 0f) return null
        val stops = floatArrayOf(0f, mediumPx, fullPx) // closed, MEDIUM, FULL
        val projected = visible - effective * Gestures.SheetProjectionSeconds
        var index = nearest(stops, projected)
        if (abs(effective) >= stepPxPerSecond && nearest(stops, visible) == index) {
            if (effective < 0f && visible > stops[index] && index < stops.lastIndex) index += 1
            else if (effective > 0f && visible < stops[index] && index > 0) index -= 1
        }
        val hardDown = effective >= flingPxPerSecond
        // A hard fling down released at (or within [slackPx] above) MEDIUM is a fling from MEDIUM.
        if (hardDown && index == 1 && visible <= mediumPx + slackPx) index = 0
        if (index == 0) {
            val closes = visible < dismissLine || (hardDown && visible <= mediumPx + slackPx)
            if (!closes) index = 1
        }
        return when (index) {
            0 -> null
            1 -> SheetDetent.MEDIUM
            else -> SheetDetent.FULL
        }
    }

    /** Index of the stop nearest [value]; the lower one wins a tie. */
    private fun nearest(stops: FloatArray, value: Float): Int {
        var best = 0
        for (i in 1 until stops.size) if (abs(stops[i] - value) < abs(stops[best] - value)) best = i
        return best
    }
}

/**
 * Drag and animation state of one sheet. [offset] is how far the sheet sits below its fully open
 * position, in px (negative while pulled past FULL). Dragging writes it directly from the finger's
 * own offset (no spring in between; past FULL through the rubber band); show, hide and release
 * animate it from the current velocity, and any new touch cancels the animation, so the sheet can
 * always be caught mid-flight.
 */
private class SheetController(private val scope: CoroutineScope) {
    // Geometry in px, refreshed on every composition.
    var fullPx = 0f
    var mediumPx = 0f
    var hiddenPx = 0f
    var flingPxPerSecond = 0f
    var stepPxPerSecond = 0f
    var minProjectionPxPerSecond = 0f
    var slackPx = 0f

    /** How far above FULL the sheet may be pulled (rubber band limit), 0 for none. */
    var overdragPx = 0f

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

    /** The finger's own offset during a drag; [offset] is drawn from it (see [SheetPhysics.overdragShown]). */
    private var raw = 0f

    /**
     * The current drag reached the sheet through a list inside it (nested scroll), not its handle.
     * A list pulled at its top is often just reading, so only a real fling steps to the next detent.
     */
    private var dragFromList = false

    /** Velocity of the running animation in px/s, so the next one can start from it. */
    private var animVelocity = 0f

    /** True while the exit animation runs. A second dismissal in that time has nothing to do. */
    val isHiding: Boolean get() = hiding

    fun shown(): Float = offset.coerceIn(-overdragPx, hiddenPx)

    /** Px the sheet is pulled above FULL; it grows by this at the bottom so no gap opens there. */
    val stretchPx: Int by derivedStateOf { (-shown().roundToInt()).coerceAtLeast(0) }

    private fun visibleHeight(): Float = (fullPx - shown()).coerceAtLeast(0f)
    private fun detentOffset(d: SheetDetent): Float =
        if (d == SheetDetent.FULL) 0f else (fullPx - mediumPx).coerceAtLeast(0f)

    /** Closing is decided below this visible height: pulled down by 25% of the MEDIUM height. */
    private fun dismissLine(): Float = mediumPx * (1f - Gestures.SheetDismissFraction)

    /** Black scrim strength, linear in the revealed height and 0.25 at FULL. */
    fun scrimAlpha(): Float =
        if (fullPx <= 0f) 0f else ScrimMaxAlpha * (visibleHeight() / fullPx).coerceIn(0f, 1f)

    /** Velocity of the animation still running, 0 when none is. */
    private fun liveVelocity(): Float = if (job?.isActive == true) animVelocity else 0f

    /** Animates to [target], by default carrying on from the velocity of the animation it replaces. */
    private fun animateTo(
        target: Float,
        spec: FiniteAnimationSpec<Float>,
        velocity: Float = liveVelocity(),
        then: () -> Unit = {},
    ) {
        job?.cancel()
        val start = shown()
        // Settling up onto FULL may overshoot it. That is not a finger pulling past FULL: draw it
        // at FULL instead of letting it rise into the band and sit on its hard stop.
        val floor = if (start >= 0f && target >= 0f) 0f else Float.NEGATIVE_INFINITY
        job = scope.launch {
            animate(start, target, velocity, spec) { value, v ->
                offset = value.coerceAtLeast(floor)
                animVelocity = v
            }
            animVelocity = 0f
            then()
        }
    }

    /** Slides in from below to [target]. Also takes over a running exit, keeping its momentum. */
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

    private fun beginDrag(fromList: Boolean) {
        job?.cancel()
        dragging = true
        dragFromList = fromList
        // Caught mid-flight: continue from where it is drawn, above FULL through the rubber band's
        // inverse, so the first move does not jump.
        raw = SheetPhysics.overdragRaw(shown(), overdragPx).coerceAtMost(fullPx)
        // Grabbed while still sliding in from below: it has not "crossed" anything, so stay quiet.
        thresholdFired = visibleHeight() < dismissLine()
    }

    /**
     * Moves the sheet by [delta] px (positive = down) and returns how much of it was used. With
     * [overdrag] the finger may pull past FULL and the sheet follows through the rubber band;
     * without it (list scrolling, reduced motion) the sheet stops hard at FULL.
     */
    fun dragBy(delta: Float, overdrag: Boolean): Float {
        if (hiding || hiddenPx <= 0f) return 0f
        if (!dragging) {
            beginDrag(fromList = !overdrag)
        } else if (abs(SheetPhysics.overdragShown(raw, overdragPx) - shown()) > 0.5f) {
            // Moved under the finger by something else (geometry change): follow from where it is drawn.
            raw = SheetPhysics.overdragRaw(shown(), overdragPx).coerceAtMost(fullPx)
        }
        val before = raw
        // Without overdrag it may still come down from a rubber band it was caught in, never go further up.
        val top = if (overdrag && overdragPx > 0f) Float.NEGATIVE_INFINITY else minOf(before, 0f)
        raw = (before + delta).coerceIn(top, fullPx)
        offset = SheetPhysics.overdragShown(raw, overdragPx)
        if (!thresholdFired && visibleHeight() < dismissLine()) {
            thresholdFired = true
            haptic(Haptic.THRESHOLD)
        }
        return raw - before
    }

    /**
     * Finger lifted, [velocity] in px/s with down positive. Pulled past FULL: back to FULL, with the
     * velocity scaled by the rubber band's local slope so it does not overshoot. A drag that came
     * through a list steps to the next detent only on a real fling (see [dragFromList]). Otherwise the target
     * comes from [SheetPhysics.releaseTarget] (projected landing point). At most one haptic per
     * drag: the threshold buzz while dragging, or the settle tick here, never both.
     */
    fun release(velocity: Float) {
        if (!dragging || hiding) return
        dragging = false
        val drawn = shown()
        if (drawn < 0f && raw < 0f) {
            settle(SheetDetent.FULL, velocity * SheetPhysics.overdragSlope(raw, overdragPx), Motion.release())
            return
        }
        val target = SheetPhysics.releaseTarget(
            visible = visibleHeight(),
            velocity = velocity,
            fullPx = fullPx,
            mediumPx = mediumPx,
            minProjectionPxPerSecond = minProjectionPxPerSecond,
            stepPxPerSecond = if (dragFromList) flingPxPerSecond else stepPxPerSecond,
            flingPxPerSecond = flingPxPerSecond,
            slackPx = slackPx,
        )
        val spec: FiniteAnimationSpec<Float> =
            if (abs(velocity) >= flingPxPerSecond) Motion.releaseFast() else Motion.release()
        if (target == null) {
            hiding = true
            onDismiss()
            animateTo(hiddenPx, spec, velocity.coerceAtLeast(0f)) { finishHide() }
            return
        }
        settle(target, velocity, spec)
    }

    private fun settle(target: SheetDetent, velocity: Float, spec: FiniteAnimationSpec<Float>) {
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
            animateTo(end, spec, velocity)
        }
    }

    /**
     * Nested scrolling (spec section 6): a list inside the sheet scrolls only when the sheet is
     * FULL. Swiping up lifts the sheet first; pulling down on a list that is already at its top
     * moves the sheet. The release fling goes to the sheet whenever the sheet was moved. A list
     * never rubber-bands the sheet past FULL: at FULL its upward scroll is the list's own.
     */
    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (source == NestedScrollSource.UserInput && available.y < 0f && shown() > 0f) {
                Offset(0f, dragBy(available.y, overdrag = false))
            } else {
                Offset.Zero
            }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (source == NestedScrollSource.UserInput && available.y > 0f) {
                Offset(0f, dragBy(available.y, overdrag = false))
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
 * Release (Gestures, see [SheetPhysics.releaseTarget]): lands on the detent nearest where the release
 * would coast to, a deliberate flick moves on to the next detent; closes when pulled below 25% of the
 * MEDIUM height or flung down above 1000dp/s from MEDIUM; a hard fling down from FULL stops at
 * MEDIUM first. Dragged past FULL on the handle it gives way through the rubber band (not with
 * reduced motion) and springs back. Haptics: Haptic.THRESHOLD the first time a drag crosses the close
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
    val reducedMotion = rememberReducedMotion()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val containerPx = constraints.maxHeight.toFloat()
        ctl.fullPx = (containerPx - statusTop - with(density) { TopStrip.toPx() }).coerceAtLeast(0f)
        ctl.mediumPx = (containerPx * SheetDetent.MEDIUM.fraction).coerceAtMost(ctl.fullPx)
        ctl.hiddenPx = ctl.fullPx + with(density) { ShadowMargin.toPx() }
        ctl.flingPxPerSecond = with(density) { Gestures.FlingDpPerSecond.dp.toPx() }
        ctl.stepPxPerSecond = with(density) { Gestures.SheetStepDpPerSecond.dp.toPx() }
        ctl.minProjectionPxPerSecond = with(density) { Gestures.MinProjectionDpPerSecond.dp.toPx() }
        ctl.slackPx = with(density) { SettleSlack.toPx() }
        // Past FULL the sheet may rise through the rubber band, but never into the status bar:
        // at most the gap between FULL's top edge and the status-bar inset. Frame-driven, so off
        // with reduced motion (it then stops hard at FULL).
        ctl.overdragPx = if (reducedMotion) 0f else minOf(
            with(density) { Gestures.SheetOverdragDp.dp.toPx() },
            (containerPx - ctl.fullPx - statusTop).coerceAtLeast(0f),
        )
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

        val dragState = rememberDraggableState { delta -> ctl.dragBy(delta, overdrag = true) }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(with(density) { ctl.fullPx.toDp() })
                // Placed at its offset (as `offset {}` did); pulled past FULL it also grows by the
                // same amount so its bottom stays on the screen edge. Read in the layout phase only:
                // dragging and animating never recompose the sheet, and only the overdrag remeasures
                // it. At rest this is the plain FULL-height box.
                .layout { measurable, constraints ->
                    val stretch = if (constraints.hasBoundedHeight) ctl.stretchPx else 0
                    val placeable = measurable.measure(
                        constraints.copy(
                            minHeight = constraints.minHeight + stretch,
                            maxHeight = constraints.maxHeight + stretch,
                        ),
                    )
                    layout(placeable.width, placeable.height - stretch) {
                        placeable.placeRelativeWithLayer(0, ctl.shown().roundToInt())
                    }
                }
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
