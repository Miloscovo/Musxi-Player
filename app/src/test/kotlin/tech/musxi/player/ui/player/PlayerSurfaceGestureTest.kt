package tech.musxi.player.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.design.playerNormal
import tech.musxi.player.ui.design.sampleMiniPlaying
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode

/** Real touches on the continuous player: pull up, pull down, scrub, and the cover swipe. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class PlayerSurfaceGestureTest {
    @get:Rule val compose = createComposeRule()

    private class CountingHaptics : HapticFeedback {
        val types = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            types += hapticFeedbackType
        }
        fun count(type: HapticFeedbackType) = types.count { it == type }
    }

    private class Host {
        lateinit var progress: PlayerProgress
        lateinit var link: PlayerSurfaceLink
        val haptics = CountingHaptics()
        var expanded = false
        var nexts = 0
        var previouses = 0
    }

    private fun host(open: Boolean, hasPrevious: Boolean = true, hasNext: Boolean = true): Host {
        val h = Host().also { it.expanded = open }
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides h.haptics) {
                MusxiTheme(accent = Accent.MIST, mode = ThemeMode.LIGHT) {
                    Box(Modifier.fillMaxSize()) {
                        var expanded by remember { mutableStateOf(open) }
                        val progress = rememberPlayerProgress(expanded, { expanded = it; h.expanded = it })
                        h.progress = progress
                        val ui = playerNormal().copy(hasPrevious = hasPrevious, hasNext = hasNext)
                        PlayerSurface(
                            progress = progress, mini = sampleMiniPlaying(), ui = ui, dark = false, miniRect = null,
                            nowPlaying = null, previous = null, next = null, coverShown = true,
                            onPlayPause = {}, onPrevious = { h.previouses++ }, onNext = { h.nexts++ },
                        ) { surface ->
                            h.link = surface
                            tech.musxi.player.ui.player.FullPlayer(ui = ui, dark = false, surface = surface)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        return h
    }

    private fun drag(from: Offset, to: Offset, millis: Long = 300) {
        compose.onNodeWithTag("player-surface").performTouchInput { swipe(from, to, millis) }
        compose.waitForIdle()
    }

    @Test fun draggingTheMiniRowUpOpensThePlayerAndBuzzesOnce() {
        val h = host(open = false)
        drag(Offset(200f, 64f), Offset(200f, -700f))
        assertEquals(1f, h.progress.p.value, 1e-3f)
        assertTrue("the saved flag follows", h.expanded)
        assertEquals(1, h.haptics.count(HapticFeedbackType.GestureThresholdActivate))
    }

    @Test fun aShortDragOnTheMiniRowSettlesBack() {
        val h = host(open = false)
        drag(Offset(200f, 64f), Offset(200f, -36f), millis = 1000)
        assertEquals(0f, h.progress.p.value, 1e-3f)
        assertEquals(0, h.haptics.count(HapticFeedbackType.GestureThresholdActivate))
    }

    @Test fun tappingTheMiniRowOpensThePlayerWithoutABuzz() {
        val h = host(open = false)
        compose.onNodeWithTag("player-surface").performTouchInput { click(Offset(150f, 64f)) }
        compose.waitForIdle()
        assertEquals(1f, h.progress.p.value, 1e-3f)
        assertTrue(h.expanded)
        assertEquals(0, h.haptics.types.size)
    }

    @Test fun pullingTheFullPageDownClosesItAndBuzzesOnce() {
        val h = host(open = true)
        drag(Offset(360f, 500f), Offset(360f, 1300f))
        assertEquals(0f, h.progress.p.value, 1e-3f)
        assertEquals("the saved flag follows", false, h.expanded)
        assertEquals(1, h.haptics.count(HapticFeedbackType.GestureThresholdActivate))
    }

    @Test fun aDragThatStartsOnTheProgressBarDoesNotClose() {
        val h = host(open = true)
        val scrub = compose.runOnIdle { h.link.probe.scrub!! }
        drag(Offset(scrub.center.x, scrub.top + 6f), Offset(scrub.center.x, scrub.top + 706f))
        assertEquals(1f, h.progress.p.value, 1e-3f)
        assertTrue(h.expanded)
    }

    @Test fun swipingTheCoverLeftPlaysTheNextSong() {
        val h = host(open = true)
        drag(Offset(560f, 600f), Offset(120f, 600f), millis = 200)
        assertEquals(1, h.nexts)
        assertEquals(0, h.previouses)
        assertEquals(1, h.haptics.count(HapticFeedbackType.SegmentTick))
        assertEquals("the page stays open", 1f, h.progress.p.value, 1e-3f)
    }

    @Test fun swipingTheCoverRightPlaysThePreviousSong() {
        val h = host(open = true)
        drag(Offset(120f, 600f), Offset(560f, 600f), millis = 200)
        assertEquals(1, h.previouses)
        assertEquals(0, h.nexts)
    }

    @Test fun swipingPastTheLastSongChangesNothing() {
        val h = host(open = true, hasNext = false)
        drag(Offset(560f, 600f), Offset(120f, 600f), millis = 200)
        assertEquals(0, h.nexts)
        assertEquals(0, h.haptics.count(HapticFeedbackType.SegmentTick))
    }

    @Test fun aShortCoverSwipeKeepsTheSong() {
        val h = host(open = true)
        drag(Offset(400f, 600f), Offset(340f, 600f), millis = 1000)
        assertEquals(0, h.nexts)
        assertEquals(0, h.previouses)
    }
}
