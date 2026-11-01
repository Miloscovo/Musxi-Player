package tech.musxi.player.ui.nav

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import tech.musxi.player.ui.theme.rememberReducedMotion

/**
 * Draws one tab's page stack: the top page and the page below it, nothing deeper. Pushes and pops
 * move the top page with the standard spring while the page below shifts and dims (spec section 7),
 * and the system back gesture drives the same transition through `PredictiveBackHandler`; the
 * system decides commit or cancel, a commit finishes with the release spring and a cancel springs
 * back. With only the root on the stack the host does not intercept back at all.
 *
 * Pages are keyed by their route key, so a page that stops being the top one keeps its
 * composition and only changes its transform. A page two levels down, or in a tab that is not
 * showing, leaves composition but its `rememberSaveable` state (scroll positions of
 * `rememberLazyListState` included) is kept and restored when it returns. A popped page's state is
 * dropped, so pushing it again starts at the top. The root page's list state is the exception that
 * [NavStackState.reselect] needs: hoist it above the host and pass it into the root page.
 *
 * The page below is only ever seen, never touched, and stays out of the accessibility tree. It is
 * still composed, though, so a page that registers its own `BackHandler` must enable it only while
 * it is the top page.
 *
 * With the system "remove animations" setting on ([rememberReducedMotion]) the page below neither
 * shifts nor dims; the top page still slides, since that is the transition itself.
 *
 * @param stack State of this tab, from [rememberNavStackState]. Drive pushes and pops through it.
 * @param backStyle Which pose the top page takes under the system back gesture.
 * @param backEnabled False hands back to whatever is registered below this host, e.g. while the
 * full player covers the tabs. Hosts of tabs that are not showing should not be composed at all.
 * @param pageColor Opaque colour behind each page, so the page below never shows through.
 * @param content Draws the page for a route key. It runs once per composed page and must call
 * nothing on [stack] for its own sake; a back button inside a page calls [NavStackState.pop].
 */
// NoCollectCallFound is a false positive here: the back events are handed to
// NavStackState.handleBack, which collects them (Lint only looks inside the lambda).
@android.annotation.SuppressLint("NoCollectCallFound")
@Composable
fun NavStackHost(
    stack: NavStackState,
    modifier: Modifier = Modifier,
    backStyle: BackStyle = BackStyle.FOLLOW,
    backEnabled: Boolean = true,
    pageColor: Color = MaterialTheme.colorScheme.background,
    content: @Composable (key: String) -> Unit,
) {
    val reduced = rememberReducedMotion()
    val currentStyle by rememberUpdatedState(backStyle)

    // Only while there is a page to go back to; at the root the back press belongs to the caller.
    PredictiveBackHandler(enabled = backEnabled && stack.depth > 1) { events ->
        stack.handleBack(events, currentStyle)
    }

    Box(modifier.fillMaxSize().background(pageColor)) {
        val keys = stack.stack.keys
        val last = keys.lastIndex
        for (i in maxOf(0, last - 1)..last) {
            val pageKey = keys[i]
            key(pageKey) {
                NavPageLayer(stack, top = i == last, reduced = reduced, pageColor = pageColor) {
                    stack.pageState.SaveableStateProvider(pageKey) { content(pageKey) }
                }
            }
        }
    }
}

/**
 * One page with its transition transform. The top page moves with the exit value (slide, or
 * shrink with rounded corners); the page below shifts and dims. Every transform is read inside
 * layer and draw blocks, so a running transition never recomposes anything.
 */
@Composable
private fun NavPageLayer(
    state: NavStackState,
    top: Boolean,
    reduced: Boolean,
    pageColor: Color,
    content: @Composable () -> Unit,
) {
    val pose = if (top) topPose(state) else belowPose(state, reduced)
    Box(Modifier.fillMaxSize().then(pose).background(pageColor)) { content() }
}

private fun topPose(state: NavStackState): Modifier = Modifier
    .graphicsLayer {
        val pose = NavPose.foreground(state.exit, state.style, state.direction)
        translationX = pose.translationX * size.width
        scaleX = pose.scale
        scaleY = pose.scale
        shadowElevation = pose.lift * NavPose.LiftElevationDp.dp.toPx()
        if (pose.cornerDp > 0f) {
            shape = RoundedCornerShape(pose.cornerDp.dp)
            clip = true
        }
    }
    .drawBehind {
        val strength = NavPose.foreground(state.exit, state.style, state.direction).edgeShadow
        if (strength > 0f) {
            val width = NavPose.EdgeShadowWidthDp.dp.toPx()
            drawRect(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = NavPose.EdgeShadowAlpha * strength)),
                    startX = -width,
                    endX = 0f,
                ),
                topLeft = Offset(-width, 0f),
                size = Size(width, size.height),
            )
        }
    }

private fun belowPose(state: NavStackState, reduced: Boolean): Modifier = Modifier
    .graphicsLayer {
        if (!reduced) translationX = NavPose.underlay(state.exit, state.style).shiftX * size.width
    }
    .drawWithContent {
        drawContent()
        if (!reduced) {
            val dim = NavPose.underlay(state.exit, state.style).dim
            if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
        }
    }
    // The page below is only ever seen, never touched; it also stays out of the accessibility tree.
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
    }
    .clearAndSetSemantics { }
