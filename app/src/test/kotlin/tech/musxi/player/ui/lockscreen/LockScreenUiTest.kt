package tech.musxi.player.ui.lockscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.player.ui.design.lyricsEmpty
import tech.musxi.player.ui.design.lyricsTimed
import tech.musxi.player.ui.player.LyricsPhase
import tech.musxi.player.ui.player.LyricsUi
import java.time.LocalDateTime

class LockScreenUiTest {
    @Test fun onlyTimedLyricsTakeTheMiddle() {
        assertEquals(LockScreenBody.LYRICS, lockScreenBodyOf(lyricsTimed()))
        assertEquals(LockScreenBody.COVER, lockScreenBodyOf(null))
        assertEquals(LockScreenBody.COVER, lockScreenBodyOf(lyricsEmpty()))
        assertEquals(LockScreenBody.COVER, lockScreenBodyOf(LyricsUi(phase = LyricsPhase.LOADING)))
        assertEquals(LockScreenBody.COVER, lockScreenBodyOf(LyricsUi(phase = LyricsPhase.PLAIN, plainText = "一段没有时间轴的歌词")))
        assertEquals(LockScreenBody.COVER, lockScreenBodyOf(LyricsUi(phase = LyricsPhase.ERROR)))
        assertEquals("timed but empty", LockScreenBody.COVER, lockScreenBodyOf(LyricsUi(phase = LyricsPhase.TIMED)))
    }

    @Test fun theDateLineReadsInChinese() {
        assertEquals("11月11日 星期三", lockScreenDate(LocalDateTime.of(2026, 11, 11, 21, 47)))
        assertEquals("1月5日 星期一", lockScreenDate(LocalDateTime.of(2026, 1, 5, 8, 0)))
    }

    @Test fun aQuarterOfThePageOrAFlickUnlocks() {
        val h = 2400f
        assertTrue(lockScreenUnlocks(dragUp = h * 0.3f, velocity = 0f, height = h))
        assertFalse(lockScreenUnlocks(dragUp = h * 0.2f, velocity = 0f, height = h))
        assertTrue("short but flicked", lockScreenUnlocks(dragUp = 60f, velocity = -5000f, height = h))
        assertFalse("short and slow", lockScreenUnlocks(dragUp = 60f, velocity = -800f, height = h))
    }

    @Test fun anyOfTheFourDirectionsUnlocks() {
        val w = 1080f
        val h = 2400f
        assertEquals(LockScreenExit.UP, lockScreenExitOf(10f, -h * 0.3f, 0f, 0f, w, h))
        assertEquals(LockScreenExit.DOWN, lockScreenExitOf(-10f, h * 0.3f, 0f, 0f, w, h))
        assertEquals(LockScreenExit.LEFT, lockScreenExitOf(-w * 0.4f, 20f, 0f, 0f, w, h))
        assertEquals(LockScreenExit.RIGHT, lockScreenExitOf(w * 0.4f, -20f, 0f, 0f, w, h))
        assertEquals("a short flick right", LockScreenExit.RIGHT, lockScreenExitOf(80f, 0f, 6000f, 0f, w, h))
    }

    @Test fun shortOrReversedDragsStay() {
        val w = 1080f
        val h = 2400f
        assertEquals(LockScreenExit.NONE, lockScreenExitOf(w * 0.2f, 0f, 0f, 0f, w, h))
        assertEquals(LockScreenExit.NONE, lockScreenExitOf(0f, -h * 0.1f, 0f, 0f, w, h))
        assertEquals("went right, came back left", LockScreenExit.NONE, lockScreenExitOf(w * 0.5f, 0f, -900f, 0f, w, h))
        assertEquals("went down, came back up", LockScreenExit.NONE, lockScreenExitOf(0f, h * 0.5f, 0f, -900f, w, h))
        assertEquals(LockScreenExit.NONE, lockScreenExitOf(0f, 0f, 0f, 0f, w, h))
        assertEquals("no page", LockScreenExit.NONE, lockScreenExitOf(500f, 0f, 9000f, 0f, 0f, 0f))
    }

    @Test fun aDragThatEndsGoingDownNeverUnlocks() {
        val h = 2400f
        assertFalse(lockScreenUnlocks(dragUp = h * 0.5f, velocity = 600f, height = h))
        assertFalse("pulled down", lockScreenUnlocks(dragUp = -40f, velocity = 0f, height = h))
        assertFalse("no page", lockScreenUnlocks(dragUp = 100f, velocity = -9000f, height = 0f))
    }
}
