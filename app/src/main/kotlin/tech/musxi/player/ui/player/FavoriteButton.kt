package tech.musxi.player.ui.player

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import tech.musxi.player.ui.theme.Haptic
import tech.musxi.player.ui.theme.LocalMusxi
import tech.musxi.player.ui.theme.Motion
import tech.musxi.player.ui.theme.rememberHaptics
import tech.musxi.player.ui.theme.likedHeartColor

/** A liked change counts as caused by the user's tap when it follows the tap within this window. */
private const val TapWindowMs = 1_000L

/**
 * The favourite toggle.
 *
 * The heart shows the state the user asked for the moment they tap, and the backend confirms it in
 * the background; [FavoriteUi.syncing] is only a four-dp dot under the heart, because a ring or a
 * dimmed heart read as "nothing happened" for the seconds a confirmation takes. There is no ripple
 * and no focus ring, since a grey circle around a 24dp glyph is the thing that looked wrong here:
 * feedback is the heart itself, which shrinks while held, pops with a ring and six sparks when a
 * tap likes the song, dips slightly when it un-likes, and shakes sideways when the request fails.
 * The button stays tappable when [FavoriteUi.enabled] is false so the shell can say why it will not
 * work, and nothing here runs an infinite animation, so a screenshot test can always settle.
 *
 * Haptics (spec section 5) follow the same rule as the burst: only the user's own tap plays one.
 * A tap that will write plays [Haptic.LIKE] or [Haptic.UNLIKE] at once; a tap on a read-only heart
 * ([FavoriteUi.enabled] false) plays [Haptic.REJECT]. A heart changing from outside plays nothing,
 * and neither does a background request failing later (the notice that explains it is enough).
 */
@Composable
internal fun FavoriteButton(
    favorite: FavoriteUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Long press: choose where to collect the song (我喜欢, a playlist, or a new one). */
    onLongPress: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val likedColor = likedHeartColor(LocalMusxi.current.dark)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = Motion.Heart.press(),
        label = "heartPress",
    )
    val tint by animateColorAsState(
        targetValue = (if (favorite.liked) likedColor else cs.onSurfaceVariant)
            .copy(alpha = if (favorite.enabled) 1f else 0.35f),
        animationSpec = Motion.enter(),
        label = "heartTint",
    )
    val dotColor = cs.onSurfaceVariant
    val dotAlpha by animateFloatAsState(
        targetValue = if (favorite.syncing) 0.5f else 0f,
        animationSpec = Motion.enter(),
        label = "heartSyncDot",
    )

    val pop = remember { Animatable(1f) }
    val burst = remember { Animatable(0f) }
    val shake = remember { Animatable(0f) }
    val haptic = rememberHaptics()
    var lastTapAt by remember { mutableLongStateOf(-1L) }
    var previousLiked by remember { mutableStateOf(favorite.liked) }
    var seenFailures by remember { mutableIntStateOf(favorite.failures) }

    // Only a change that follows the user's own tap is celebrated. A heart that turns red because
    // the song changed or the collection finished syncing must not burst.
    LaunchedEffect(favorite.liked) {
        val was = previousLiked
        previousLiked = favorite.liked
        burst.snapTo(0f)
        pop.snapTo(1f)
        if (was == favorite.liked) return@LaunchedEffect
        val fromTap = lastTapAt >= 0 && SystemClock.elapsedRealtime() - lastTapAt <= TapWindowMs
        if (!fromTap) return@LaunchedEffect
        if (favorite.liked) {
            coroutineScope {
                launch { burst.animateTo(1f, Motion.Heart.burst()) }
                launch {
                    pop.animateTo(1.22f, Motion.Heart.popUp())
                    pop.animateTo(1f, Motion.pop())
                }
            }
            burst.snapTo(0f)
        } else {
            pop.animateTo(0.9f, Motion.Heart.dip())
            pop.animateTo(1f, Motion.exit())
        }
    }

    LaunchedEffect(favorite.failures) {
        val increased = favorite.failures > seenFailures
        seenFailures = favorite.failures
        shake.snapTo(0f)
        if (!increased) return@LaunchedEffect
        for (x in floatArrayOf(-5f, 5f, -3f, 3f, 0f)) {
            shake.animateTo(x, Motion.Heart.shakeStep())
        }
    }

    Box(
        modifier
            .size(48.dp)
            .graphicsLayer { translationX = shake.value.dp.toPx() }
            .drawBehind {
                val p = burst.value
                if (p > 0f) {
                    val eased = Motion.Heart.BurstTravel.transform(p)
                    drawCircle(
                        color = likedColor,
                        radius = lerp(12.dp.toPx(), 22.dp.toPx(), eased),
                        center = center,
                        alpha = 0.55f * (1f - p),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                    val distance = lerp(14.dp.toPx(), 22.dp.toPx(), eased)
                    repeat(6) { k ->
                        val angle = k * PI.toFloat() / 3f
                        drawCircle(
                            color = likedColor,
                            radius = 1.5.dp.toPx(),
                            center = Offset(
                                center.x + cos(angle) * distance,
                                center.y + sin(angle) * distance,
                            ),
                            alpha = 1f - p,
                        )
                    }
                }
                if (dotAlpha > 0f) {
                    // The glyph's tip sits 9.35dp below the icon's centre; the dot floats 2dp below it.
                    drawCircle(
                        color = dotColor,
                        radius = 2.dp.toPx(),
                        center = Offset(center.x, center.y + (9.35f + 2f + 2f).dp.toPx()),
                        alpha = dotAlpha,
                    )
                }
            }
            // The enabled flag is deliberately not passed on: a read-only account still gets the tap.
            // A toggle in semantics, but clickable underneath so a long press can ask "收藏到哪里".
            .semantics { toggleableState = ToggleableState(favorite.liked) }
            .combinedClickable(
                interactionSource = source,
                indication = null,
                role = Role.Checkbox,
                // The long press plays Haptic.LONG_PRESS itself; the default buzz would double it.
                hapticFeedbackEnabled = false,
                onLongClickLabel = if (onLongPress != null) "收藏到…" else null,
                onLongClick = onLongPress?.let { open ->
                    {
                        haptic(Haptic.LONG_PRESS)
                        open()
                    }
                },
                onClick = {
                    lastTapAt = SystemClock.elapsedRealtime()
                    haptic(tapHaptic(enabled = favorite.enabled, wantLiked = !favorite.liked))
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (favorite.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = if (favorite.liked) "取消收藏" else "收藏",
            tint = tint,
            modifier = Modifier.size(24.dp).graphicsLayer {
                val scale = pressScale * pop.value
                scaleX = scale
                scaleY = scale
            },
        )
    }
}

/**
 * The one haptic a tap on the heart plays: [Haptic.REJECT] when the account cannot write (the shell
 * then says why), otherwise the toggle the tap asks for.
 */
internal fun tapHaptic(enabled: Boolean, wantLiked: Boolean): Haptic = when {
    !enabled -> Haptic.REJECT
    wantLiked -> Haptic.LIKE
    else -> Haptic.UNLIKE
}
