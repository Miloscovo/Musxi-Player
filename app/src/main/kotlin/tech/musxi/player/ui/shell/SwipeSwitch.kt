package tech.musxi.player.ui.shell

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tech.musxi.player.ui.theme.Gestures
import tech.musxi.player.ui.theme.Motion
import kotlin.math.abs
import kotlin.math.sign

/**
 * The numbers of the sideways swipe between the library's segments and the tabs (task 1). They are
 * the knobs to turn after trying it on the phone, so they live in one place.
 */
object SwipeTuning {
    /** Share of the finger's travel the page follows; the rest of the move is the fade. */
    const val Follow = 0.4f
    /** How much the page fades at a full-width drag. */
    const val FadeAtFull = 0.5f
    /** Share of the width a release must reach (after look-ahead) to switch platform. */
    const val SegmentThreshold = 0.2f
    /** The same for leaving the tab, past the last platform or between tabs: a firmer swipe. */
    const val TabThreshold = 0.3f
    /** Seconds of release velocity added to the travel before the threshold is checked. */
    const val LookAhead = 0.15f
    /** Where the old page goes and the new one comes from, as a share of the width. */
    const val OutTravel = 0.18f
    const val InTravel = 0.12f
    /** A drag must lean this much more sideways than up or down before it is a swipe. */
    const val Slant = 1.5f
    /** With nothing on that side the page only gives this share of the width, rubber-banded. */
    const val EdgeGive = 0.08f
}

/** Which way a swipe goes: [NEXT] is the finger moving left, toward the page on the right. */
enum class SwipeDir(val step: Int) { PREVIOUS(-1), NEXT(1) }

/**
 * The page's sideways position while it is swiped. The host says what is on either side
 * ([canGo]), whether going there leaves the tab ([leavesTab]), and does the switch ([go]).
 */
@Stable
class SwipeSwitchState internal constructor(private val scope: CoroutineScope) {
    var canGo: (SwipeDir) -> Boolean = { false }
    var leavesTab: (SwipeDir) -> Boolean = { false }
    var go: (SwipeDir) -> Unit = {}
    var onCommit: () -> Unit = {}
    var reduced = false
    /** Read when a finger lands on the page; turning it off mid-drag lets that drag finish and settle. */
    var enabled = false
    /** The same for the dock (tab bar, folded tab and mini row), which only walks the tabs. */
    var dockEnabled = false

    /** The drag under way started on the dock: it skips the library's segments. */
    var tabsOnly = false
        private set

    /** Drawn: the page's shift in px and its opacity. */
    var shift by mutableFloatStateOf(0f)
        private set
    var opacity by mutableFloatStateOf(1f)
        private set

    internal var width = 1f
    private var raw = 0f
    private var settling: Job? = null
    /** True from a commit until the new page has come in: a touch then does not start another. */
    var switching = false
        private set

    /** A drag starts; [tabsOnly] for one that began on the dock. */
    fun begin(tabsOnly: Boolean) {
        this.tabsOnly = tabsOnly
        settling?.cancel()
        // Picks up from where a springing-back page is.
        raw = shift / SwipeTuning.Follow
    }

    fun dragBy(dx: Float) {
        raw += dx
        val dir = dirOf(raw) ?: run { shift = 0f; opacity = 1f; return }
        if (canGo(dir)) {
            shift = raw * SwipeTuning.Follow
            opacity = 1f - SwipeTuning.FadeAtFull * (abs(raw) / width).coerceAtMost(1f)
        } else {
            shift = raw.sign * Gestures.rubberBand(abs(raw), width * SwipeTuning.EdgeGive)
            opacity = 1f
        }
    }

    /** [velocity] in px per second, positive to the right. */
    fun release(velocity: Float) {
        val dir = dirOf(raw)
        val commit = dir != null && canGo(dir) && Gestures.completes(
            progress = abs(raw) / width,
            velocity = -velocity * dir.step / width,
            lookAheadSeconds = SwipeTuning.LookAhead,
            threshold = if (leavesTab(dir)) SwipeTuning.TabThreshold else SwipeTuning.SegmentThreshold,
        )
        raw = 0f
        settling = scope.launch {
            if (commit && dir != null) switchTo(dir) else settleBack()
        }
    }

    private suspend fun settleBack() {
        val from = shift
        val fromOpacity = opacity
        animate(0f, 1f, animationSpec = Motion.release()) { t, _ ->
            shift = from * (1f - t)
            opacity = fromOpacity + (1f - fromOpacity) * t
        }
    }

    private suspend fun switchTo(dir: SwipeDir) {
        switching = true
        try {
            onCommit()
            if (reduced) {
                go(dir)
                shift = 0f
                opacity = 1f
                return
            }
            val from = shift
            val fromOpacity = opacity
            val out = -dir.step * width * SwipeTuning.OutTravel
            animate(0f, 1f, animationSpec = Motion.exit()) { t, _ ->
                shift = from + (out - from) * t
                opacity = fromOpacity * (1f - t)
            }
            go(dir)
            val into = dir.step * width * SwipeTuning.InTravel
            shift = into
            animate(0f, 1f, animationSpec = Motion.release()) { t, _ ->
                shift = into * (1f - t)
                opacity = t.coerceIn(0f, 1f)
            }
        } finally {
            shift = 0f
            opacity = 1f
            switching = false
        }
    }

    private fun dirOf(raw: Float): SwipeDir? = when {
        raw < 0f -> SwipeDir.NEXT
        raw > 0f -> SwipeDir.PREVIOUS
        else -> null
    }
}

@Composable
fun rememberSwipeSwitchState(): SwipeSwitchState {
    val scope = rememberCoroutineScope()
    return remember(scope) { SwipeSwitchState(scope) }
}

/**
 * Takes sideways drags that nothing inside claimed: a horizontal strip, a slider or a text field
 * consumes its own drag and keeps it, and a drag that turns vertical first stays a scroll. Read
 * after the children (main pass), so it only ever gets what they left.
 *
 * On the pages it also measures the width the thresholds are shares of. On the dock ([tabsOnly])
 * it walks the tabs only and leaves the width to the pages.
 */
fun Modifier.swipeSwitch(state: SwipeSwitchState, tabsOnly: Boolean = false): Modifier =
    (if (tabsOnly) this else onSizeChanged { state.width = it.width.toFloat().coerceAtLeast(1f) })
        .pointerInput(state, tabsOnly) { detectSwipe(state, tabsOnly) }

/** The page under a [swipeSwitch]: moved and faded with it. Apply inside, after the detector. */
fun Modifier.swipeSwitchPage(state: SwipeSwitchState): Modifier = graphicsLayer {
    translationX = state.shift
    alpha = state.opacity
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectSwipe(state: SwipeSwitchState, tabsOnly: Boolean) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
        val enabled = if (tabsOnly) state.dockEnabled else state.enabled
        if (!enabled || state.switching) return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var dx = 0f
        var dy = 0f
        // Deciding: a child that takes the drag, a second finger or a vertical lean ends it.
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            if (event.changes.size > 1) return@awaitEachGesture
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed || change.isConsumed) return@awaitEachGesture
            val d = change.positionChange()
            dx += d.x
            dy += d.y
            tracker.addPosition(change.uptimeMillis, change.position)
            if (abs(dy) > slop && abs(dy) * SwipeTuning.Slant >= abs(dx)) return@awaitEachGesture
            if (abs(dx) > slop && abs(dx) > abs(dy) * SwipeTuning.Slant) {
                change.consume()
                break
            }
        }
        state.begin(tabsOnly)
        // Dragging: the page is ours until the finger lifts.
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null || !change.pressed) break
            state.dragBy(change.positionChange().x)
            tracker.addPosition(change.uptimeMillis, change.position)
            change.consume()
        }
        state.release(tracker.calculateVelocity().x)
    }
}

/**
 * Where a swipe leads from the [tab] showing, on library segment [segment] of [segments]: the next
 * library segment, or across to the neighbouring tab past the last one. Back from 搜索 lands on the
 * library as it was left. Null when there is nothing on that side. With [tabsOnly] (a swipe on the
 * dock, or a library locked to one platform) the library is one stop and only the tabs change.
 */
internal sealed interface SwipeTarget {
    data class Segment(val index: Int) : SwipeTarget
    data class Tab(val tab: ShellTab) : SwipeTarget
}

internal fun swipeTarget(
    tab: ShellTab,
    segment: Int,
    segments: Int,
    dir: SwipeDir,
    tabsOnly: Boolean = false,
): SwipeTarget? = when (tab) {
    ShellTab.LIBRARY -> {
        val to = segment + dir.step
        when {
            !tabsOnly && to in 0 until segments -> SwipeTarget.Segment(to)
            dir == SwipeDir.NEXT -> SwipeTarget.Tab(ShellTab.SEARCH)
            else -> null
        }
    }
    ShellTab.SEARCH -> SwipeTarget.Tab(if (dir == SwipeDir.NEXT) ShellTab.ME else ShellTab.LIBRARY)
    ShellTab.ME -> if (dir == SwipeDir.PREVIOUS) SwipeTarget.Tab(ShellTab.SEARCH) else null
}
