package tech.musxi.player.ui.components

import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the glass renderers that need no device: which renderer a device gets, and the
 * numbers each style is tuned with (spec section 3).
 */
class GlassRendererTest {
    private fun pick(sdk: Int, powerSave: Boolean = false, enabled: Boolean = true) =
        chooseGlassRenderer(sdk, powerSave, enabled)

    @Test fun androidThirteenAndAboveRefracts() {
        for (sdk in listOf(33, 34, 35, 36)) assertEquals("API $sdk", GlassRenderer.REFRACTION, pick(sdk))
    }

    @Test fun androidTwelveIsFrosted() {
        assertEquals(GlassRenderer.FROSTED, pick(31))
        assertEquals(GlassRenderer.FROSTED, pick(32))
    }

    @Test fun belowAndroidTwelveIsSolid() {
        for (sdk in listOf(26, 28, 29, 30)) assertEquals("API $sdk", GlassRenderer.SOLID, pick(sdk))
    }

    @Test fun batterySaverIsSolidAtEveryLevel() {
        for (sdk in listOf(26, 30, 31, 32, 33, 35)) assertEquals("API $sdk", GlassRenderer.SOLID, pick(sdk, powerSave = true))
    }

    @Test fun switchedOffIsSolidAtEveryLevel() {
        for (sdk in listOf(26, 30, 31, 32, 33, 35)) assertEquals("API $sdk", GlassRenderer.SOLID, pick(sdk, enabled = false))
    }

    @Test fun switchedOffInBatterySaverIsStillSolid() {
        assertEquals(GlassRenderer.SOLID, pick(35, powerSave = true, enabled = false))
    }

    // Spec section 3: text on glass never gets less tint than this table, whichever renderer draws it.
    @Test fun tintFollowsTheSpecTable() {
        val table = mapOf(
            GlassStyle.REGULAR to (0.55f to 0.45f),
            GlassStyle.THIN to (0.68f to 0.55f),
            GlassStyle.CLEAR to (0.30f to 0.30f),
        )
        for ((style, alphas) in table) {
            assertEquals("$style light", Color.White.copy(alpha = alphas.first), glassTint(style, dark = false))
            assertEquals("$style dark", Color.Black.copy(alpha = alphas.second), glassTint(style, dark = true))
        }
    }

    @Test fun lensIsStrongestOnClearAndWeakestOnThin() {
        val thin = GlassStyle.THIN.refraction()
        val regular = GlassStyle.REGULAR.refraction()
        val clear = GlassStyle.CLEAR.refraction()
        assertTrue(thin.lensHeight < regular.lensHeight && regular.lensHeight < clear.lensHeight)
        assertTrue(thin.lensAmount < regular.lensAmount && regular.lensAmount < clear.lensAmount)
        assertTrue(thin.rimLight < regular.rimLight && regular.rimLight < clear.rimLight)
    }

    // The lens bends what it can still read: a refraction blur as big as Haze's would leave nothing to bend.
    @Test fun refractionBlursLessThanFrostedGlassAndKeepsTheStyleOrder() {
        for (style in GlassStyle.entries) assertTrue("$style", style.refraction().blur < style.blur)
        val byFrosted = GlassStyle.entries.sortedBy { it.blur.value }
        val byRefraction = GlassStyle.entries.sortedBy { it.refraction().blur.value }
        assertEquals(byFrosted, byRefraction)
    }

    @Test fun lensNeverBendsPastTheMiddleOfASmallControl() {
        assertEquals(22f, fitLens(40f, 44f), 0f)
        assertEquals(20f, fitLens(20f, 200f), 0f)
        assertEquals(0f, fitLens(20f, 0f), 0f)
    }

    @Test fun lensNeedsARoundedShape() {
        assertTrue(RoundedCornerShape(28.dp).supportsLens())
        assertTrue(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp).supportsLens())
        assertTrue(AbsoluteRoundedCornerShape(12.dp).supportsLens())
        assertTrue(CircleShape.supportsLens())
        assertFalse(RectangleShape.supportsLens())
        assertFalse(CutCornerShape(8.dp).supportsLens())
    }
}
