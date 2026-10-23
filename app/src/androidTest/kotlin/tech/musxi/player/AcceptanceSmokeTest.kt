package tech.musxi.player

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tech.musxi.core.account.Platform
import tech.musxi.core.lyrics.LyricsStatus
import java.io.File

/** Installed product entry point, live repositories, no fixture data and no account/favourite writes. */
@RunWith(AndroidJUnit4::class)
class AcceptanceSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun productTabsOpenOnThePhone() {
        compose.onNodeWithText("我的").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("网易云", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("搜索").performClick()
        compose.onNodeWithText("歌名、歌手或专辑").assertIsDisplayed()
        compose.onNodeWithText("曲库").performClick()
        compose.onAllNodesWithText("曲库").onFirst().assertIsDisplayed()
    }

    /** Backend setup is explicit: the product still lacks a restore button. Never starts audio. */
    @Test fun savedQueueOpensPlayerLyricsAndQueueWithoutAutoplay() {
        val app = compose.activity.application as MusxiApplication
        compose.waitUntil(20_000) {
            app.playbackJournal.state.value.getValue(Platform.KUGOU).let {
                it.accountVerified && !it.loading && it.queue != null
            }
        }
        compose.runOnIdle { app.playbackJournal.restore(Platform.KUGOU) }
        compose.waitUntil(20_000) { app.playback.state.value.queueSize > 0 }
        val title = app.playback.state.value.title
        val count = app.playback.state.value.queueSize
        compose.onNodeWithText(title).performClick()
        compose.onNodeWithContentDescription("歌词").performClick()
        compose.onNodeWithContentDescription("收起").assertIsDisplayed()
        compose.onNodeWithContentDescription("播放队列").performClick()
        compose.onNodeWithText("$count 首 · 点一首跳到它").assertIsDisplayed()
        capture("v01-queue-phone.png")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("收起").performClick()
        compose.runOnIdle {
            assertFalse(app.playback.state.value.playing)
            assertFalse(app.playback.state.value.playWhenReady)
        }
    }

    @Test fun liveLyricsRenderAfterExplicitPlaybackThenPause() {
        val app = compose.activity.application as MusxiApplication
        compose.waitUntil(20_000) {
            app.playbackJournal.state.value.getValue(Platform.KUGOU).let {
                it.accountVerified && !it.loading && it.queue != null
            }
        }
        compose.runOnIdle { app.playbackJournal.restore(Platform.KUGOU) }
        compose.waitUntil(20_000) { app.playback.state.value.queueSize > 0 }
        compose.onNodeWithText(app.playback.state.value.title).performClick()
        try {
            compose.onAllNodesWithContentDescription("播放").onLast().performClick()
            compose.waitUntil(30_000) { app.playback.state.value.playing }
            compose.onAllNodesWithContentDescription("暂停").onLast().performClick()
            compose.onNodeWithContentDescription("歌词").performClick()
            compose.waitUntil(30_000) {
                app.lyrics.state.value.status in setOf(LyricsStatus.TIMED, LyricsStatus.PLAIN)
            }
            capture("v01-lyrics-phone.png")
        } finally {
            compose.runOnIdle { app.playback.pause() }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val bitmap = requireNotNull(automation.takeScreenshot())
        File(compose.activity.cacheDir, name).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
