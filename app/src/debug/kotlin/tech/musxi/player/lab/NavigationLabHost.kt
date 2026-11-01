package tech.musxi.player.lab

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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shows one tab: its top page and the page below it. Pages are keyed by route, so a page that
 * stops being the top one keeps its composition and only changes its transform.
 */
@Composable
internal fun LabTabHost(
    nav: LabTabNav,
    env: LabEnv,
    scrollStore: LabScrollStore,
    bottomPad: Dp,
    reduced: Boolean,
) {
    val currentEnv by rememberUpdatedState(env)
    val ctx = LabPageContext(
        env = env,
        nav = nav,
        scroll = scrollStore,
        bottomPad = bottomPad,
        onPush = { route -> nav.push(route, currentEnv.tuning.standard<Float>()) },
        onBack = { nav.pop(currentEnv.tuning.standard<Float>()) },
    )
    val pageColor = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().background(pageColor)) {
        val last = nav.stack.lastIndex
        for (i in maxOf(0, last - 1)..last) {
            val route = nav.stack[i]
            key(route.key) {
                LabPageLayer(nav, top = i == last, reduced = reduced, pageColor = pageColor) {
                    LabPage(route, ctx)
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
private fun LabPageLayer(
    nav: LabTabNav,
    top: Boolean,
    reduced: Boolean,
    pageColor: Color,
    content: @Composable () -> Unit,
) {
    val pose = if (top) {
        Modifier
            .graphicsLayer {
                val pose = LabPose.foreground(nav.exit, nav.style, nav.direction)
                translationX = pose.translationX * size.width
                scaleX = pose.scale
                scaleY = pose.scale
                shadowElevation = pose.lift * 20.dp.toPx()
                if (pose.cornerDp > 0f) {
                    shape = RoundedCornerShape(pose.cornerDp.dp)
                    clip = true
                }
            }
            .drawBehind {
                val strength = LabPose.foreground(nav.exit, nav.style, nav.direction).edgeShadow
                if (strength > 0f && nav.exit > 0.001f) {
                    val width = 12.dp.toPx()
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.04f * strength)),
                            startX = -width,
                            endX = 0f,
                        ),
                        topLeft = Offset(-width, 0f),
                        size = Size(width, size.height),
                    )
                }
            }
    } else {
        Modifier
            .graphicsLayer {
                if (!reduced) translationX = LabPose.underlay(nav.exit, nav.style).shiftX * size.width
            }
            .drawWithContent {
                drawContent()
                if (!reduced) {
                    val dim = LabPose.underlay(nav.exit, nav.style).dim
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
    }
    Box(Modifier.fillMaxSize().then(pose).background(pageColor)) { content() }
}
