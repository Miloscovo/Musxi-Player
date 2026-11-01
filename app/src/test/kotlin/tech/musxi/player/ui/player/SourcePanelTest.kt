package tech.musxi.player.ui.player

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.components.SheetTag
import tech.musxi.player.ui.design.panelChoosing
import tech.musxi.player.ui.design.panelChoosingPartial
import tech.musxi.player.ui.design.panelFailed
import tech.musxi.player.ui.design.panelFailedPartial
import tech.musxi.player.ui.design.panelSearching
import tech.musxi.player.ui.design.panelSearchingManual
import tech.musxi.player.ui.theme.MusxiTheme

/**
 * The source panel as the shell uses it: left in the tree, driven by [SourcePanelUi]. Covers what the
 * three faces print, what a tap reports, and how the sheet closes (scrim, back, the service hiding it)
 * on a 360x800dp screen, where the sheet rests with its top at 352dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class SourcePanelTest {
    @get:Rule val compose = createComposeRule()

    private var ui by mutableStateOf(SourcePanelUi())
    private val picked = mutableListOf<Int>()
    private var cancelled = 0
    private var skipped = 0
    private var retried = 0
    private var backDispatcher: OnBackPressedDispatcher? = null

    private fun show(panel: SourcePanelUi) {
        ui = panel
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MusxiTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    SourcePanel(
                        ui = ui,
                        dark = false,
                        onPick = { picked += it },
                        onCancel = { cancelled += 1 },
                        onSkip = { skipped += 1 },
                        onRetry = { retried += 1 },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun tapScrim() {
        compose.onRoot().performTouchInput { click(Offset(width / 2f, 100.dp.toPx())) }
        compose.waitForIdle()
    }

    @Test fun hiddenPanelHasNoSheet() {
        show(SourcePanelUi())
        compose.onNodeWithTag(SheetTag).assertDoesNotExist()
    }

    @Test fun searchingNamesTheFailureAndEachPlatform() {
        show(panelSearching())
        compose.onNodeWithText("《晴天》在酷狗放不了（需要会员）").assertExists()
        compose.onNodeWithText("正在其他平台找能播的版本").assertExists()
        compose.onNodeWithText("网易云 · 找到 2 个版本").assertExists()
        compose.onNodeWithText("QQ音乐 · 正在找").assertExists()
        compose.onNodeWithText("酷狗 · 原平台").assertExists()
    }

    @Test fun userStartedSearchSaysChangeTheSource() {
        show(panelSearchingManual())
        compose.onNodeWithText("换个音源").assertExists()
        compose.onNodeWithText("网易云 · 正在找").assertExists()
    }

    @Test fun choosingListsEveryVersionWithItsLengthNote() {
        show(panelChoosing())
        compose.onNodeWithText("换个音源").assertExists()
        compose.onNodeWithText("晴天（Live）").assertExists()
        compose.onNodeWithText("长 32 秒").assertExists()
        compose.onNodeWithText("短 8 秒").assertExists()
    }

    @Test fun choosingWithMissingPlatformsSaysTheListMayBeShort() {
        show(panelChoosingPartial())
        compose.onNodeWithText("有平台没回应，候选可能不全").assertExists()
    }

    @Test fun tappingARowPicksItsIndex() {
        show(panelChoosing())
        compose.onNodeWithText("晴天（Live）").performClick()
        assertEquals(listOf(1), picked)
    }

    @Test fun failedShowsEachPlatformsReasonAndBothActions() {
        show(panelFailed())
        compose.onNodeWithText("没找到能播的《晴天》").assertExists()
        compose.onNodeWithText("网易云：暂无版权").assertExists()
        compose.onNodeWithText("QQ音乐：没找到这首").assertExists()
        compose.onNodeWithText("酷狗：需要会员").assertExists()
        compose.onNodeWithText("重试").performClick()
        compose.onNodeWithText("播下一首").performClick()
        assertEquals(1, retried)
        assertEquals(1, skipped)
    }

    @Test fun failedWithoutLinesShowsTheSummarySentence() {
        show(panelFailedPartial().copy(platformLines = emptyList(), failureDetail = "还有平台没回应，稍后可以再试一次"))
        compose.onNodeWithText("还有平台没回应，稍后可以再试一次").assertExists()
    }

    @Test fun aNewFaceKeepsTheSheetOpenAndReplacesTheBody() {
        show(panelSearching())
        ui = panelChoosing()
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTag).assertExists()
        compose.onNodeWithText("晴天（Live）").assertExists()
        compose.onNodeWithText("QQ音乐 · 正在找").assertDoesNotExist()
    }

    @Test fun tapOnTheScrimCancelsAndClosesTheSheet() {
        show(panelChoosing())
        tapScrim()
        assertEquals(1, cancelled)
        compose.onNodeWithTag(SheetTag).assertDoesNotExist()
    }

    // The service has nothing to cancel once a search failed, so its state does not change: the
    // sheet must close by itself and stay closed until a new search (a new revision) starts.
    @Test fun dismissedFailedSheetStaysClosedUntilANewSearchStarts() {
        show(panelFailed())
        tapScrim()
        assertEquals(1, cancelled)
        compose.onNodeWithTag(SheetTag).assertDoesNotExist()

        ui = panelFailed().copy(failureDetail = "same search, new details")
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTag).assertDoesNotExist()

        ui = panelSearching().copy(revision = 2)
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTag).assertExists()
        compose.onNodeWithText("QQ音乐 · 正在找").assertExists()
    }

    @Test fun backClosesTheSheetAndCancels() {
        show(panelFailed())
        compose.runOnUiThread { backDispatcher!!.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, cancelled)
        compose.onNodeWithTag(SheetTag).assertDoesNotExist()
    }

    // The service hides the panel when a version is picked: the sheet slides out still showing what
    // it showed, instead of going blank on the way down.
    @Test fun hiddenFromTheServiceSlidesOutShowingTheLastFace() {
        show(panelChoosing())
        compose.mainClock.autoAdvance = false
        ui = SourcePanelUi(revision = 2)
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag(SheetTag).assertExists()
        compose.onNodeWithText("晴天（Live）").assertExists()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTag).assertDoesNotExist()
        assertEquals("the service hid it, nobody cancelled", 0, cancelled)
    }
}
