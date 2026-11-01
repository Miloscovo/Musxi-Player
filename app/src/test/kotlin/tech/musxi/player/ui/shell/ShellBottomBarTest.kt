package tech.musxi.player.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.theme.MusxiTheme

/**
 * The tab row of the dock: the selected tab is marked by a capsule behind the icon only, with the
 * label underneath (the product owner preferred this to a capsule around icon and label). The row
 * is at least 64dp, and the font-scale check is the spec's rule that it grows with the text instead
 * of clipping it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class ShellBottomBarTest {
    @get:Rule val compose = createComposeRule()

    private var selected by mutableStateOf(ShellTab.LIBRARY)
    private var fontScale by mutableStateOf(1f)
    private val picked = mutableListOf<ShellTab>()

    private fun setBar() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MusxiTheme {
                    Box(Modifier.testTag("bar")) {
                        ShellBottomBar(selected = selected, onSelect = { picked += it }, embedded = true)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun tappingATabReportsIt() {
        setBar()
        compose.onNodeWithText("搜索").performClick()
        compose.onNodeWithText("我的").performClick()
        assertEquals(listOf(ShellTab.SEARCH, ShellTab.ME), picked)
    }

    @Test fun onlyTheSelectedTabIsMarkedSelected() {
        setBar()
        compose.onNodeWithText("曲库").assertIsSelected()
        compose.onNodeWithText("搜索").assertIsNotSelected()
        compose.runOnIdle { selected = ShellTab.ME }
        compose.waitForIdle()
        compose.onNodeWithText("我的").assertIsSelected()
        compose.onNodeWithText("曲库").assertIsNotSelected()
    }

    @Test fun theRowIsAtLeast64dpAndDoesNotGrowAtNormalSize() {
        setBar()
        val height = compose.onNodeWithTag("bar").getUnclippedBoundsInRoot().height.value
        // 64dp is the least. The CJK label's own line height can add a dp or two under Robolectric,
        // but at the normal font size the row must not grow noticeably past it.
        assertTrue("tab row height ${height}dp", height >= 64f && height <= 72f)
        for (label in ShellTab.entries.map { it.label }) {
            compose.onNodeWithText(label).assertHeightIsAtLeast(44.dp).assertWidthIsAtLeast(44.dp)
        }
    }

    @Test fun theRowGrowsWithTheFontScaleInsteadOfClippingTheLabel() {
        setBar()
        val normal = compose.onNodeWithTag("bar").getUnclippedBoundsInRoot().height
        compose.runOnIdle { fontScale = 2f }
        compose.waitForIdle()
        val large = compose.onNodeWithTag("bar").getUnclippedBoundsInRoot().height
        assertTrue("row stayed ${normal.value}dp at font scale 2.0 (now ${large.value}dp)", large > normal)
    }
}
