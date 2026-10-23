package tech.musxi.player.ui.player

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.design.FavoritesSheet
import tech.musxi.player.ui.design.favoriteLiked
import tech.musxi.player.ui.design.lyricsBilingual
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class TranslationButtonTest {
    @get:Rule val compose = createComposeRule()

    @Test fun translationCanBeSwitchedOnAndOff() {
        var on by mutableStateOf(false)
        compose.setContent {
            MusxiTheme {
                TranslationChipButton(
                    TranslationUi(if (on) TranslationChip.ON else TranslationChip.OFF, hasText = true),
                    onToggle = { on = !on }, onRefresh = {},
                )
            }
        }
        val button = compose.onNodeWithContentDescription("翻译")
        button.assertIsOff().performClick().assertIsOn().performClick().assertIsOff()
    }

    @Test fun failedLookupWithNoTextOffersRetryWithoutPretendingToToggle() {
        var toggles = 0
        var refreshes = 0
        compose.setContent {
            MusxiTheme {
                TranslationChipButton(
                    TranslationUi(TranslationChip.WARNING, canRefresh = true),
                    onToggle = { toggles++ }, onRefresh = { refreshes++ },
                )
            }
        }
        compose.onNodeWithContentDescription("翻译").performClick()
        compose.runOnIdle {
            assertEquals(0, toggles)
            assertEquals(1, refreshes)
        }
    }

    @Test fun staleButReadableTranslationCanBeClosedAndIndependentlyRefreshed() {
        var toggles = 0
        var refreshes = 0
        compose.setContent {
            MusxiTheme {
                TranslationChipButton(
                    TranslationUi(TranslationChip.WARNING, canRefresh = true, hasText = true),
                    onToggle = { toggles++ }, onRefresh = { refreshes++ },
                )
            }
        }
        val button = compose.onNodeWithContentDescription("翻译")
        button.assertIsOn().performClick()
        button.performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(1, toggles)
            assertEquals(1, refreshes)
        }
    }

    @Test fun confirmedAbsentTranslationIsDisabled() {
        compose.setContent {
            MusxiTheme {
                TranslationChipButton(TranslationUi(TranslationChip.NONE), onToggle = {}, onRefresh = {})
            }
        }
        compose.onNodeWithContentDescription("翻译").assertIsNotEnabled()
    }

    @Test fun actionTargetsShareTheirCentreLineAndDoNotOverlap() = checkActionLayout(1f)

    @Test fun largeFontKeepsActionsAlignedAndPlatformReadable() = checkActionLayout(1.3f)

    private fun checkActionLayout(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                FavoritesSheet(Accent.MIST, ThemeMode.LIGHT, favoriteLiked(), lyrics = lyricsBilingual())
            }
        }
        val translation = compose.onNodeWithContentDescription("翻译").assertHeightIsEqualTo(48.dp)
            .fetchSemanticsNode().boundsInRoot
        val favorite = compose.onNodeWithContentDescription("取消收藏").assertHeightIsEqualTo(48.dp)
            .fetchSemanticsNode().boundsInRoot
        assertEquals(translation.center.x, favorite.center.x, 0.01f)
        assertTrue("Touch targets overlap", translation.bottom <= favorite.top)
        compose.onNodeWithText("晴天").assertIsDisplayed()
        compose.onNodeWithText("网易云音乐").assertIsDisplayed()
    }
}
