package tech.musxi.player.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tech.musxi.player.ui.components.GlassStyle
import tech.musxi.player.ui.components.LocalGlass
import tech.musxi.player.ui.components.glass
import tech.musxi.player.ui.theme.Haptic
import tech.musxi.player.ui.theme.Motion
import tech.musxi.player.ui.theme.rememberHaptics
import tech.musxi.player.ui.theme.isDarkSurface

/** How long a notice stays on screen before it dismisses itself. */
internal const val NoticeDurationMillis = 4_000L

/**
 * One notice on screen. [id] is new for every [NoticeState.show] call, even when the text repeats.
 * [haptic] is played once when the notice appears (spec section 5: CONFIRM for a success the user
 * asked for, REJECT for a refusal); null for a notice that only informs.
 */
data class Notice(val id: Long, val text: String, val haptic: Haptic? = null)

/**
 * Holds the single notice that is on screen. A new notice replaces the old one instead of queuing
 * behind it, because a later message is always about the newer action.
 */
class NoticeState {
    var current: Notice? by mutableStateOf(null)
        private set

    private var lastId = 0L

    /** Id of the last notice whose haptic was handed out, so it is played once per notice. */
    private var hapticPlayedFor = 0L

    fun show(text: String, haptic: Haptic? = null) {
        lastId += 1
        current = Notice(id = lastId, text = text, haptic = haptic)
    }

    /**
     * The haptic for notice [id], the first time it is asked for and only while that notice is the
     * one on screen; null afterwards. Lives here rather than in the pill so a pill that leaves and
     * re-enters composition does not play it again.
     */
    fun takeHaptic(id: Long): Haptic? {
        val notice = current ?: return null
        if (notice.id != id || id <= hapticPlayedFor) return null
        hapticPlayedFor = id
        return notice.haptic
    }

    /** Clears the notice only when [id] is the one on screen. A stale timer or tap is a no-op. */
    fun dismiss(id: Long) {
        if (current?.id == id) current = null
    }
}

/**
 * A non-blocking feedback pill that replaces the system Toast. It uses the same surface as the
 * floating dock: white with a soft shadow in light mode, the raised dark surface with a hairline
 * outline in dark mode.
 */
@Composable
fun NoticePill(state: NoticeState, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.background.isDarkSurface()
    val shape = MaterialTheme.shapes.extraLarge
    // Null outside a GlassHost; the pill then keeps the solid dock colour.
    val glass = LocalGlass.current
    val notice = state.current
    // Keeps the last notice on screen while its exit animation runs after current becomes null.
    val lastNotice = remember { mutableStateOf(notice) }
    val haptic = rememberHaptics()
    LaunchedEffect(notice) { if (notice != null) lastNotice.value = notice }
    // Each notice gets its own effect run, keyed on its id, so a repeated text restarts the timer.
    LaunchedEffect(notice?.id) {
        val id = notice?.id ?: return@LaunchedEffect
        state.takeHaptic(id)?.let(haptic)
        delay(NoticeDurationMillis)
        state.dismiss(id)
    }
    AnimatedVisibility(
        visible = notice != null,
        modifier = modifier,
        enter = fadeIn(Motion.enter()) + slideInVertically(Motion.enter()) { it / 2 },
        exit = fadeOut(Motion.exit()),
    ) {
        val ui = notice ?: lastNotice.value ?: return@AnimatedVisibility
        Text(
            text = ui.text,
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .then(
                    if (glass != null) {
                        Modifier.glass(glass, shape, GlassStyle.THIN)
                    } else {
                        Modifier.shadowIfLight(dark, shape).clip(shape).background(noticeColor())
                            .then(if (dark) Modifier.border(1.dp, cs.outlineVariant.copy(alpha = 0.5f), shape) else Modifier)
                    },
                )
                .clickable { state.dismiss(ui.id) }
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

private fun Modifier.shadowIfLight(dark: Boolean, shape: androidx.compose.ui.graphics.Shape): Modifier =
    if (dark) this else shadow(
        elevation = 12.dp, shape = shape,
        ambientColor = Color.Black.copy(alpha = 0.06f),
        spotColor = Color.Black.copy(alpha = 0.12f),
        clip = false,
    )

/** Same fill as the floating dock, so the pill reads as part of the same chrome. */
@Composable
private fun noticeColor(): Color = dockColor()
