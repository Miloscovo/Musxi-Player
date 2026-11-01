package tech.musxi.player.lab

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import tech.musxi.player.ui.theme.Haptic
import tech.musxi.player.ui.theme.Motion
import tech.musxi.player.ui.theme.rememberHaptics
import tech.musxi.player.ui.theme.rememberReducedMotion

/**
 * Starting state for tests and screenshots. The default is the normal lab: library tab, every tab
 * at its root page, nothing moving. [stack] holds the pages above the root of [tab]; [exit] freezes
 * the top page part-way through a transition; [style] and [direction] pick the pose family it uses.
 */
internal data class NavigationLabPreview(
    val tab: LabTab = LabTab.LIBRARY,
    val stack: List<LabRoute> = emptyList(),
    val exit: Float = 0f,
    val style: BackStyle = BackStyle.FOLLOW,
    val direction: Float = 1f,
    val backStyle: BackStyle = BackStyle.FOLLOW,
)

/**
 * Page push and back experiment (spec section 7): three tabs with their own page stacks, push from
 * the right with a parallax underlay, and system back gesture in two styles to compare.
 */
// NoCollectCallFound is a false positive here: the back events are handed to
// LabTabNav.handleBack, which collects them (Lint only looks inside the lambda).
@android.annotation.SuppressLint("NoCollectCallFound")
@Composable
internal fun NavigationLab(env: LabEnv, preview: NavigationLabPreview = NavigationLabPreview()) {
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    val reduced = rememberReducedMotion()
    val scrollStore = remember { LabScrollStore() }
    var selected by rememberSaveable { mutableStateOf(preview.tab) }
    var backStyle by rememberSaveable { mutableStateOf(preview.backStyle) }
    val navs = remember {
        LabTab.entries.associateWith { tab ->
            val shown = tab == preview.tab
            LabTabNav(
                tab = tab,
                scope = scope,
                onDiscard = { route -> scrollStore.discard(tab, route) },
                initialStack = listOf<LabRoute>(LabRoute.Root(tab)) + if (shown) preview.stack else emptyList(),
                initialExit = if (shown) preview.exit else 0f,
                initialStyle = preview.style,
                initialDirection = preview.direction,
            )
        }
    }
    val currentEnv by rememberUpdatedState(env)
    val currentNav by rememberUpdatedState(navs.getValue(selected))
    val currentStyle by rememberUpdatedState(backStyle)

    // Only while there is a page to go back to; at the root the activity's own BackHandler leaves the lab.
    PredictiveBackHandler(enabled = currentNav.depth > 1) { events ->
        currentNav.handleBack(
            events = events,
            preferred = currentStyle,
            release = { currentEnv.tuning.release<Float>() },
            standard = { currentEnv.tuning.standard<Float>() },
        )
    }

    val dockInset = DockHeight + DockMargin * 2 + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxSize()) {
        // The lab host has no Surface, so the default content colour stays black and the bar's
        // title and icons vanish in dark mode.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            LabTopBar(env, LabExperiment.NAVIGATION.title)
        }
        BackStyleRow(backStyle) { style ->
            if (style != backStyle) {
                haptic(Haptic.TICK)
                backStyle = style
            }
        }
        LabGlassHost(
            env,
            Modifier.weight(1f),
            overlay = {
                LabDock(selected) { tab ->
                    val nav = navs.getValue(tab)
                    when {
                        // Spec section 5 lists no haptic for switching tabs, so there is none.
                        tab != selected -> selected = tab
                        nav.depth > 1 -> nav.popToRoot(env.tuning.standard<Float>())
                        else -> scope.launch { scrollStore.stateFor(tab, LabRoute.Root(tab)).animateScrollToItem(0) }
                    }
                }
            },
        ) {
            AnimatedContent(
                targetState = selected,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = { tabFade() },
                label = "tabFade",
            ) { tab ->
                LabTabHost(navs.getValue(tab), env, scrollStore, dockInset, reduced)
            }
        }
    }
}

/** Tabs cross-fade with no movement: the new tab fades in over 220 ms while the old one leaves in 120 ms. */
private fun tabFade(): ContentTransform =
    ContentTransform(fadeIn(Motion.enter()), fadeOut(Motion.exit()), sizeTransform = null)
