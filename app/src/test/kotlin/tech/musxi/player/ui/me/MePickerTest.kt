package tech.musxi.player.ui.me

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.core.playback.PreferredAudioQuality
import tech.musxi.core.settings.LyricsFontSize
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.design.MeSheet
import tech.musxi.player.ui.design.meSampleUi
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode

/**
 * The four settings picked from a menu beside their row (默认音质, 字号, 深色模式, 主题色): what the
 * menu lists, that a pick is reported once and closes the menu, and that only one menu is open at a
 * time. The menu is a popup window of its own, so its rows are found under the popup root; the row
 * on the page shows the same word as the chosen entry and would otherwise match too.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h1500dp-xhdpi", application = DesignTestApplication::class)
class MePickerTest {
    @get:Rule val compose = createComposeRule()

    /** Sample page: quality 标准, font size 中, dark mode 跟随系统, accent 雾蓝. */
    private var ui by mutableStateOf(meSampleUi())

    private fun setPage(actions: MeActions = MeActions()) {
        compose.setContent { MusxiTheme { MeScreen(ui = ui, actions = actions) } }
        compose.waitForIdle()
    }

    private fun open(row: String) {
        compose.onNodeWithText(row).performClick()
        compose.waitForIdle()
    }

    private fun choice(label: String) = compose.onNode(hasText(label) and hasAnyAncestor(isPopup()))

    private fun pick(label: String) {
        choice(label).performClick()
        compose.waitForIdle()
    }

    private fun menus() = compose.onAllNodes(isPopup())

    // --- 字号 -----------------------------------------------------------------------------------

    @Test fun theFontSizeMenuListsTheThreeSizesAndClosesOnAPick() {
        val picked = mutableListOf<LyricsFontSize>()
        setPage(MeActions(onSelectFontSize = { picked += it }))
        menus().assertCountEquals(0)

        open("字号")
        menus().assertCountEquals(1)
        choice("小").assertIsNotSelected()
        choice("中").assertIsSelected()
        choice("大").assertIsNotSelected()

        pick("大")
        assertEquals(listOf(LyricsFontSize.LARGE), picked)
        menus().assertCountEquals(0)
    }

    @Test fun eachSizeShowsASampleInThatSize() {
        setPage()
        open("字号")
        // The samples are hidden from accessibility (the label already names the size), so they are
        // found by tag in the unmerged tree, in the order the menu lists them: 小, 中, 大.
        val widths = compose.onAllNodesWithTag(MeFontSampleTag, useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.size.width }
        assertEquals(3, widths.size)
        assertTrue("samples should grow with the size: $widths", widths[0] < widths[1] && widths[1] < widths[2])
    }

    @Test fun pickingTheChosenSizeOnlyCloses() {
        val picked = mutableListOf<LyricsFontSize>()
        setPage(MeActions(onSelectFontSize = { picked += it }))
        open("字号")
        pick("中")
        assertTrue("the chosen size is not a change", picked.isEmpty())
        menus().assertCountEquals(0)
    }

    @Test fun theRowShowsWhatTheCallerSaved() {
        setPage(MeActions(onSelectFontSize = { size -> ui = ui.copy(lyrics = ui.lyrics.copy(fontSize = size)) }))
        open("字号")
        pick("小")
        // The menu is gone, so the only "小" left is the row's value.
        compose.onNodeWithText("小").assertExists()
        compose.onNodeWithText("中").assertDoesNotExist()
    }

    // --- 默认音质, 主题色 -------------------------------------------------------------------------

    @Test fun theQualityMenuListsEveryQuality() {
        val picked = mutableListOf<PreferredAudioQuality>()
        setPage(MeActions(onSelectQuality = { picked += it }))
        open("默认音质")
        for (quality in PreferredAudioQuality.entries) choice(qualityLabel(quality)).assertExists()
        choice("标准").assertIsSelected()

        pick("无损")
        assertEquals(listOf(PreferredAudioQuality.LOSSLESS), picked)
        menus().assertCountEquals(0)
    }

    @Test fun theAccentMenuListsEveryAccent() {
        val picked = mutableListOf<Accent>()
        setPage(MeActions(onSelectAccent = { picked += it }))
        open("主题色")
        for (accent in Accent.entries) choice(accent.label).assertExists()
        choice(Accent.MIST.label).assertIsSelected()

        pick(Accent.AMBER.label)
        assertEquals(listOf(Accent.AMBER), picked)
        menus().assertCountEquals(0)
    }

    // --- one at a time --------------------------------------------------------------------------

    @Test fun openingAnotherPickerClosesTheFirst() {
        setPage()
        open("默认音质")
        choice("标准").assertExists()

        // On a device the first tap outside only closes the menu; the test reaches the row directly,
        // which is the harder case: the page must still end up with a single menu.
        open("字号")
        menus().assertCountEquals(1)
        choice("大").assertExists()
        choice("无损").assertDoesNotExist()
    }

    // --- pictures -------------------------------------------------------------------------------

    // The whole page in one window, so each menu opens beside its row with the rest of the page around it.

    @Test fun fontSizeMenuLight() = shot("me_picker_font_size_light", ThemeMode.LIGHT, row = "字号", expect = "大")

    @Test fun accentMenuLight() =
        shot("me_picker_accent_light", ThemeMode.LIGHT, row = "主题色", expect = Accent.TEAL.label)

    @Test fun darkModeMenuDark() = shot("me_picker_dark_mode_dark", ThemeMode.DARK, row = "深色模式", expect = "浅色")

    /** The menu is a popup window of its own, so only a capture of the whole screen includes it. */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun shot(name: String, mode: ThemeMode, row: String, expect: String) {
        compose.setContent { MeSheet(Accent.MIST, mode) }
        compose.waitForIdle()
        open(row)
        menus().assertCountEquals(1)
        choice(expect).assertExists()
        captureScreenRoboImage("build/design/$name.png", RoborazziOptions(taskType = RoborazziTaskType.Record))
    }
}
