package tech.musxi.player.lab

import android.os.SystemClock
import androidx.activity.BackEventCompat
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The exit value is animated in thousandths. A spring stops once it is within 0.01 of its target,
 * which on a 0 to 1 scale is about ten pixels and shows as a jump at the very end; in thousandths
 * the same threshold is a hundredth of a pixel.
 */
private const val ExitUnits = 1000f

/** A back-gesture sample older than this no longer says how fast the finger was moving. */
private const val FreshMillis = 100L

/** Page widths per second; keeps a flung release from throwing the spring. */
private const val MaxExitVelocity = 6f

/**
 * Navigation state of one tab: its page stack and the transition of the top page, whose exit value
 * (see [LabPose]) is the only thing that moves. Animations run in [scope], which belongs to the
 * whole experiment rather than to a page, so switching tabs mid-transition lets it finish unseen.
 *
 * Everything is interruptible: each new animation or gesture cancels the running one and continues
 * from the current exit value and velocity, so nothing replays or jumps.
 */
internal class LabTabNav(
    val tab: LabTab,
    private val scope: CoroutineScope,
    private val onDiscard: (LabRoute) -> Unit,
    initialStack: List<LabRoute> = listOf(LabRoute.Root(tab)),
    initialExit: Float = 0f,
    initialStyle: BackStyle = BackStyle.FOLLOW,
    initialDirection: Float = 1f,
) {
    val stack = mutableStateListOf<LabRoute>().apply { addAll(initialStack) }

    /** Exit of the top page, 0 at rest and 1 gone. Read it inside layer or draw blocks, not in composition. */
    var exit by mutableFloatStateOf(initialExit)
        private set

    /** Pose family the current transition uses; pushes and button pops are always [BackStyle.FOLLOW]. */
    var style by mutableStateOf(initialStyle)
        private set

    /** Which way a SHRINK page drifts: +1 for a swipe from the left edge, -1 from the right. */
    var direction by mutableFloatStateOf(initialDirection)
        private set

    /** True while a back gesture holds the page. */
    var dragging by mutableStateOf(false)
        private set

    val depth: Int get() = stack.size

    private var job: Job? = null
    private var velocity = 0f
    private var heading = 0f
    private var gestureId = 0

    private val settled: Boolean get() = !dragging && exit == 0f && job?.isActive != true

    /** Starts [target] with a spring from wherever the page is now; [onDone] runs only if it finishes. */
    private fun animateExit(target: Float, spec: AnimationSpec<Float>, initialVelocity: Float, onDone: () -> Unit = {}) {
        job?.cancel()
        heading = target
        job = scope.launch {
            animate(exit * ExitUnits, target * ExitUnits, initialVelocity * ExitUnits, spec) { value, v ->
                exit = value / ExitUnits
                velocity = v / ExitUnits
            }
            exit = target
            velocity = 0f
            onDone()
        }
    }

    /** Pushes [route] from the right. Ignored while another transition runs or the depth limit is met. */
    fun push(route: LabRoute, spec: AnimationSpec<Float>): Boolean {
        if (!settled || stack.size >= tab.maxDepth()) return false
        style = BackStyle.FOLLOW
        direction = 1f
        exit = 1f
        stack.add(route)
        animateExit(0f, spec, 0f)
        return true
    }

    /** Pops the top page. Pressed during a push, it reverses that push from where it is. */
    fun pop(spec: AnimationSpec<Float>) {
        if (dragging || stack.size <= 1) return
        val running = job?.isActive == true
        if (running && heading == 1f) return
        if (!running && exit == 0f) {
            style = BackStyle.FOLLOW
            direction = 1f
        }
        animateExit(1f, spec, velocity) { finishPop() }
    }

    /** Back to the tab's root page: pages in between vanish at once, so the root is what gets uncovered. */
    fun popToRoot(spec: AnimationSpec<Float>) {
        if (dragging || stack.size <= 1) return
        if (job?.isActive == true && heading == 1f) return
        while (stack.size > 2) onDiscard(stack.removeAt(1))
        pop(spec)
    }

    private fun finishPop() {
        if (stack.size > 1) onDiscard(stack.removeAt(stack.lastIndex))
        exit = 0f
        velocity = 0f
        style = BackStyle.FOLLOW
        direction = 1f
    }

    /** Exit units per second at release, from the smoothed progress velocity of the last samples. */
    private fun releaseVelocity(style: BackStyle, start: Float, progressVelocity: Float, lastAt: Long): Float {
        if (lastAt == 0L || SystemClock.uptimeMillis() - lastAt > FreshMillis) return 0f
        return (progressVelocity * LabPose.exitPerProgress(style, start)).coerceIn(-MaxExitVelocity, MaxExitVelocity)
    }

    /**
     * Follows one system back gesture. [events] ends normally when the system commits and throws a
     * [CancellationException] when it cancels, as PredictiveBackHandler delivers it. Without any
     * progress event (back button, three-button navigation) the pop plays as an ordinary transition.
     * The finish and the spring-back run in [scope], so they outlive this call and stay grabbable.
     */
    suspend fun handleBack(
        events: Flow<BackEventCompat>,
        preferred: BackStyle,
        release: () -> AnimationSpec<Float>,
        standard: () -> AnimationSpec<Float>,
    ) {
        if (stack.size <= 1) {
            events.collect { }
            return
        }
        val id = ++gestureId
        var begun = false
        var start = 0f
        var progress = 0f
        var progressVelocity = 0f
        var lastAt = 0L
        try {
            events.collect { event ->
                val now = SystemClock.uptimeMillis()
                if (!begun) {
                    begun = true
                    val wasSettled = exit == 0f && job?.isActive != true
                    job?.cancel()
                    job = null
                    dragging = true
                    velocity = 0f
                    start = exit
                    // A transition already under way keeps its pose family, so grabbing it does not jump.
                    if (wasSettled) {
                        style = preferred
                        direction = LabPose.directionFor(event.swipeEdge)
                    }
                }
                if (lastAt != 0L && now > lastAt) {
                    val instant = (event.progress - progress) / ((now - lastAt) / 1000f)
                    progressVelocity = if (progressVelocity == 0f) instant else progressVelocity * 0.5f + instant * 0.5f
                }
                progress = event.progress
                lastAt = now
                exit = LabPose.exitAt(style, start, progress)
            }
            if (!begun) {
                pop(standard())
                return
            }
            dragging = false
            animateExit(1f, release(), releaseVelocity(style, start, progressVelocity, lastAt)) { finishPop() }
        } catch (cancelled: CancellationException) {
            // Only the newest gesture springs back; an older one was cut off by the gesture that replaced it.
            if (begun && id == gestureId) {
                dragging = false
                animateExit(0f, release(), releaseVelocity(style, start, progressVelocity, lastAt))
            }
            throw cancelled
        }
    }
}
