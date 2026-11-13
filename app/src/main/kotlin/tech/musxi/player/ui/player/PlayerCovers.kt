package tech.musxi.player.ui.player

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.theme.Gestures
import tech.musxi.player.ui.theme.Haptic
import tech.musxi.player.ui.theme.Motion
import kotlin.math.abs
import kotlin.math.roundToInt

/** What one cover needs to be drawn: the placeholder seed and the artwork address. */
internal data class CoverRef(val seed: String, val url: String?)

/**
 * Horizontal cover swipe. Cover n sits at `(n - anchor) * slot + offset`. When the queue index moves
 * by one, `anchor` follows and `offset` takes up `slot`, so no cover changes place: the commit is
 * invisible and the settle animation simply runs `offset` back to zero.
 */
@Stable
internal class CoverSwipe(private val scope: CoroutineScope, start: Int) : AxisDragHandler {
    var offset by mutableFloatStateOf(0f)
        private set
    var anchor by mutableIntStateOf(start)
        private set

    // From composition; read only when a gesture or an index change happens.
    var slot = 1f
    var coverWidth = 1f
    var density = 1f
    var reduced = false
    var hasPrevious = false
    var hasNext = false
    var previous: CoverRef? = null
    var next: CoverRef? = null
    var haptic: (Haptic) -> Unit = {}
    var onNext: () -> Unit = {}
    var onPrevious: () -> Unit = {}
    var interactive: () -> Boolean = { false }
    var verticalDrag: AxisDragHandler? = null

    private var raw = 0f
    private var dragging = false
    // The queue moved under the finger: the offset is then measured from a new anchor, so this
    // release only lets the covers settle and never changes song.
    private var rebasedInDrag = false
    private var sliding = 0
    private var velocity = 0f
    private var job: Job? = null

    /**
     * A finger or a committed slide is moving the covers. Only then may the playing cover sit away
     * from the anchor; when the queue moves on its own (a song ended) it must appear in place at once.
     */
    fun following(): Boolean = dragging || sliding != 0

    /** The queue moved to [newIndex]: follow it without a visible jump. */
    fun indexChanged(newIndex: Int) {
        val d = newIndex - anchor
        if (d == 0) return
        if (abs(d) == 1 && (sliding == d || dragging)) {
            anchor = newIndex
            offset += d * slot
            raw += d * slot
            if (dragging) rebasedInDrag = true
            if (!dragging) {
                sliding = 0
                settle(velocity)
            }
        } else {
            job?.cancel()
            sliding = 0
            anchor = newIndex
            offset = 0f
            raw = 0f
        }
    }

    // Horizontal drags are ours; vertical ones belong to the player pull.
    override fun allows(vertical: Boolean, down: Offset): Boolean =
        if (vertical) verticalDrag?.allows(true, down) == true else interactive()

    override fun onLock(vertical: Boolean) {
        if (vertical) {
            verticalDrag?.onLock(true)
        } else {
            job?.cancel()
            sliding = 0
            dragging = true
            rebasedInDrag = false
            raw = offset
        }
    }

    override fun onDelta(vertical: Boolean, delta: Float) {
        if (vertical) {
            verticalDrag?.onDelta(true, delta)
        } else {
            raw += delta
            offset = PlayerMotion.swipeOffset(raw, hasPrevious, hasNext, reduced)
        }
    }

    override fun onEnd(vertical: Boolean, velocity: Float) {
        if (vertical) {
            verticalDrag?.onEnd(true, velocity)
            return
        }
        dragging = false
        if (rebasedInDrag) {
            rebasedInDrag = false
            settle(velocity)
            return
        }
        val dir = PlayerMotion.swipeDirection(
            offset, velocity, coverWidth, Gestures.FlingDpPerSecond * density, hasPrevious, hasNext,
        )
        if (dir == 0) settle(velocity) else commit(dir, velocity)
    }

    override fun onCancel(vertical: Boolean) = onEnd(vertical, 0f)

    private fun commit(dir: Int, velocity: Float) {
        haptic(Haptic.TICK)
        val known = (if (dir > 0) next else previous) != null
        if (dir > 0) onNext() else onPrevious()
        job?.cancel()
        if (!known || reduced) {
            // Nothing to slide to (shuffle, wrap-around): the cover returns and the new song replaces it.
            settle(velocity)
            return
        }
        sliding = dir
        job = scope.launch {
            animate(offset, -dir * slot, velocity, Motion.release()) { value, v -> offset = value; this@CoverSwipe.velocity = v }
            // The new song should be playing by now; if it never arrives the old cover comes back.
            delay(SlideTimeoutMillis)
            sliding = 0
            settle(0f)
        }
    }

    private fun settle(velocity: Float) {
        job?.cancel()
        job = scope.launch {
            animate(offset, 0f, velocity, Motion.release()) { value, v -> offset = value; this@CoverSwipe.velocity = v }
        }
    }

    private companion object {
        const val SlideTimeoutMillis = 2_500L
    }
}

internal fun Shape.radiusPx(density: Density): Float =
    (this as? CornerBasedShape)?.topStart?.toPx(Size(1000f, 1000f), density) ?: 0f

/**
 * The cover, drawn once. Every cover is laid out at the page's cover size and moved with a graphics
 * layer: scale and translation come straight from the interpolated rectangle, so a finger can take
 * over at any moment and nothing is laid out again. The neighbours exist only for the swipe.
 */
@Composable
internal fun PlayerCoverStack(
    progress: PlayerProgress,
    swipe: CoverSwipe,
    frame: PlayerFrame,
    probe: PlayerProbe,
    index: Int,
    current: CoverRef,
    /** Task 2: 0 on the cover page, 1 on the lyrics page, where the cover is the title bar's tile. */
    lyricsT: State<Float>,
) {
    val fade = { lyricsT.value }
    val showNeighbours = progress.covering
    // Centred on the playing song, not on the anchor: the anchor trails the queue by one frame.
    for (i in index - 1..index + 1) {
        val ref = when (i) {
            index -> current
            index - 1 -> swipe.previous
            index + 1 -> swipe.next
            else -> null
        } ?: continue
        if (i != index && !showNeighbours) continue
        key(i) { CoverLayer(i, i == index, ref, progress, swipe, frame, probe, fade) }
    }
}

@Composable
private fun CoverLayer(
    i: Int,
    isCurrent: Boolean,
    ref: CoverRef,
    progress: PlayerProgress,
    swipe: CoverSwipe,
    frame: PlayerFrame,
    probe: PlayerProbe,
    lyricsT: () -> Float,
) {
    val density = LocalDensity.current
    val miniRadius = MaterialTheme.shapes.medium.radiusPx(density)
    val fullRadius = MaterialTheme.shapes.extraLarge.radiusPx(density)
    Box(
        Modifier
            .layout { measurable, _ ->
                val side = probe.coverOr(frame).width.roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(side, side))
                layout(side, side) { placeable.place(0, 0) }
            }
            .graphicsLayer {
                val big = probe.coverOr(frame)
                val pp = progress.p.value.coerceIn(0f, 1f)
                val square = Rect(big.left, big.top, big.left + big.width, big.top + big.width)
                // On the lyrics page the cover is not hidden: it flies into the title bar's tile.
                val t = lyricsT().coerceIn(0f, 1f)
                val tile = probe.tile ?: LyricsTransition.fallbackTile(square, LyricsTransition.TileSize.toPx())
                val target = if (t > 0f) PlayerMotion.lerpRect(square, tile, t) else square
                val rect = PlayerMotion.lerpRect(frame.miniCover, target, pp)
                val scale = rect.width / big.width
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = scale
                scaleY = scale
                val rel = if (isCurrent && !swipe.following()) 0 else i - swipe.anchor
                translationX = rect.left + (rel * frame.slot + swipe.offset) * scale
                translationY = rect.top
                // Neighbours only show once the big cover is at rest size, and never beside the tile.
                alpha = if (isCurrent) 1f
                else ((pp - 0.9f) / 0.1f).coerceIn(0f, 1f) * LyricsTransition.neighbourAlpha(t)
                val restRadius = lerp(fullRadius, miniRadius, t)
                shape = RoundedCornerShape(lerp(miniRadius, restRadius, pp) / scale.coerceAtLeast(0.01f))
                clip = true
            },
    ) {
        Cover(seed = ref.seed, modifier = Modifier.fillMaxSize(), shape = RectangleShape, url = ref.url)
        // The large rendition fades in over the small one, like the page used to; only on the open page.
        val sharpUrl = hdCoverUrl(ref.url)
        if (progress.covering && !sharpUrl.isNullOrBlank() && sharpUrl != ref.url) {
            key(sharpUrl) { SharpCover(url = sharpUrl, modifier = Modifier.fillMaxSize(), shape = RectangleShape) }
        }
    }
}
