package tech.musxi.player.wallpaper

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import java.io.File
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import tech.musxi.core.wallpaper.FileWallpaperStore
import tech.musxi.core.wallpaper.SavedWallpaper
import tech.musxi.core.wallpaper.WallpaperError
import tech.musxi.core.wallpaper.WallpaperFrame
import tech.musxi.core.wallpaper.WallpaperPreferences
import tech.musxi.core.wallpaper.WallpaperSlot

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AppWallpaperRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun expandedStrengthAndValidationAreForwardedForBothFacades() {
        val context = context("expanded-strength")
        val playerSaved = seed(context, WallpaperSlot.PLAYER, 0xff335577.toInt())
        val interfaceSaved = seed(context, WallpaperSlot.INTERFACE, 0xff775533.toInt())
        val pool = AppWallpaperPool.get(context)
        val directory = File(context.filesDir, "wallpapers-v1")
        for ((repository, slot, original) in listOf(
            Triple(pool.player, WallpaperSlot.PLAYER, playerSaved),
            Triple(pool.interfaceWallpaper, WallpaperSlot.INTERFACE, interfaceSaved),
        )) {
            val id = original.asset!!.id
            success { repository.preview(id, it) }
            val draft = original.preferences.copy(strength = 120)
            success { repository.setPreferences(draft, it) }
            assertEquals(draft, repository.state.value.draft)
            assertEquals(original, FileWallpaperStore(directory, slot).read())
            for (invalid in listOf(-1, 151)) {
                error(WallpaperError.INVALID_ARGUMENT) { repository.setPreferences(draft.copy(strength = invalid), it) }
                assertEquals(draft, repository.state.value.draft)
                assertEquals(original, FileWallpaperStore(directory, slot).read())
            }
            success { repository.apply(id, it) }
            assertEquals(draft, FileWallpaperStore(directory, slot).read().preferences)
            success { repository.setPreferences(draft.copy(strength = WallpaperPreferences.MAX_STRENGTH), it) }
            val committed = repository.state.value.saved
            assertEquals(150, committed.preferences.strength)
            error(WallpaperError.INVALID_ARGUMENT) {
                repository.setPreferences(committed.preferences.copy(strength = 151), it)
            }
            assertEquals(committed, FileWallpaperStore(directory, slot).read())
        }
    }

    @Test fun originalConstructorJoinsTheSharedPoolAndBothSlotsLoadBeforeCommands() {
        val context = context("shared-facades")
        val saved = seed(context, WallpaperSlot.INTERFACE, 0xff224488.toInt())
        val pool = AppWallpaperPool.get(context)
        val original = AppWallpaperRepository(context)

        assertSame(pool.player.state, original.state)
        assertSame(pool, AppWallpaperPool.get(context))
        success { original.choose(null, it) }

        assertTrue(pool.player.state.value.loaded)
        assertTrue(pool.interfaceWallpaper.state.value.loaded)
        assertEquals(SavedWallpaper(), original.state.value.saved)
        assertEquals(saved, pool.interfaceWallpaper.state.value.saved)
        file(pool.interfaceWallpaper, saved.asset!!.id).also { assertTrue(it.isFile) }
        assertFalse(File(context.filesDir, "wallpapers-v1/current.wallpaper").exists())
    }

    @Test fun slotCommandsAndImageFailuresOnlyAffectTheirOwnStateAndCallbacksUseMain() {
        val context = context("independent")
        val saved = seed(context, WallpaperSlot.PLAYER, 0xff335577.toInt())
        val other = seed(context, WallpaperSlot.INTERFACE, 0xff335577.toInt())
        val pool = AppWallpaperPool.get(context)
        val player = pool.player
        val ui = pool.interfaceWallpaper
        success { ui.preview(other.asset!!.id, it) }
        val draft = ui.state.value.effectivePreferences.copy(strength = 90, frame = WallpaperFrame(0.2f, 0.8f, 3f))
        success { ui.setPreferences(draft, it) }
        val playerSettings = saved.preferences.copy(enabled = false, strength = 20, frame = WallpaperFrame(0.8f, 0.1f, 2f))
        success { player.setPreferences(playerSettings, it) }

        assertEquals(draft, ui.state.value.draft)
        assertEquals(playerSettings, player.state.value.saved.preferences)
        success { ui.cancel(it) }
        assertEquals(other, ui.state.value.saved)
        success { ui.preview(other.asset!!.id, it) }
        success { ui.setPreferences(draft, it) }
        success { ui.apply(other.asset!!.id, it) }
        val uiBeforeFailure = ui.state.value

        await(player.imageFailed(saved.asset!!.id))
        assertEquals(WallpaperError.SAVED_IMAGE_UNAVAILABLE, player.state.value.error)
        assertEquals(uiBeforeFailure, ui.state.value)
        assertTrue(file(ui, other.asset!!.id).isFile)
        success { player.reset(it) }
        assertEquals(uiBeforeFailure.saved, ui.state.value.saved)
        assertTrue(file(ui, other.asset!!.id).isFile)
        assertEquals(SavedWallpaper(other.asset, draft), FileWallpaperStore(File(context.filesDir, "wallpapers-v1"), WallpaperSlot.INTERFACE).read())
    }

    @Test fun chooseSavedWorksInBothDirectionsWithoutCopyingSourceSettingsOrBytes() {
        val context = context("reuse")
        val source = seed(context, WallpaperSlot.PLAYER, 0xff553311.toInt()).let {
            it.copy(preferences = it.preferences.copy(enabled = false, strength = 80, frame = WallpaperFrame(0.1f, 0.9f, 4f)))
        }
        FileWallpaperStore(File(context.filesDir, "wallpapers-v1")).write(source)
        val target = seed(context, WallpaperSlot.INTERFACE, 0xff113355.toInt())
        val pool = AppWallpaperPool.get(context)
        val player = pool.player
        val ui = pool.interfaceWallpaper
        val sourceId = source.asset!!.id
        success { ui.preview(target.asset!!.id, it) }
        val draft = ui.state.value.effectivePreferences.copy(strength = 75, frame = WallpaperFrame(0.2f, 0.7f, 3f))
        success { ui.setPreferences(draft, it) }
        val sourceBefore = player.state.value

        success { ui.chooseSaved(player, it) }

        assertEquals(sourceBefore, player.state.value)
        assertEquals(source.asset, ui.state.value.candidate)
        assertEquals(draft.copy(frame = WallpaperFrame()), ui.state.value.draft)
        assertEquals(target, ui.state.value.saved)
        assertEquals(file(player, sourceId).canonicalFile, file(ui, sourceId).canonicalFile)
        success { ui.preview(sourceId, it) }
        success { ui.apply(sourceId, it) }
        success { player.chooseSaved(ui, it) }
        assertEquals(source.asset, player.state.value.candidate)
        success { player.cancel(it) }
        success { player.reset(it) }
        assertTrue(file(ui, sourceId).isFile)
        assertEquals(1, File(context.filesDir, "wallpapers-v1").listFiles()!!.count { it.extension in listOf("jpg", "png", "webp") })
    }

    @Test fun failedReuseAndInvalidInputPreserveTheOtherSlotAndCurrentDraft() {
        val context = context("errors")
        val target = seed(context, WallpaperSlot.INTERFACE, 0xff225588.toInt())
        val pool = AppWallpaperPool.get(context)
        val ui = pool.interfaceWallpaper
        val player = pool.player
        success { ui.preview(target.asset!!.id, it) }
        val original = ui.state.value
        error(WallpaperError.NO_IMAGE) { ui.chooseSaved(player, it) }
        assertEquals(original.saved, ui.state.value.saved)
        assertEquals(original.draft, ui.state.value.draft)
        val sourceBefore = player.state.value
        error(WallpaperError.INVALID_ARGUMENT) {
            ui.setPreferences(original.effectivePreferences.copy(frame = WallpaperFrame(zoom = Float.NaN)), it)
        }
        assertEquals(original.draft, ui.state.value.draft)
        error(WallpaperError.STALE_CANDIDATE) { ui.apply("f".repeat(64), it) }
        error(WallpaperError.READ_FAILED) { ui.choose(Uri.parse("file:///not-an-input"), it) }
        success { ui.choose(null, it) }
        assertEquals(original.saved, ui.state.value.saved)
        assertEquals(original.draft, ui.state.value.draft)
        assertEquals(sourceBefore, player.state.value)
    }

    @Test fun repositoriesFromDifferentPoolsCannotReuseFiles() {
        val context = context("target-pool")
        val otherContext = context("other-pool")
        val saved = seed(context, WallpaperSlot.PLAYER, 0xff123456.toInt())
        seed(otherContext, WallpaperSlot.PLAYER, 0xff654321.toInt())
        val target = AppWallpaperPool.get(context).player
        val source = AppWallpaperPool.get(otherContext).player
        success { source.choose(null, it) }
        val sourceBefore = source.state.value

        error(WallpaperError.INVALID_ARGUMENT) { target.chooseSaved(source, it) }

        assertEquals(saved, target.state.value.saved)
        assertNull(target.state.value.candidate)
        assertEquals(sourceBefore, source.state.value)
        assertTrue(file(target, saved.asset!!.id).isFile)
    }

    private fun context(name: String): Context {
        val files = temporary.newFolder("$name-files")
        val cache = temporary.newFolder("$name-cache")
        return object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = cache
        }
    }

    private fun seed(context: Context, slot: WallpaperSlot, color: Int): SavedWallpaper {
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        val source = temporary.newFile()
        try {
            bitmap.eraseColor(color)
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val store = FileWallpaperStore(File(context.filesDir, "wallpapers-v1"), slot)
        return SavedWallpaper(store.stage(source), WallpaperPreferences(enabled = true, strength = 45)).also(store::write)
    }

    private fun success(command: ((WallpaperError?) -> Unit) -> Job) = error(null, command)

    private fun error(expected: WallpaperError?, command: ((WallpaperError?) -> Unit) -> Job) {
        var called = false
        await(command { result ->
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            assertEquals(expected, result)
            called = true
        })
        assertTrue("Callback was not delivered", called)
    }

    private fun file(repository: AppWallpaperRepository, id: String): File {
        var file: File? = null
        await(repository.imageFile(id) {
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            file = it.getOrThrow()
        })
        return file!!
    }

    private fun await(job: Job) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!job.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Wallpaper command timed out", job.isCompleted)
        assertFalse("Wallpaper command was cancelled", job.isCancelled)
    }
}
