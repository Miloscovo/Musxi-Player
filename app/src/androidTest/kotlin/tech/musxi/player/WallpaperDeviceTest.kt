package tech.musxi.player

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.wallpaper.FileWallpaperStore
import tech.musxi.core.wallpaper.WallpaperRepository
import tech.musxi.core.wallpaper.WallpaperScope
import tech.musxi.player.wallpaper.AndroidWallpaperImporter

/** Isolated local files only. Does not change the user's wallpaper, accounts, queue or playback. */
class WallpaperDeviceTest {
    @Test fun importPreviewApplyColdReadAndResetUseManagedCopies() = isolated { scratch ->
        val importer = AndroidWallpaperImporter(File(scratch, "imports"))
        val root = File(scratch, "managed")
        val repository = WallpaperRepository(FileWallpaperStore(root))
        assertNull(repository.load())
        val original = File(scratch, "original.png").apply { writeBytes(image(Bitmap.CompressFormat.PNG, true)) }
        assertNull(repository.importImage { importer.prepare({ original.inputStream() }).file })
        val id = repository.state.value.candidate!!.id
        assertTrue(original.delete())
        assertNull(repository.preview(id))
        assertNull(repository.setPreferences(repository.state.value.effectivePreferences.copy(scope = WallpaperScope.GLOBAL, strength = 48)))
        assertNull(repository.apply(id))
        val before = repository.state.value.saved
        val cold = WallpaperRepository(FileWallpaperStore(root))
        assertNull(cold.load())
        assertEquals(before, cold.state.value.saved)
        assertTrue(cold.resolveImage(id).isFile)
        assertNull(cold.setPreferences(before.preferences.copy(enabled = false)))
        assertTrue(cold.resolveImage(id).isFile)
        assertNull(cold.setPreferences(before.preferences.copy(enabled = true)))
        val sentinel = File(root, "unrelated.txt").apply { writeText("keep") }
        assertNull(cold.reset())
        assertNull(cold.state.value.effectiveAsset)
        assertEquals("keep", sentinel.readText())
        assertEquals(0, root.listFiles()!!.count { it.name.matches(Regex("[0-9a-f]{64}\\.(jpg|png|webp)")) })
    }

    @Test fun jpegAndStaticWebpNormalizeWhileBrokenImageKeepsAppliedWallpaper() = isolated { scratch ->
        val importer = AndroidWallpaperImporter(File(scratch, "imports"))
        val repository = WallpaperRepository(FileWallpaperStore(File(scratch, "managed")))
        assertNull(repository.load())
        for (format in listOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.WEBP)) {
            val source = image(format, false)
            assertNull(repository.importImage { importer.prepare({ ByteArrayInputStream(source) }).file })
            val id = repository.state.value.candidate!!.id
            assertNull(repository.preview(id))
            assertNull(repository.apply(id))
        }
        val saved = repository.state.value.saved
        assertNotNull(repository.importImage { importer.prepare({ ByteArrayInputStream(byteArrayOf(1, 2, 3)) }).file })
        assertEquals(saved, repository.state.value.saved)
        assertTrue(repository.resolveImage(saved.asset!!.id).isFile)
        assertTrue(File(scratch, "imports").listFiles().isNullOrEmpty())
    }

    private fun image(format: Bitmap.CompressFormat, transparent: Boolean): ByteArray {
        val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.setHasAlpha(transparent)
            bitmap.eraseColor(if (transparent) Color.argb(100, 30, 90, 160) else Color.rgb(30, 90, 160))
            ByteArrayOutputStream().also { check(bitmap.compress(format, 90, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }

    private fun isolated(block: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "wallpaper-device-${UUID.randomUUID()}")
        check(root.mkdirs())
        try { block(root) }
        finally { check(root.deleteRecursively()) }
    }
}
