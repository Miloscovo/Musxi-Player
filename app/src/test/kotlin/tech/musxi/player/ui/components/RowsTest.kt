package tech.musxi.player.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.player.queueRowHeight
import tech.musxi.player.ui.theme.MusxiTheme

/** The shared row vocabulary: the monogram rule, where text starts, and rows that grow with the font. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class RowsTest {
    @get:Rule val compose = createComposeRule()

    private var fontScale by mutableStateOf(1f)

    @Test fun monogramIsTheFirstCharacter() {
        assertEquals("云", monogramOf("云村用户 8842"))
        assertEquals("酷", monogramOf("酷狗"))
        assertEquals("Q", monogramOf("QQ音乐"))
    }

    @Test fun monogramUpperCasesLatinAndTrimsSpace() {
        assertEquals("A", monogramOf("alice"))
        assertEquals("阿", monogramOf("  阿青"))
    }

    @Test fun monogramKeepsACharacterOutsideTheBasicPlaneWhole() {
        assertEquals("🎵", monogramOf("🎵 mix"))
    }

    @Test fun monogramOfNothingIsNothing() {
        assertEquals("", monogramOf(""))
        assertEquals("", monogramOf("   "))
    }

    @Test fun dividersStartAtTheTextNotUnderTheLead() {
        // A 48dp cover and the 12dp gap: the line begins 60dp in, as the search list always drew it.
        assertEquals(60.dp, RowSpec.textStart(RowSpec.cover))
        assertEquals(52.dp, RowSpec.textStart(RowSpec.avatar))
        assertEquals(68.dp, RowSpec.textStart(RowSpec.coverFeature))
    }

    @Test fun aCoverRowIsTheCoverPlusItsPadding() {
        assertEquals(64.dp, RowSpec.cover + RowSpec.vertical * 2)
        assertEquals(RowSpec.minHeight, RowSpec.coverCompact + RowSpec.vertical * 2)
        assertEquals(RowSpec.minHeight, RowSpec.avatar + RowSpec.vertical * 2)
    }

    @Test fun theQueueRowGrowsWithTheFontButNeverShrinksBelowTheMinimum() {
        var covered: Dp = 0.dp
        var plain: Dp = 0.dp
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MusxiTheme {
                    covered = queueRowHeight(showCover = true)
                    plain = queueRowHeight(showCover = false)
                }
            }
        }
        compose.waitForIdle()
        assertEquals(RowSpec.minHeight, covered)
        assertEquals(RowSpec.minHeight, plain)
        compose.runOnIdle { fontScale = 2f }
        compose.waitForIdle()
        assertTrue("queue row stayed ${covered.value}dp at font scale 2.0", covered > RowSpec.minHeight)
        assertEquals("the cover rows and the plain rows share a height", covered, plain)
    }
}
