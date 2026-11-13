package tech.musxi.player.localaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import tech.musxi.core.localaudio.LocalAudioError
import tech.musxi.core.localaudio.LocalAudioFailure

class LocalArtworkCropTest {
    /**
     * Raw 400x300 with the dyadic-exact crop (1/16, 1/8, 1/2, 9/16): upright box [25,37]-[200,169],
     * or [18,50]-[150,225] when the orientation swaps the axes.
     */
    private fun asymmetricRegion(orientation: Int): LocalArtworkRegion =
        LocalArtworkCropGeometry.region(400, 300, orientation, LocalArtworkCrop(0.0625f, 0.125f, 0.5f, 0.5625f))

    private fun assertRegion(expected: List<Int>, actual: LocalArtworkRegion) {
        assertEquals("left/top/right/bottom", expected, listOf(actual.left, actual.top, actual.right, actual.bottom))
    }

    private fun expectInvalid(block: () -> Unit) {
        try {
            block()
            fail("expected LocalAudioFailure(INVALID_TARGET)")
        } catch (error: LocalAudioFailure) {
            assertEquals(LocalAudioError.INVALID_TARGET, error.error)
        }
    }

    @Test
    fun `off-centre corner crop maps through every exif orientation`() {
        // Upright box (u=25..200, v=37..169) traced back to stored pixels, from EXIF 1..8.
        val table = mapOf(
            1 to listOf(25, 37, 200, 169),
            2 to listOf(200, 37, 375, 169),
            3 to listOf(200, 131, 375, 263),
            4 to listOf(25, 131, 200, 263),
            5 to listOf(50, 18, 225, 150),
            6 to listOf(50, 150, 225, 282),
            7 to listOf(175, 150, 350, 282),
            8 to listOf(175, 18, 350, 150),
        )
        table.forEach { (orientation, expected) ->
            assertRegion(expected, asymmetricRegion(orientation))
        }
    }

    @Test
    fun `crop rectangle keeps its pixel area under every orientation`() {
        (1..8).forEach { orientation ->
            val region = asymmetricRegion(orientation)
            assertEquals("orientation $orientation", 175 * 132, region.width * region.height)
        }
    }

    @Test
    fun `whole image crop returns every raw pixel for all orientations`() {
        (1..8).forEach { orientation ->
            val region = LocalArtworkCropGeometry.region(400, 300, orientation, LocalArtworkCrop(0f, 0f, 1f, 1f))
            assertRegion(listOf(0, 0, 400, 300), region)
        }
    }

    @Test
    fun `crop touching right and bottom edges stays inside the picture`() {
        assertRegion(
            listOf(200, 150, 400, 300),
            LocalArtworkCropGeometry.region(400, 300, 1, LocalArtworkCrop(0.5f, 0.5f, 1f, 1f)),
        )
        assertRegion(
            listOf(200, 0, 400, 150),
            LocalArtworkCropGeometry.region(400, 300, 6, LocalArtworkCrop(0.5f, 0.5f, 1f, 1f)),
        )
    }

    @Test
    fun `non-square crop is preserved instead of being forced square`() {
        val region = LocalArtworkCropGeometry.region(400, 300, 1, LocalArtworkCrop(0.2f, 0f, 0.8f, 1f))
        assertRegion(listOf(80, 0, 320, 300), region)
        assertEquals(240, region.width)
        assertEquals(300, region.height)
    }

    @Test
    fun `fractional pixel bounds round outward`() {
        // 0.201*200 = 40.2 -> 40, 0.799*200 = 159.8 -> 160, 0.101*300 = 30.3 -> 30, 0.899*300 = 269.7 -> 270.
        assertRegion(
            listOf(30, 40, 270, 160),
            LocalArtworkCropGeometry.region(300, 200, 1, LocalArtworkCrop(0.101f, 0.201f, 0.899f, 0.799f)),
        )
        // 0.7501*400 = 300.04 -> 301 and 0.7501*300 = 225.03 -> 226, so the boundary grows, never shrinks.
        assertRegion(
            listOf(100, 75, 301, 226),
            LocalArtworkCropGeometry.region(400, 300, 1, LocalArtworkCrop(0.25f, 0.25f, 0.7501f, 0.7501f)),
        )
    }

    @Test
    fun `unknown orientation is treated as upright`() {
        val expected = asymmetricRegion(1)
        listOf(0, 9, -3).forEach { orientation ->
            assertEquals("orientation $orientation", expected, asymmetricRegion(orientation))
        }
    }

    @Test
    fun `null crop returns the whole raw picture even below the minimum edge`() {
        assertEquals(LocalArtworkRegion(0, 0, 40, 40), LocalArtworkCropGeometry.region(40, 40, 1, null))
        assertEquals(LocalArtworkRegion(0, 0, 1, 1), LocalArtworkCropGeometry.region(1, 1, 6, null))
        assertEquals(LocalArtworkRegion(0, 0, 0, 0), LocalArtworkCropGeometry.region(0, 0, 1, null))
    }

    @Test
    fun `crop on a tiny picture is rejected as too small`() {
        expectInvalid { LocalArtworkCropGeometry.region(40, 40, 1, LocalArtworkCrop(0f, 0f, 1f, 1f)) }
    }

    @Test
    fun `validate rejects non-finite coordinates`() {
        listOf(
            LocalArtworkCrop(Float.NaN, 0f, 1f, 1f),
            LocalArtworkCrop(0f, Float.NaN, 1f, 1f),
            LocalArtworkCrop(0f, 0f, Float.POSITIVE_INFINITY, 1f),
            LocalArtworkCrop(0f, 0f, 1f, Float.NEGATIVE_INFINITY),
        ).forEach { crop ->
            expectInvalid { crop.validate() }
            expectInvalid { LocalArtworkCropGeometry.region(400, 300, 1, crop) }
        }
    }

    @Test
    fun `validate rejects coordinates outside zero to one`() {
        listOf(
            LocalArtworkCrop(-0.1f, 0f, 1f, 1f),
            LocalArtworkCrop(0f, -0.1f, 1f, 1f),
            LocalArtworkCrop(0f, 0f, 1.1f, 1f),
            LocalArtworkCrop(0f, 0f, 1f, 1.1f),
        ).forEach { crop -> expectInvalid { crop.validate() } }
    }

    @Test
    fun `validate rejects empty and inverted crops`() {
        listOf(
            LocalArtworkCrop(0.5f, 0f, 0.5f, 1f),
            LocalArtworkCrop(0f, 0.5f, 1f, 0.5f),
            LocalArtworkCrop(0.6f, 0f, 0.4f, 1f),
            LocalArtworkCrop(0f, 0.6f, 1f, 0.4f),
        ).forEach { crop -> expectInvalid { crop.validate() } }
    }

    @Test
    fun `invalid crop can be built and only fails when validated`() {
        val crop = LocalArtworkCrop(1f, 1f, 0f, 0f)
        assertEquals(1f, crop.left)
        expectInvalid { crop.validate() }
        LocalArtworkCrop(0f, 0f, 1f, 1f).validate()
    }

    @Test
    fun `short edge below 64 pixels is rejected while 64 is accepted`() {
        // 63/1024 and 64/1024 are exact in binary, so this compares the pixel limit and nothing else.
        assertEquals(
            LocalArtworkRegion(0, 0, 64, 1024),
            LocalArtworkCropGeometry.region(1024, 1024, 1, LocalArtworkCrop(0f, 0f, 0.0625f, 1f)),
        )
        expectInvalid {
            LocalArtworkCropGeometry.region(1024, 1024, 1, LocalArtworkCrop(0f, 0f, 0.0615234375f, 1f))
        }
    }

    @Test
    fun `minimum edge is measured on the upright picture`() {
        // Raw 300x400 stored sideways: upright is 400x300, so 0.16*400 = 64 wide is just enough.
        // That upright strip [0,64]x[0,300] is the raw bottom strip [0,336]-[300,400].
        assertEquals(
            LocalArtworkRegion(0, 336, 300, 400),
            LocalArtworkCropGeometry.region(300, 400, 6, LocalArtworkCrop(0f, 0f, 0.16f, 1f)),
        )
        expectInvalid {
            LocalArtworkCropGeometry.region(300, 400, 6, LocalArtworkCrop(0f, 0f, 0.157f, 1f))
        }
    }
}
