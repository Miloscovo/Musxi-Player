package tech.musxi.player.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.core.lyrics.LyricLine
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.design.lyricsNativeBilingual
import tech.musxi.player.ui.theme.MusxiTheme

/** Check the actual measured rows, after layout/scroll and after changes in content height. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class LyricsBalanceTest {
    @get:Rule val compose = createComposeRule()

    private var ui by mutableStateOf(longLyrics())
    private var fontScale by mutableStateOf(1f)
    private var blockHeight by mutableStateOf(400.dp)
    private var blockWidth by mutableStateOf(312.dp)
    private var seeks = mutableListOf<Long>()
    private var drags = 0

    @Test fun shortChineseDocumentStaysCentredAtBeginningMiddleAndEnd() {
        ui = lyricsNativeBilingual().copy(translation = TranslationUi(TranslationChip.OFF))
        show()
        for (index in listOf(0, 4, ui.lines.lastIndex)) {
            compose.runOnIdle { ui = ui.copy(activeIndex = index) }
            val first = row(ui.lines.first().text)
            val last = row(ui.lines.last().text)
            assertEquals(viewport().center.y, (first.top + last.bottom) / 2f, 1f)
            assertTrue(first.top > viewport().top)
            assertTrue(last.bottom < viewport().bottom)
        }
    }

    @Test fun oneBilingualGroupIsCentredAsAWhole() {
        ui = longLyrics().copy(lines = listOf(LyricLine(0L, "把窗推开一点", "Leave the window open")), activeIndex = 0)
        show()
        assertCurrentCentred()
        compose.onNodeWithText("Leave the window open", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun initialOffscreenCurrentGroupIsCentredAfterFirstLayout() {
        show()
        assertCurrentCentred()
    }

    @Test fun translationToggleRecentresUsingTheNewGroupHeight() {
        show()
        assertCurrentCentred()
        compose.runOnIdle { ui = ui.copy(translation = TranslationUi(TranslationChip.OFF)) }
        assertCurrentCentred()
        compose.runOnIdle { ui = ui.copy(translation = TranslationUi(TranslationChip.ON)) }
        assertCurrentCentred()
    }

    @Test fun delayedTranslationAndChangedViewportAreMeasuredAgain() {
        ui = longLyrics().copy(lines = longLyrics().lines.map { it.copy(translation = null) })
        show()
        assertCurrentCentred()
        compose.runOnIdle { ui = ui.copy(lines = longLyrics().lines) }
        assertCurrentCentred()
        compose.runOnIdle { blockHeight = 520.dp }
        assertCurrentCentred()
    }

    @Test fun wrappedBilingualGroupRemainsCentredAtLargerFont() {
        val lines = longLyrics().lines.toMutableList()
        lines[12] = LyricLine(120_000L,
            "I keep walking past the windows and the small lives inside them",
            "我继续走过那些亮着的窗户，以及窗子里那些小小的生活")
        ui = longLyrics().copy(lines = lines)
        show()
        assertCurrentCentred()
        compose.runOnIdle { fontScale = 1.3f }
        assertCurrentCentred()
    }

    @Test fun beginningAndEndHaveNoArtificialBlankHalfScreen() {
        show()
        compose.runOnIdle { ui = ui.copy(activeIndex = 0) }
        assertEquals(viewport().top, row(ui.lines.first().text).top, 1f)
        compose.runOnIdle { ui = ui.copy(activeIndex = ui.lines.lastIndex) }
        assertEquals(viewport().bottom, row(ui.lines.last().text).bottom, 1f)
    }

    @Test fun widthOnlyChangesReflowAndRecentreTheGroup() {
        val lines = longLyrics().lines.toMutableList()
        lines[12] = LyricLine(120_000L, "I keep walking past the windows and the small lives inside them",
            "我继续走过那些亮着的窗户，以及窗子里那些小小的生活")
        ui = longLyrics().copy(lines = lines)
        show()
        assertCurrentCentred()
        compose.runOnIdle { blockWidth = 230.dp }
        assertCurrentCentred()
    }

    @Test fun newDocumentBeforeItsFirstTimestampDoesNotInheritThePreviousScroll() {
        show()
        assertCurrentCentred()
        compose.runOnIdle {
            ui = ui.copy(activeIndex = -1, lines = ui.lines.map { it.copy(text = "New ${it.text}") })
        }
        assertEquals(viewport().top, row(ui.lines.first().text).top, 1f)
    }

    @Test fun manualBrowsingSurvivesTicksAndTranslationChangesThenCanResume() {
        show()
        compose.onNodeWithTag("lyrics-viewport").performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue("Drag did not release following", drags > 0) }
        compose.onNodeWithTag("lyrics-viewport").performScrollToIndex(3)
        val before = row(ui.lines[3].text).top
        compose.runOnIdle { ui = ui.copy(activeIndex = 18) }
        assertEquals(before, row(ui.lines[3].text).top, 1f)
        compose.runOnIdle { ui = ui.copy(translation = TranslationUi(TranslationChip.OFF)) }
        assertEquals(before, row(ui.lines[3].text).top, 1f)
        compose.runOnIdle { ui = ui.copy(browsing = false) }
        assertCurrentCentred()
    }

    @Test fun centredRowsStillSeekToTheirOriginalTimestamp() {
        show()
        val line = ui.lines[ui.activeIndex]
        compose.onNodeWithText(line.text).performClick()
        compose.runOnIdle { assertEquals(listOf(line.timeMs), seeks) }
    }

    @Test fun shortUntimedLyricsAreVerticallyBalanced() {
        ui = LyricsUi(phase = LyricsPhase.PLAIN, plainText = "把窗推开一点\n风就进来了")
        show()
        val note = row("这首歌没有时间轴，不能跟着滚动")
        val text = row(ui.plainText)
        // The block starts at y=0; text and its explanation are one balanced reading group.
        val centre = with(compose.density) { blockHeight.toPx() / 2 }
        assertEquals(centre, (note.top + text.bottom) / 2f, 1f)
    }

    private fun show() = compose.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            MusxiTheme {
                Box(Modifier.width(blockWidth).height(blockHeight)) {
                    LyricsBlock(ui, onSeekLine = { seeks.add(it) }, onUserScroll = {
                        drags++
                        ui = ui.copy(browsing = true)
                    })
                }
            }
        }
    }

    private fun row(text: String): Rect = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
    private fun viewport(): Rect = compose.onNodeWithTag("lyrics-viewport").fetchSemanticsNode().boundsInRoot
    private fun assertCurrentCentred() {
        assertEquals("Current original/translation group is off centre", viewport().center.y,
            row(ui.lines[ui.activeIndex].text).center.y, 1f)
    }

    private fun longLyrics() = LyricsUi(
        phase = LyricsPhase.TIMED,
        lines = (0..25).map { LyricLine(it * 10_000L, "Window number $it", "第${it}扇窗还亮着") },
        activeIndex = 12,
        translation = TranslationUi(TranslationChip.ON, hasText = true),
    )
}
