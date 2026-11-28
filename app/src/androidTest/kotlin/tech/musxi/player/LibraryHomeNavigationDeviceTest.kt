package tech.musxi.player

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.Platform
import tech.musxi.player.ui.theme.displayName

/** Product semantic actions only: opens lists and returns, never plays or edits anything. */
@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
class LibraryHomeNavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun recommendationAndLikedNavigationKeepRecentPosition() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowPlaylistReadOnly") == "true")
        val app = compose.activity.application as MusxiApplication
        val report = JSONObject().put("platforms", JSONArray())
        val output = File(app.filesDir, "library-home-navigation-device-results.json")
        try {
            compose.onAllNodesWithText("曲库").onFirst().performClick()
            for (platform in listOf(Platform.KUGOU, Platform.QQ, Platform.NETEASE)) {
                compose.waitUntil(90_000) { app.accounts.states.value.getValue(platform).phase != AccountPhase.REVALIDATING }
                val row = JSONObject().put("platform", platform.name)
                report.getJSONArray("platforms").put(row)
                if (app.accounts.states.value.getValue(platform).phase != AccountPhase.CONNECTED) {
                    row.put("result", "account_not_connected")
                    continue
                }
                compose.onNodeWithText(platform.displayName).performScrollTo().performClick()
                val catalog = app.playlists.catalog(platform)
                compose.waitUntil(90_000) { catalog.state.value.let { !it.loading && it.items.isNotEmpty() && it.error == null } }
                compose.waitForIdle()
                val hasRecent = compose.onAllNodesWithText("最近播放").fetchSemanticsNodes().isNotEmpty()
                row.put("hasRecent", hasRecent)
                fun recentY(): Float? = if (hasRecent) compose.onNodeWithText("最近播放").fetchSemanticsNode().boundsInRoot.top else null
                val actions = JSONArray()
                row.put("actions", actions)
                val liked = catalog.state.value.items.firstOrNull { tech.musxi.player.ui.library.isLikedPlaylist(it) }
                val rec = app.recommendations.state.value[platform]?.snapshot
                val targets = listOfNotNull(
                    liked?.let { "liked" to tech.musxi.player.ui.library.likedRowTitle(it.title) },
                    rec?.takeIf { it.songs.isNotEmpty() }?.let { "recommendation" to tech.musxi.player.ui.library.recommendationArtists(it.songs) },
                )
                for ((kind, text) in targets) {
                    compose.onNodeWithText(text).performScrollTo()
                    compose.waitForIdle()
                    val before = recentY()
                    compose.onNodeWithText(text).performClick()
                    compose.waitForIdle()
                    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                    compose.waitForIdle()
                    val after = recentY()
                    actions.put(JSONObject().put("kind", kind).put("recentBefore", before ?: JSONObject.NULL)
                        .put("recentAfter", after ?: JSONObject.NULL))
                    if (before != null && after != null) assertTrue("$platform $kind recent moved: $before -> $after", kotlin.math.abs(after - before) <= 1f)
                }
                val beforeIdle = recentY()
                Thread.sleep(3_000)
                compose.waitForIdle()
                val afterIdle = recentY()
                row.put("idleRecentBefore", beforeIdle ?: JSONObject.NULL).put("idleRecentAfter", afterIdle ?: JSONObject.NULL)
                if (beforeIdle != null && afterIdle != null) assertTrue("$platform recent moved at idle", kotlin.math.abs(afterIdle - beforeIdle) <= 1f)
                // Actual pull gesture on the product Compose root, never screen coordinates
                // that could land in another application's floating window.
                val beforePull = recentY()
                val headerBefore = compose.onAllNodesWithText("曲库").fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top
                compose.onRoot().performTouchInput { swipeDown(startY = height * .28f, endY = height * .72f, durationMillis = 400) }
                compose.waitUntil(90_000) { !catalog.state.value.loading }
                // Pull release is an Android frame animation, independent of repository IO.
                Thread.sleep(1_000)
                compose.waitForIdle()
                val afterPull = recentY()
                val headerAfter = compose.onAllNodesWithText("曲库").fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top
                row.put("pullRecentBefore", beforePull ?: JSONObject.NULL).put("pullRecentAfter", afterPull ?: JSONObject.NULL)
                    .put("pullHeaderBefore", headerBefore ?: JSONObject.NULL).put("pullHeaderAfter", headerAfter ?: JSONObject.NULL)
                if (beforePull != null && afterPull != null) assertTrue("$platform recent moved after pull", kotlin.math.abs(afterPull - beforePull) <= 1f)
                row.put("result", "passed")
                output.writeText(report.toString(2))
            }
            compose.onNodeWithText("酷狗").performScrollTo().performClick()
            report.put("result", "passed")
        } catch (error: Throwable) {
            report.put("result", "failed").put("failureType", error.javaClass.simpleName)
            throw error
        } finally { output.writeText(report.toString(2)) }
    }
}
