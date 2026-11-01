package tech.musxi.player.ui.player

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.player.ui.components.coverColors

/** The page palette taken from a cover's own colour, and its fall-back to the seed. */
class PlayerPaletteCoverTest {
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private val seeds = listOf("晴天", "夜に駆ける", "Wherever You Are", "群青", "zzzz", "")
    private val rose = argb(200, 60, 100)

    /** The palette exactly as it was derived before covers were read, written out from the seed alone. */
    private fun legacy(seed: String, dark: Boolean): PlayerPalette {
        fun hsl(hue: Float, lightness: Float, chroma: Float): Color {
            val room = (1f - abs(2f * lightness - 1f)).coerceAtLeast(0.05f)
            return Color.hsl(hue, (chroma / room).coerceIn(0f, 1f), lightness)
        }
        val hue = ((seed.hashCode() and 0x7fffffff) % 360).toFloat()
        val second = (hue + 38f) % 360f
        val coverChroma = coverColors(seed).let { (a, b) -> maxOf(a.chroma(), b.chroma()) }
        val backdropChroma = coverChroma * 0.22f
        val accentChroma = coverChroma * 0.85f
        return if (dark) {
            PlayerPalette(
                hsl(hue, 0.15f, backdropChroma), hsl(second, 0.10f, backdropChroma * 0.85f),
                hsl(hue, 0.66f, accentChroma), hsl(hue, 0.14f, accentChroma * 0.55f),
            )
        } else {
            PlayerPalette(
                hsl(hue, 0.94f, backdropChroma), hsl(second, 0.97f, backdropChroma * 0.75f),
                hsl(hue, 0.42f, accentChroma), hsl(hue, 0.98f, accentChroma * 0.18f),
            )
        }
    }

    private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

    @Test fun noCoverIsTheSeedPaletteAsBefore() {
        seeds.forEach { seed ->
            listOf(true, false).forEach { dark ->
                assertEquals("$seed dark=$dark", legacy(seed, dark), playerPalette(seed, dark))
                assertEquals("$seed dark=$dark null", legacy(seed, dark), playerPalette(seed, dark, null))
            }
        }
    }

    @Test fun theSameColourAlwaysGivesTheSamePalette() {
        listOf(true, false).forEach { dark ->
            val first = playerPalette("晴天", dark, rose)
            repeat(3) { assertEquals(first, playerPalette("晴天", dark, rose)) }
        }
    }

    @Test fun onceTheCoverHasAHueTheSeedIsNotConsulted() {
        listOf(true, false).forEach { dark ->
            assertEquals(playerPalette("晴天", dark, rose), playerPalette("夜に駆ける", dark, rose))
        }
        // And the cover does change the result against the seed palette.
        assertNotEquals(playerPalette("晴天", true), playerPalette("晴天", true, argb(40, 60, 210)))
    }

    @Test fun aGreyCoverIsNotTintedWithAnAccidentalHue() {
        // (130,128,126) leans orange by a hair, (126,128,130) leans blue; both are grey in practice.
        val greys = listOf(argb(128, 128, 128), argb(130, 128, 126), argb(126, 128, 130), argb(135, 128, 128), argb(0, 0, 0))
        seeds.forEach { seed ->
            listOf(true, false).forEach { dark ->
                greys.forEach { grey ->
                    assertEquals("$seed dark=$dark grey=${grey.toString(16)}", legacy(seed, dark), playerPalette(seed, dark, grey))
                }
            }
        }
        // Two greys with opposite noise hues give the same page.
        assertEquals(playerPalette("x", true, argb(130, 128, 126)), playerPalette("x", true, argb(126, 128, 130)))
    }

    @Test fun greyThresholdSitsAtEightLevelsBetweenTheChannels() {
        assertNull(coverTintOf(argb(135, 128, 128)))
        assertNotNull(coverTintOf(argb(136, 128, 128)))
        assertNull(coverTintOf(argb(128, 128, 128)))
    }

    @Test fun tintReadsHueAndChromaOfPrimaries() {
        fun hue(r: Int, g: Int, b: Int) = coverTintOf(argb(r, g, b))!!.hue
        assertEquals(0f, hue(255, 0, 0), 0.5f)
        assertEquals(60f, hue(255, 255, 0), 0.5f)
        assertEquals(120f, hue(0, 255, 0), 0.5f)
        assertEquals(180f, hue(0, 255, 255), 0.5f)
        assertEquals(240f, hue(0, 0, 255), 0.5f)
        assertEquals(300f, hue(255, 0, 255), 0.5f)
        assertEquals(1f, coverTintOf(argb(255, 0, 0))!!.chroma, 0.001f)
        assertEquals(100f / 255f, coverTintOf(argb(200, 100, 100))!!.chroma, 0.001f)
        // Hue stays inside 0..360 even for a colour just short of red from the magenta side.
        assertTrue(coverTintOf(argb(255, 0, 1))!!.hue < 360f)
    }

    @Test fun theAccentFollowsTheCoversHue() {
        listOf(true, false).forEach { dark ->
            val red = playerPalette("x", dark, argb(210, 40, 40)).accent
            assertTrue("red dark=$dark", red.red > red.green && red.red > red.blue)
            val green = playerPalette("x", dark, argb(40, 200, 60)).accent
            assertTrue("green dark=$dark", green.green > green.red && green.green > green.blue)
            val blue = playerPalette("x", dark, argb(40, 60, 210)).accent
            assertTrue("blue dark=$dark", blue.blue > blue.red && blue.blue > blue.green)
        }
    }

    @Test fun aMutedCoverGivesAMutedRoom() {
        listOf(true, false).forEach { dark ->
            val vivid = playerPalette("x", dark, argb(210, 40, 40))
            val muted = playerPalette("x", dark, argb(150, 110, 110))
            assertTrue("accent dark=$dark", muted.accent.chroma() < vivid.accent.chroma())
            assertTrue("backdrop dark=$dark", muted.backdropTop.chroma() < vivid.backdropTop.chroma())
        }
    }

    @Test fun anyCoverKeepsTheBackdropDarkInDarkAndLightInLight() {
        val covers = listOf(
            argb(255, 0, 0), argb(0, 255, 0), argb(0, 0, 255), argb(255, 255, 0),
            argb(0, 255, 255), argb(255, 0, 255), rose, argb(20, 60, 200),
        )
        covers.forEach { cover ->
            val dark = playerPalette("x", true, cover)
            assertTrue("dark top ${cover.toString(16)}", dark.backdropTop.luminance() < 0.30f)
            assertTrue("dark bottom ${cover.toString(16)}", dark.backdropBottom.luminance() < 0.30f)
            val light = playerPalette("x", false, cover)
            assertTrue("light top ${cover.toString(16)}", light.backdropTop.luminance() > 0.80f)
            assertTrue("light bottom ${cover.toString(16)}", light.backdropBottom.luminance() > 0.80f)
        }
    }
}
