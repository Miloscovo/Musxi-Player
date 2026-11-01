package tech.musxi.player.lab

import androidx.activity.BackEventCompat
import androidx.compose.foundation.lazy.LazyListState
import tech.musxi.player.ui.theme.Motion

/** The three root tabs of the shell. */
internal enum class LabTab(val label: String) { LIBRARY("曲库"), SEARCH("搜索"), PROFILE("我的") }

/** How the foreground leaves under the system back gesture. The product owner picks one. */
internal enum class BackStyle(val label: String) {
    /** iOS style: the page follows the finger to the right and the page below slides home. */
    FOLLOW("跟手推回"),

    /** Android style: the page shrinks, gains rounded corners and drifts toward the gesture. */
    SHRINK("缩小"),
}

/** A page in a tab's stack. [key] is unique inside its tab; it keys composition and scroll state. */
internal sealed class LabRoute(val key: String) {
    data class Root(val tab: LabTab) : LabRoute("root")
    data class Playlist(val index: Int) : LabRoute("playlist-$index")
    data class Artist(val name: String) : LabRoute("artist-$name")
    data class SearchResult(val keyword: String) : LabRoute("search-$keyword")
    data class Setting(val title: String) : LabRoute("setting-$title")
}

/** Deepest stack a tab allows: library goes playlist list, playlist, artist; the others one level. */
internal fun LabTab.maxDepth(): Int = if (this == LabTab.LIBRARY) 3 else 2

/**
 * Scroll positions that outlive the pages. A page leaving composition (tab switch, or sitting two
 * levels under the top) keeps its [LazyListState] here; a popped page is discarded so pushing it
 * again starts at the top, as on iOS.
 */
internal class LabScrollStore {
    private val states = HashMap<String, LazyListState>()

    fun stateFor(tab: LabTab, route: LabRoute): LazyListState =
        states.getOrPut("${tab.name}/${route.key}") { LazyListState() }

    fun discard(tab: LabTab, route: LabRoute) {
        states.remove("${tab.name}/${route.key}")
    }
}

/**
 * Foreground page transform. [translationX] is a share of the page width, [cornerDp] a corner
 * radius, [edgeShadow] the strength (0 to 1) of the faint shadow on the left edge, and [lift] the
 * strength of the all-round shadow that gives the shrunken card some depth.
 */
internal data class ForegroundPose(
    val translationX: Float,
    val scale: Float,
    val cornerDp: Float,
    val edgeShadow: Float,
    val lift: Float,
)

/** Page-below transform. [shiftX] is a share of the page width (zero or negative), [dim] a black alpha. */
internal data class UnderlayPose(val shiftX: Float, val dim: Float)

/**
 * One scalar, the foreground's exit `e`, drives the whole transition: 0 is the page at rest on top,
 * 1 is the page gone. A push animates e from 1 to 0, a pop from 0 to 1, a back gesture sets it
 * frame by frame and then lets a spring finish it. Keeping one scalar means any hand-over (grab a
 * running push, cancel a gesture, reverse a pop) continues from the current value without a jump.
 */
internal object LabPose {
    /** In SHRINK the finger drives e from 0 to this; the release slides the page out the rest. */
    const val ShrinkPhase = 0.4f
    const val ShrinkScale = 0.9f
    const val ShrinkCornerDp = 28f
    const val ShrinkDrift = 0.04f

    /** Direction of the page's travel for a swipe edge: the page follows the finger's way. */
    fun directionFor(swipeEdge: Int): Float = if (swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f

    fun foreground(exit: Float, style: BackStyle, direction: Float): ForegroundPose {
        val e = exit.coerceAtLeast(0f)
        return when (style) {
            // The page always leaves to the right, so the page below (shifted left) is never uncovered.
            BackStyle.FOLLOW -> ForegroundPose(e, 1f, 0f, 1f, 0f)
            BackStyle.SHRINK -> {
                val a = (e / ShrinkPhase).coerceIn(0f, 1f)
                val b = ((e - ShrinkPhase) / (1f - ShrinkPhase)).coerceIn(0f, 1f)
                ForegroundPose(
                    translationX = direction * (ShrinkDrift * a + (1f - ShrinkDrift) * b),
                    scale = 1f - (1f - ShrinkScale) * a,
                    cornerDp = ShrinkCornerDp * a,
                    edgeShadow = 0f,
                    lift = a * (1f - b),
                )
            }
        }
    }

    fun underlay(exit: Float, style: BackStyle): UnderlayPose {
        val e = exit.coerceIn(0f, 1f)
        val home = when (style) {
            BackStyle.FOLLOW -> e
            // The shrunken card exposes the page's edges, so the page below is home by half of phase one.
            BackStyle.SHRINK -> (e / (ShrinkPhase * 0.5f)).coerceIn(0f, 1f)
        }
        return UnderlayPose(
            shiftX = -Motion.PushUnderlayShift * (1f - home),
            dim = Motion.PushUnderlayDim * (1f - e),
        )
    }

    /** Maps the system's gesture [progress] (0 to 1) to the exit value, starting from [start]. */
    fun exitAt(style: BackStyle, start: Float, progress: Float): Float {
        val reach = maxOf(if (style == BackStyle.FOLLOW) 1f else ShrinkPhase, start)
        return start + (reach - start) * progress.coerceIn(0f, 1f)
    }

    /** Exit units per unit of gesture progress; turns progress velocity into exit velocity. */
    fun exitPerProgress(style: BackStyle, start: Float): Float =
        maxOf(if (style == BackStyle.FOLLOW) 1f else ShrinkPhase, start) - start
}
