package tech.musxi.player.lab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import tech.musxi.player.ui.theme.rememberHaptics
import tech.musxi.player.ui.theme.rememberReducedMotion

/**
 * Experiment 1: the continuous player (spec section 8). A fake playlist sits behind a floating
 * glass dock; the dock's mini bar and the full-screen player are one surface driven by one
 * progress value. [initial] lets screenshot tests freeze a moment; the defaults are normal use.
 */
@Composable
internal fun PlayerLab(env: LabEnv, initial: PlayerLabInitial = PlayerLabInitial()) {
    val scope = rememberCoroutineScope()
    val s = remember { PlayerLabState(initial, scope) }
    s.songs = env.songs
    s.tuning = env.tuning
    s.haptic = rememberHaptics()
    s.reduced = rememberReducedMotion()
    s.density = LocalDensity.current.density
    val palette = rememberCoverPalette(s.song)
    LabGlassHost(
        env,
        overlay = { PlayerOverlay(env, s, palette) },
        content = { PlayerLabPage(env, s, nowPlayingColor(palette, env.dark)) },
    )
}

@Composable
private fun BoxScope.PlayerOverlay(env: LabEnv, s: PlayerLabState, palette: CoverPalette) {
    // Registered after the activity's handler, so it runs first: sheet, then mode, then the player.
    BackHandler(enabled = s.backEnabled) { s.back() }
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.getTop(density).toFloat()
    val bottom = WindowInsets.navigationBars.getBottom(density).toFloat()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        s.layout = remember(w, h, top, bottom, density.density) { PlayerLayout(density, w, h, top, bottom) }
        PlayerSurface(s, env, palette)
        QueueSheetHost(s, env)
    }
}

/** Queue variant B: the shared lab sheet, a child of the glass host's overlay. */
@Composable
private fun QueueSheetHost(s: PlayerLabState, env: LabEnv) {
    var detent by rememberSaveable { mutableStateOf(LabSheetDetent.MEDIUM) }
    LabSheet(
        visible = s.sheetVisible && s.queueStyle == QueueStyle.SHEET,
        onDismiss = { s.sheetVisible = false },
        env = env,
        initialDetent = detent,
        onDetentChange = { detent = it },
    ) {
        Text(
            "接下来播放", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        // The sheet leaves the navigation-bar inset to its content; the list scrolls under it and ends above it.
        QueueList(
            s, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f),
            bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        )
    }
}
