package tech.musxi.player.ui.nav

import android.os.SystemClock
import androidx.activity.BackEventCompat
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import tech.musxi.player.ui.theme.Motion

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

/** What tapping the tab that is already showing does (spec section 7). */
enum class ReselectAction {
    /** The stack is deeper than the root: go back to the root page. */
    POP_TO_ROOT,

    /** Already at the root: the caller scrolls its list to the top. */
    SCROLL_TO_TOP,
}

/** The rule behind [NavStackState.reselect], on its own so it can be tested without a transition. */
fun reselectActionFor(depth: Int): ReselectAction =
    if (depth > 1) ReselectAction.POP_TO_ROOT else ReselectAction.SCROLL_TO_TOP

/**
 * Navigation state of one tab: its [PageStack] and the transition of the top page, whose exit
 * value (see [NavPose]) is the only thing that moves. Animations run in a scope that belongs to
 * the caller of [rememberNavStackState] rather than to a page, so leaving the tab mid-transition
 * lets the transition finish unseen.
 *
 * Everything is interruptible: each new animation or gesture cancels the running one and
 * continues from the current exit value and velocity, so nothing replays or jumps.
 *
 * Main thread only, like any Compose state.
 */
class NavStackState internal constructor(
    private val scope: CoroutineScope,
    /** Saved state (scroll position and other `rememberSaveable` values) of pages that are not composed. */
    internal val pageState: SaveableStateHolder,
    initialStack: PageStack,
    initialExit: Float = 0f,
    initialStyle: BackStyle = BackStyle.FOLLOW,
    initialDirection: Float = 1f,
) {
    /** The pages, bottom to top. Reading it in composition subscribes to pushes and pops. */
    var stack by mutableStateOf(initialStack)
        private set

    /** Number of pages, root included. */
    val depth: Int get() = stack.depth

    /** Exit of the top page, 0 at rest and 1 gone. Read it inside layer or draw blocks, not in composition. */
    internal var exit by mutableFloatStateOf(initialExit)
        private set

    /** Pose family the current transition uses; pushes and button pops are always [BackStyle.FOLLOW]. */
    internal var style by mutableStateOf(initialStyle)
        private set

    /** Which way a [BackStyle.SHRINK] page drifts: +1 for a swipe from the left edge, -1 from the right. */
    internal var direction by mutableFloatStateOf(initialDirection)
        private set

    /** True while a back gesture holds the page. */
    internal var dragging by mutableStateOf(false)
        private set

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

    /**
     * Pushes [key] from the right with the standard spring. Refused (false) while another
     * transition runs, when [PageStack.maxDepth] is met, or when [key] is already on the stack.
     */
    fun push(key: String): Boolean {
        if (!settled || !stack.canPush(key)) return false
        style = BackStyle.FOLLOW
        direction = 1f
        exit = 1f
        stack = stack.push(key)
        animateExit(0f, Motion.standard<Float>(), 0f)
        return true
    }

    /**
     * Pops the top page with the standard spring; false when there is nothing to pop, a back
     * gesture holds the page, or it is already leaving. Pressed during a push, it reverses that
     * push from where it is.
     */
    fun pop(): Boolean {
        if (dragging || stack.isRoot) return false
        val running = job?.isActive == true
        if (running && heading == 1f) return false
        if (!running && exit == 0f) {
            style = BackStyle.FOLLOW
            direction = 1f
        }
        animateExit(1f, Motion.standard<Float>(), velocity) { finishPop() }
        return true
    }

    /** Back to the root page: pages in between vanish at once, so the root is what gets uncovered. */
    fun popToRoot(): Boolean {
        if (dragging || stack.isRoot) return false
        if (job?.isActive == true && heading == 1f) return false
        val reduced = stack.withoutMiddle()
        stack.keys.filter { it !in reduced.keys }.forEach { pageState.removeState(it) }
        stack = reduced
        return pop()
    }

    /**
     * Handles a tap on the tab that is already showing: a deep stack goes back to its root with the
     * usual transition; at the root, [scrollToTop] runs so the caller can scroll its list up. The
     * root page's list state must therefore live in the caller, above the host.
     */
    fun reselect(scrollToTop: () -> Unit): ReselectAction {
        val action = reselectActionFor(depth)
        when (action) {
            ReselectAction.POP_TO_ROOT -> popToRoot()
            ReselectAction.SCROLL_TO_TOP -> scrollToTop()
        }
        return action
    }

    private fun finishPop() {
        if (!stack.isRoot) {
            pageState.removeState(stack.top)
            stack = stack.pop()
        }
        exit = 0f
        velocity = 0f
        style = BackStyle.FOLLOW
        direction = 1f
    }

    /** Exit units per second at release, from the smoothed progress velocity of the last samples. */
    private fun releaseVelocity(style: BackStyle, start: Float, progressVelocity: Float, lastAt: Long): Float {
        if (lastAt == 0L || SystemClock.uptimeMillis() - lastAt > FreshMillis) return 0f
        return (progressVelocity * NavPose.exitPerProgress(style, start)).coerceIn(-MaxExitVelocity, MaxExitVelocity)
    }

    /**
     * Follows one system back gesture. [events] ends normally when the system commits and throws a
     * [CancellationException] when it cancels, as PredictiveBackHandler delivers it. Without any
     * progress event (back button, three-button navigation) the pop plays as an ordinary transition.
     * The finish and the spring-back run in the state's scope, so they outlive this call and stay
     * grabbable.
     */
    internal suspend fun handleBack(events: Flow<BackEventCompat>, preferred: BackStyle) {
        if (stack.isRoot) {
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
                        direction = NavPose.directionFor(event.swipeEdge)
                    }
                }
                if (lastAt != 0L && now > lastAt) {
                    val instant = (event.progress - progress) / ((now - lastAt) / 1000f)
                    progressVelocity = if (progressVelocity == 0f) instant else progressVelocity * 0.5f + instant * 0.5f
                }
                progress = event.progress
                lastAt = now
                exit = NavPose.exitAt(style, start, progress)
            }
            if (!begun) {
                pop()
                return
            }
            dragging = false
            animateExit(1f, Motion.release<Float>(), releaseVelocity(style, start, progressVelocity, lastAt)) { finishPop() }
        } catch (cancelled: CancellationException) {
            // Only the newest gesture springs back; an older one was cut off by the gesture that replaced it.
            if (begun && id == gestureId) {
                dragging = false
                animateExit(0f, Motion.release<Float>(), releaseVelocity(style, start, progressVelocity, lastAt))
            }
            throw cancelled
        }
    }
}

/**
 * Creates the [NavStackState] of one tab. Call it once per tab, at the same place on every
 * composition and outside any `when (tab)` that composes only the selected tab, so the stack and
 * the scroll positions of tabs that are not showing survive. The stack is saved across
 * configuration changes and process death as its list of keys; a restored stack is at rest.
 *
 * @param root Key of the tab's root page.
 * @param maxDepth Most pages the tab may hold, root included.
 */
@Composable
fun rememberNavStackState(root: String, maxDepth: Int = PageStack.DefaultMaxDepth): NavStackState {
    val scope = rememberCoroutineScope()
    val holder = rememberSaveableStateHolder()
    return rememberSaveable(
        root,
        maxDepth,
        saver = listSaver<NavStackState, String>(
            save = { it.stack.toSaved() },
            restore = { NavStackState(scope, holder, PageStack.restore(it, root, maxDepth)) },
        ),
    ) { NavStackState(scope, holder, PageStack.of(root, maxDepth)) }
}
