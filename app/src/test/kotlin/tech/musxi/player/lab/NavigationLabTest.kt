package tech.musxi.player.lab

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode

private val NavTitles = listOf("夜航", "回声", "薄雾", "海盐", "晴空万里", "慢速列车", "静默时区", "月光白", "旧唱片", "远方来信")
private val NavArtists = listOf("林间", "Aster", "沈宁", "Noa", "周屿", "Kite", "宋禾", "Luma", "陈默", "Orin")

/** 30 sample songs with ten distinct artists, so every playlist window has recognisable links. */
internal fun navTestSongs(): List<LabSong> = List(30) { i ->
    LabSong("nav-$i", NavTitles[i % 10], NavArtists[i % 10], "示例专辑", 180_000L + i * 7_000L, coverUrl = null)
}

internal fun navTestEnv(dark: Boolean = false, glass: Boolean = false) = LabEnv(
    songs = navTestSongs(),
    dark = dark,
    tuning = LabTuning.STANDARD,
    onTuning = {},
    onToggleDark = {},
    onExit = {},
    glass = glass,
)

/** What a person does in the navigation lab, driven through the real screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class NavigationLabTest {
    @get:Rule val compose = createComposeRule()

    private fun setLab(preview: NavigationLabPreview = NavigationLabPreview()) {
        compose.setContent {
            MusxiTheme(mode = ThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    NavigationLab(navTestEnv(), preview)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun back() {
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
    }

    @Test fun tappingAPlaylistPushesItsPageAndBackPopsIt() {
        setLab()
        compose.onNodeWithText("12 个歌单").assertExists()
        compose.onNodeWithText("通勤路上").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("返回").assertExists()
        back()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
        compose.onNodeWithText("12 个歌单").assertExists()
    }

    @Test fun libraryGoesThreeLevelsDeepThroughTheArtistName() {
        setLab()
        compose.onNodeWithText("通勤路上").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("林间").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("热门歌曲").assertExists()
        back()
        compose.onNodeWithText("热门歌曲").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").assertExists()
    }

    @Test fun eachTabKeepsItsOwnStack() {
        setLab()
        compose.onNodeWithText("通勤路上").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("lab-dock-SEARCH").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("热门搜索").assertExists()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
        compose.onNodeWithText("夜航").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("返回").assertExists()
        compose.onNodeWithTag("lab-dock-LIBRARY").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("返回").assertExists()
        compose.onNodeWithText("热门搜索").assertDoesNotExist()
    }

    @Test fun rootScrollSurvivesBeingTwoLevelsDown() {
        setLab()
        compose.onNodeWithTag("lab-list").performScrollToIndex(6)
        compose.waitForIdle()
        compose.onNodeWithText("冬夜围炉").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Noa")[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("热门歌曲").assertExists()
        back()
        back()
        compose.onNodeWithText("冬夜围炉").assertExists()
        compose.onNodeWithText("通勤路上").assertDoesNotExist()
    }

    @Test fun tabScrollSurvivesASwitch() {
        setLab()
        compose.onNodeWithTag("lab-list").performScrollToIndex(6)
        compose.waitForIdle()
        compose.onNodeWithTag("lab-dock-PROFILE").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Musxi 用户").assertExists()
        compose.onNodeWithTag("lab-dock-LIBRARY").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("冬夜围炉").assertExists()
    }

    @Test fun retappingTheTabLeavesAllLevelsAtOnce() {
        setLab()
        compose.onNodeWithText("通勤路上").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("林间").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("lab-dock-LIBRARY").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
        compose.onNodeWithText("12 个歌单").assertExists()
    }

    @Test fun retappingTheTabAtTheRootScrollsToTop() {
        setLab()
        compose.onNodeWithTag("lab-list").performScrollToIndex(6)
        compose.waitForIdle()
        compose.onNodeWithText("12 个歌单").assertDoesNotExist()
        compose.onNodeWithTag("lab-dock-LIBRARY").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("12 个歌单").assertExists()
    }

    @Test fun systemBackPopsTheTopPageWithoutAGesture() {
        var dispatcher: androidx.activity.OnBackPressedDispatcher? = null
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MusxiTheme(mode = ThemeMode.LIGHT) { NavigationLab(navTestEnv()) }
        }
        compose.waitForIdle()
        compose.onNodeWithText("通勤路上").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("返回").assertExists()
        compose.runOnIdle { dispatcher!!.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
    }
}
