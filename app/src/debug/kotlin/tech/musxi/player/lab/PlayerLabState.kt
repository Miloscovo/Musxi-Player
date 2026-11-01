package tech.musxi.player.lab

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tech.musxi.player.ui.theme.Gestures
import tech.musxi.player.ui.theme.Haptic

/**
 * All player state. [p] is the one progress value (0 = mini bar, 1 = full screen) that drives the
 * surface, the cover and the control fades; [modeP] does the same for the cover-to-title-row move.
 * The fields marked "from composition" are refreshed on every pass so gestures never use stale values.
 */
internal class PlayerLabState(initial: PlayerLabInitial, val scope: CoroutineScope) : AxisDragHandler {
    val p = Animatable(initial.progress.coerceIn(0f, 1f))
    val modeP = Animatable(if (initial.mode == PlayerMode.COVER) 0f else 1f)
    var mode by mutableStateOf(initial.mode)
    /** Mode content stays drawn while it fades out, so it is remembered after [mode] returns to COVER. */
    var shown by mutableStateOf(if (initial.mode == PlayerMode.COVER) PlayerMode.LYRICS else initial.mode)
    var backdrop by mutableStateOf(initial.backdrop)
    var queueStyle by mutableStateOf(initial.queueStyle)
    var index by mutableIntStateOf(initial.songIndex)
    var playing by mutableStateOf(initial.playing)
    var sheetVisible by mutableStateOf(false)
    var optionsOpen by mutableStateOf(false)
    /** Selected fake tab in the dock (0 = 曲库). */
    var tab by mutableIntStateOf(0)
    /** Horizontal cover offset in big-cover pixels; cover n sits at (n - index) * slot + swipe. */
    var swipe by mutableFloatStateOf(0f)

    // From composition.
    var songs: List<LabSong> = emptyList()
    var tuning = LabTuning.STANDARD
    var haptic: (Haptic) -> Unit = {}
    var reduced = false
    var density = 1f
    lateinit var layout: PlayerLayout

    val song: LabSong get() = songs[index.coerceIn(0, songs.lastIndex)]
    val songCount: Int get() = songs.size

    /** Composition only sees these flip, not every frame of the animation. */
    val miniVisible by derivedStateOf { p.value < 0.3f }
    val fullVisible by derivedStateOf { p.value > 0.5f }
    /** Lyrics / queue content is composed while a mode is on or still fading out. */
    val modeVisible by derivedStateOf { mode != PlayerMode.COVER || modeP.value > 0.001f }
    val backEnabled by derivedStateOf {
        sheetVisible || mode != PlayerMode.COVER || p.value > 0.001f || p.targetValue > 0.001f
    }

    /** Draw-phase fades (spec section 8): mini controls gone by p = 0.3, full controls in from p = 0.5. */
    fun miniAlpha(): Float = (1f - p.value / 0.3f).coerceIn(0f, 1f)
    fun fullAlpha(): Float = ((p.value - 0.5f) / 0.3f).coerceIn(0f, 1f)
    fun backdropAlpha(): Float {
        val t = ((p.value - 0.05f) / 0.5f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // Vertical pull (expand / collapse).
    var pullTotal = 0f
    private var pullBase = 0f
    private var pullValue = 0f
    private var pullFromExpanded = false
    private var pullCrossed = false

    fun pullBegin() {
        pullBase = p.value
        pullValue = pullBase
        pullTotal = 0f
        pullFromExpanded = pullBase > 0.5f
        pullCrossed = false
    }

    /** [dy] is finger movement in px, positive downwards; progress follows the finger one to one. */
    fun pullBy(dy: Float) {
        pullTotal += dy
        pullValue = (pullBase - pullTotal / layout.travel).coerceIn(0f, 1f)
        val target = pullValue
        scope.launch(start = CoroutineStart.UNDISPATCHED) { p.snapTo(target) }
        // One buzz per drag: the first time it passes the completion point, never again.
        if (!pullCrossed && Gestures.playerCompletes(towardProgress(pullValue), 0f)) {
            pullCrossed = true
            haptic(Haptic.THRESHOLD)
        }
    }

    private fun towardProgress(value: Float) = if (pullFromExpanded) 1f - value else value

    fun pullEnd(velocityY: Float) {
        val progressVelocity = -velocityY / layout.travel
        val toward = if (pullFromExpanded) -progressVelocity else progressVelocity
        val completes = Gestures.playerCompletes(towardProgress(pullValue), toward)
        if (completes && !pullCrossed) haptic(Haptic.THRESHOLD)
        val target = if (completes == !pullFromExpanded) 1f else 0f
        animateTo(target, progressVelocity, release = true)
    }

    fun animateTo(target: Float, velocity: Float = 0f, release: Boolean = false) {
        scope.launch {
            val spec: FiniteAnimationSpec<Float> = if (release) tuning.release() else tuning.standard()
            p.animateTo(target, spec, velocity)
            if (target == 0f) resetModeWhenDocked()
        }
    }

    fun expand() = animateTo(1f)
    fun collapse() = animateTo(0f)

    private fun resetModeWhenDocked() {
        if (mode != PlayerMode.COVER) {
            mode = PlayerMode.COVER
            scope.launch { modeP.snapTo(0f) }
        }
    }

    fun toggleMode(target: PlayerMode) {
        val next = if (mode == target) PlayerMode.COVER else target
        mode = next
        if (next != PlayerMode.COVER) shown = next
        scope.launch { modeP.animateTo(if (next == PlayerMode.COVER) 0f else 1f, tuning.standard()) }
    }

    /** Back key: sheet first, then the mode, then the player itself. */
    fun back() {
        when {
            sheetVisible -> sheetVisible = false
            mode != PlayerMode.COVER -> toggleMode(mode)
            else -> collapse()
        }
    }

    // Axis-locked drag from the surface.
    override fun allows(vertical: Boolean) = vertical || coverInteractive()
    override fun onLock(vertical: Boolean) = if (vertical) pullBegin() else swipeBegin()
    override fun onDelta(vertical: Boolean, delta: Float) = if (vertical) pullBy(delta) else swipeBy(delta)
    override fun onEnd(vertical: Boolean, velocity: Float) = if (vertical) pullEnd(velocity) else swipeEnd(velocity)
    override fun onCancel(vertical: Boolean) = onEnd(vertical, 0f)

    /** True while the big cover is on screen and can be swiped. */
    fun coverInteractive() = p.value > 0.95f && modeP.value < 0.05f

    internal var swipeJob: Job? = null
    internal var swipeRaw = 0f
}
