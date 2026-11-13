package tech.musxi.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.wallpaper.WallpaperFrame

/** Where the picture sits and how much of it shows; the pictures themselves are in WallpaperSheetTest. */
class WallpaperRulesTest {

    @Test fun zoomOneIsTheLargestWindowShapedRectangle() {
        // A landscape picture in a portrait window: full height, window-shaped width, centred.
        val r = wallpaperSource(1800, 1200, 360f, 800f, WallpaperFrame())
        assertEquals(1200f, r.height, 0.01f)
        assertEquals(1200f * 360f / 800f, r.width, 0.01f)
        assertEquals(900f, r.center.x, 0.01f)
    }

    @Test fun theCentreIsKeptInsideThePicture() {
        val r = wallpaperSource(1800, 1200, 360f, 800f, WallpaperFrame(centerX = 1f, centerY = 0f))
        assertEquals(1800f, r.right, 0.01f)
        assertEquals(0f, r.top, 0.01f)
    }

    @Test fun zoomShrinksTheShownPart() {
        val r = wallpaperSource(1800, 1200, 360f, 800f, WallpaperFrame(zoom = 2f))
        assertEquals(600f, r.height, 0.01f)
        assertEquals(600f, r.center.y, 0.01f)
    }

    @Test fun aSavedFrameIsLegalAtAnyWindowShape() {
        val frame = WallpaperFrame(centerX = 0.9f, centerY = 0.1f, zoom = 3f)
        for ((w, h) in listOf(360f to 800f, 800f to 360f, 360f to 360f, 600f to 900f)) {
            val r = wallpaperSource(1200, 1800, w, h, frame)
            assertTrue(r.left >= 0f && r.top >= 0f && r.right <= 1200.01f && r.bottom <= 1800.01f)
            assertEquals(w / h, r.width / r.height, 0.001f)
        }
    }

    @Test fun strengthWalksUpToWhatStaysReadable() {
        // A picture that needs no help: 100 reaches the page's cap, 0 shows nothing.
        val mid = WallpaperTone(deep = 0.75f, bright = 0.9f)
        assertEquals(1f, wallpaperScrim(0, dark = false, lyrics = false, tone = mid, baseLuma = 0.96f), 0.001f)
        assertEquals(0.35f, wallpaperScrim(100, dark = false, lyrics = false, tone = mid, baseLuma = 0.96f), 0.001f)
        // A very dark picture in light mode: 100 stops at the text floor, and half is half of that.
        val dark = WallpaperTone(deep = 0.05f, bright = 0.3f)
        val floor = (0.70f - 0.05f) / (0.96f - 0.05f)
        assertEquals(floor, wallpaperScrim(100, dark = false, lyrics = false, tone = dark, baseLuma = 0.96f), 0.001f)
        assertEquals(1f - 0.5f * (1f - floor), wallpaperScrim(50, dark = false, lyrics = false, tone = dark, baseLuma = 0.96f), 0.001f)
    }

    @Test fun everyStepOfTheSliderChangesThePicture() {
        val tone = WallpaperTone(deep = 0.2f, bright = 0.8f)
        for (dark in listOf(false, true)) for (lyrics in listOf(false, true)) {
            val base = if (dark) 0.007f else 0.955f
            val values = (0..100 step 5).map { wallpaperScrim(it, dark, lyrics, tone, base) }
            assertTrue("dark=$dark lyrics=$lyrics", values.zipWithNext().all { (a, b) -> b < a })
        }
    }

    @Test fun pastOneHundredShowsMoreButNeverDropsTheTheme() {
        val tones = listOf(WallpaperTone(0.05f, 0.3f), WallpaperTone(0.2f, 0.8f), WallpaperTone(0.75f, 0.9f), WallpaperTone(0.9f, 0.98f))
        for (tone in tones) for (dark in listOf(false, true)) {
            val base = if (dark) 0.007f else 0.955f
            for (lyrics in listOf(false, true)) {
                val walk = (0..150 step 5).map { wallpaperScrim(it, dark, lyrics, tone, base) }
                assertTrue("player $tone dark=$dark lyrics=$lyrics", walk.zipWithNext().all { (a, b) -> b <= a })
                assertTrue("player keeps a scrim $tone dark=$dark", walk.last() >= 0.15f - 0.001f)
            }
            val walk = (0..150 step 5).map { interfaceWallpaperScrim(it, dark, tone, base) }
            assertTrue("interface $tone dark=$dark", walk.zipWithNext().all { (a, b) -> b <= a })
            assertTrue("interface keeps a scrim $tone dark=$dark", walk.last() >= 0.45f - 0.001f)
        }
        // 0..100 is untouched: the old mid-tone picture still reaches 0.35 at 100, and 150 goes to the 0.85 cap.
        val mid = WallpaperTone(deep = 0.75f, bright = 0.9f)
        assertEquals(0.35f, wallpaperScrim(100, dark = false, lyrics = false, tone = mid, baseLuma = 0.96f), 0.001f)
        assertEquals(0.15f, wallpaperScrim(150, dark = false, lyrics = false, tone = mid, baseLuma = 0.96f), 0.001f)
        assertEquals(0.25f, wallpaperScrim(125, dark = false, lyrics = false, tone = mid, baseLuma = 0.96f), 0.001f)
        // Out-of-range values never reach the page, but the mapping holds them at its ends anyway.
        assertEquals(wallpaperScrim(150, false, false, mid, 0.96f), wallpaperScrim(400, false, false, mid, 0.96f), 0.0001f)
    }

    @Test fun theLyricsPageIsAlwaysCalmerThanTheCoverPage() {
        val tones = listOf(WallpaperTone(0.05f, 0.3f), WallpaperTone(0.4f, 0.8f), WallpaperTone(0.8f, 0.95f))
        for (tone in tones) for (dark in listOf(false, true)) for (s in listOf(15, 35, 60, 100, 125, 150)) {
            val base = if (dark) 0.007f else 0.955f
            val cover = wallpaperScrim(s, dark, lyrics = false, tone = tone, baseLuma = base)
            val lyrics = wallpaperScrim(s, dark, lyrics = true, tone = tone, baseLuma = base)
            assertTrue("$tone dark=$dark s=$s", lyrics >= cover)
        }
    }
}
