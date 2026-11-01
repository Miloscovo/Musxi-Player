package tech.musxi.player.ui.library

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.PlaylistKind
import tech.musxi.core.library.PlaylistSummary
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.components.RowSpec
import tech.musxi.player.ui.design.libraryHomeSample
import tech.musxi.player.ui.design.libraryLoaded
import tech.musxi.player.ui.theme.MusxiTheme

/**
 * The modules at the top of the library tab. Each module has one heading above its card and the card
 * does not say it again; the "歌单" heading appears only when something sits above the list and the
 * list has no titled section of its own. The window is tall enough that nothing needs scrolling.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h2400dp-xhdpi", application = DesignTestApplication::class)
class LibraryHomeModulesTest {
    @get:Rule val compose = createComposeRule()

    private var state by mutableStateOf(libraryLoaded())
    private var home by mutableStateOf(libraryHomeSample())
    private var fontScale by mutableStateOf(1f)

    private fun show() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MusxiTheme {
                    LibraryContent(
                        state = state, onSelectFilter = {}, home = home,
                        initialDensity = LibraryDensity.LIST,
                    )
                }
            }
        }
    }

    private fun playlist(title: String, kind: PlaylistKind) =
        PlaylistSummary(MediaKey(Platform.NETEASE, "p-$title"), title, null, 10, kind)

    @Test fun everyModuleHasOneHeading() {
        show()
        for (heading in listOf("继续播放", "我喜欢", "最近播放", "歌单")) {
            // onNodeWithText fails on more than one match, so this also proves "exactly once".
            compose.onNodeWithText(heading).assertExists()
        }
    }

    @Test fun theLikedRowDoesNotRepeatItsHeading() {
        show()
        compose.onNodeWithText("我喜欢的音乐").assertDoesNotExist()
        compose.onNodeWithText("全部歌曲").assertExists()
        compose.onNodeWithText("746 首", substring = true).assertExists()
    }

    @Test fun aLikedTitleThatAddsSomethingIsKept() {
        state = state.copy(items = state.items.map {
            if (it.title == "我喜欢的音乐") it.copy(title = "阿青喜欢的音乐") else it
        })
        show()
        compose.onNodeWithText("阿青喜欢的音乐").assertExists()
        compose.onNodeWithText("全部歌曲").assertDoesNotExist()
    }

    @Test fun withoutModulesThePlaylistsNeedNoHeading() {
        state = state.copy(items = state.items.drop(1))
        home = LibraryHomeUi()
        show()
        for (heading in listOf("继续播放", "我喜欢", "最近播放", "歌单")) {
            compose.onNodeWithText(heading).assertDoesNotExist()
        }
        compose.onNodeWithText("私人雷达").assertExists()
    }

    @Test fun aTitledSectionNamesItself() {
        state = state.copy(items = listOf(
            playlist("我喜欢的音乐", PlaylistKind.LIKED),
            playlist("夜读", PlaylistKind.CREATED),
            playlist("华语经典", PlaylistKind.COLLECTED),
        ))
        show()
        compose.onNodeWithText("创建的歌单").assertExists()
        compose.onNodeWithText("收藏的歌单").assertExists()
        compose.onNodeWithText("歌单").assertDoesNotExist()
    }

    @Test fun aPlaylistRowIsTheCoverPlusPaddingAndGrowsWithTheFont() {
        show()
        val row = compose.onNodeWithText("私人雷达")
        row.assertHeightIsAtLeast(RowSpec.cover + RowSpec.vertical * 2)
        val normal = row.fetchSemanticsNode().size.height
        compose.runOnIdle { fontScale = 2f }
        compose.waitForIdle()
        val large = row.fetchSemanticsNode().size.height
        assertTrue("row stayed ${normal}px at font scale 2.0 (now ${large}px)", large > normal)
    }

    @Test fun theHeadingRuleDoesNotDependOnTheOrderOfTheList() {
        // The liked list in the middle of the data still becomes the module, not a row of the list.
        val items = libraryLoaded().items
        state = state.copy(items = items.drop(2) + items.take(2))
        show()
        compose.onNodeWithText("全部歌曲").assertExists()
        assertEquals(1, compose.onAllNodesWithTextCount("私人雷达"))
    }

    @Test fun theResumePositionIsNeverCutAtDoubleFontSize() {
        show()
        compose.runOnIdle { fontScale = 2f }
        compose.waitForIdle()
        val layout = textLayoutOf("3 / 24 首 · 上次听到 1:23")
        assertTrue("the position line is ellipsized at font scale 2.0", (0 until layout.lineCount).none(layout::isLineEllipsized))
    }

    @Test fun aRecentTileKeepsItsTitleOnTwoLinesAtDoubleFontSize() {
        show()
        compose.runOnIdle { fontScale = 2f }
        compose.waitForIdle()
        // "夜的第七章" does not fit one line of a 96dp tile at 200%; it takes a second one, uncut.
        val layout = textLayoutOf("夜的第七章")
        assertEquals(2, layout.lineCount)
        assertTrue((0 until layout.lineCount).none(layout::isLineEllipsized))
    }

    private fun textLayoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
