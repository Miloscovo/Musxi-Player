package tech.musxi.core.wallpaper

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import javax.imageio.ImageIO
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Strength is picture visibility in 0..150; 100..150 is the "beyond recommended" band.
 * The value object accepts out-of-range Ints so a stale UI cannot crash on copy(); commands and
 * the store validate instead, report INVALID_ARGUMENT and never clamp. Manifests keep version 2
 * with a plain Int strength, and v1/v2 values in 0..100 keep loading without a rewrite.
 */
class WallpaperStrengthTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val slots = listOf(WallpaperSlot.PLAYER, WallpaperSlot.INTERFACE)
    private val rejectedStrengths = listOf(-1, 151, Int.MIN_VALUE, Int.MAX_VALUE)

    @Test
    fun boundaryStrengthsRoundTripThroughBothSlots() {
        assertEquals(150, WallpaperPreferences.MAX_STRENGTH)
        assertEquals(35, WallpaperPreferences().strength)
        // Out-of-range values must construct and copy without throwing.
        assertEquals(-1, WallpaperPreferences(strength = -1).strength)
        assertEquals(151, WallpaperPreferences().copy(strength = 151).strength)

        for (slot in slots) {
            for (strength in listOf(0, 100, 150)) {
                val directory = temporary.newFolder("bounds-${slot.name.lowercase()}-$strength")
                val repository = WallpaperRepository(FileWallpaperStore(directory, slot))
                assertNull(repository.load())
                val asset = apply(repository, png("bounds-${slot.name}-$strength.png", 36, 48, 0x334455), strength)

                val cold = WallpaperRepository(FileWallpaperStore(directory, slot))
                assertNull(cold.load())
                assertEquals(strength, cold.state.value.saved.preferences.strength)
                assertEquals(asset, cold.state.value.saved.asset)
                assertEquals(asset.fileName, cold.resolveImage(asset.id).name)
            }
        }
    }

    @Test
    fun outOfRangeStrengthIsRejectedWithoutChangingSavedDraftOrImage() {
        for (slot in slots) {
            val directory = temporary.newFolder("reject-${slot.name.lowercase()}")
            val repository = WallpaperRepository(FileWallpaperStore(directory, slot))
            assertNull(repository.load())
            val asset = apply(repository, png("reject-${slot.name}.png", 32, 40, 0x445566), 120)
            val before = repository.state.value.saved
            val manifest = File(directory, slot.manifestName)
            val committed = manifest.readBytes()
            val image = File(directory, asset.fileName).readBytes()

            // Preview edits stay in memory, but they still reject out-of-range strengths.
            assertNull(repository.preview(asset.id))
            val draft = repository.state.value.draft!!
            for (strength in rejectedStrengths) {
                assertEquals(
                    "preview $strength in ${slot.name}",
                    WallpaperError.INVALID_ARGUMENT,
                    repository.setPreferences(draft.copy(strength = strength)),
                )
                assertEquals(draft, repository.state.value.draft)
                assertArrayEquals(committed, manifest.readBytes())
            }
            assertNull(repository.cancel())

            // Committing directly reports the same error and leaves the committed state alone.
            for (strength in rejectedStrengths) {
                assertEquals(
                    "commit $strength in ${slot.name}",
                    WallpaperError.INVALID_ARGUMENT,
                    repository.setPreferences(before.preferences.copy(strength = strength)),
                )
                assertEquals(before, repository.state.value.saved)
                assertEquals(before.preferences.frame, repository.state.value.saved.preferences.frame)
            }

            assertEquals(120, repository.state.value.saved.preferences.strength)
            assertArrayEquals(committed, manifest.readBytes())
            assertArrayEquals(image, File(directory, asset.fileName).readBytes())
            val cold = FileWallpaperStore(directory, slot).read()
            assertEquals(before, cold)
            assertEquals(asset, cold.asset)
        }
    }

    @Test
    fun draftAboveHundredNeverWritesUntilApplyAndApplySurvivesColdStart() {
        for (slot in slots) {
            val directory = temporary.newFolder("draft-${slot.name.lowercase()}")
            val repository = WallpaperRepository(FileWallpaperStore(directory, slot))
            assertNull(repository.load())
            val asset = apply(repository, png("draft-${slot.name}.png", 40, 30, 0x556677), 35)
            val manifest = File(directory, slot.manifestName)
            val base = manifest.readBytes()

            // Previewing a saved image and raising strength only touches the volatile draft.
            assertNull(repository.preview(asset.id))
            assertNull(repository.setPreferences(WallpaperPreferences(enabled = true, strength = 120)))
            assertEquals(120, repository.state.value.effectivePreferences.strength)
            assertEquals(35, repository.state.value.saved.preferences.strength)
            assertArrayEquals(base, manifest.readBytes())

            // Cancel drops the draft; the committed 35 stays.
            assertNull(repository.cancel())
            assertNull(repository.state.value.draft)
            assertEquals(35, repository.state.value.saved.preferences.strength)
            assertArrayEquals(base, manifest.readBytes())

            // Apply persists 120 and a cold start reads it back.
            assertNull(repository.preview(asset.id))
            assertNull(repository.setPreferences(WallpaperPreferences(enabled = true, strength = 120)))
            assertNull(repository.apply(asset.id))
            val applied = manifest.readBytes()
            val cold = WallpaperRepository(FileWallpaperStore(directory, slot))
            assertNull(cold.load())
            assertEquals(120, cold.state.value.saved.preferences.strength)
            assertEquals(asset, cold.state.value.saved.asset)
            assertArrayEquals(applied, manifest.readBytes())
        }
    }

    @Test
    fun legacyV1AndV2StrengthsFromZeroToHundredLoadWithoutRewriting() {
        val legacy = listOf(1 to 0, 2 to 62, 1 to 100)
        for (slot in slots) {
            legacy.forEachIndexed { index, entry ->
                val version = entry.first
                val strength = entry.second
                val directory = temporary.newFolder("legacy-${slot.name.lowercase()}-$index")
                val manifest = File(directory, slot.manifestName)
                manifest.writeBytes(manifestBytes(version, strength, if (version == 2) WallpaperFrame() else null))
                val original = manifest.readBytes()

                val library = WallpaperLibrary(directory)
                library.load()
                val target = target(library, slot)
                assertNull(target.state.value.error)
                assertEquals(strength, target.state.value.saved.preferences.strength)
                assertEquals(WallpaperFrame(), target.state.value.saved.preferences.frame)
                assertArrayEquals("v$version strength $strength in ${slot.name}", original, manifest.readBytes())
            }
        }
    }

    @Test
    fun crcValidManifestWithOutOfRangeStrengthIsRejectedWithoutRewrite() {
        listOf(-1, 151, Int.MIN_VALUE).forEachIndexed { index, strength ->
            val directory = temporary.newFolder("bad-strength-$index")
            val playerManifest = File(directory, WallpaperSlot.PLAYER.manifestName)
            playerManifest.writeBytes(manifestBytes(2, strength, WallpaperFrame()))
            val interfaceManifest = File(directory, WallpaperSlot.INTERFACE.manifestName)
            interfaceManifest.writeBytes(manifestBytes(2, 100, WallpaperFrame()))
            val playerBytes = playerManifest.readBytes()
            val interfaceBytes = interfaceManifest.readBytes()

            assertEquals(
                "store read $strength",
                WallpaperError.SAVED_IMAGE_UNAVAILABLE,
                failureOf { FileWallpaperStore(directory, WallpaperSlot.PLAYER).read() },
            )

            // A rejected player manifest must not take the interface slot down with it.
            val library = WallpaperLibrary(directory)
            library.load()
            assertTrue(library.player.state.value.loaded)
            assertEquals(WallpaperError.SAVED_IMAGE_UNAVAILABLE, library.player.state.value.error)
            assertEquals(SavedWallpaper(), library.player.state.value.saved)
            assertNull(library.interfaceWallpaper.state.value.error)
            assertEquals(100, library.interfaceWallpaper.state.value.saved.preferences.strength)
            assertArrayEquals(playerBytes, playerManifest.readBytes())
            assertArrayEquals(interfaceBytes, interfaceManifest.readBytes())
        }
    }

    @Test
    fun storeWriteRejectsOutOfRangeStrengthWithoutCreatingOrReplacingManifest() {
        for (slot in slots) {
            val fresh = temporary.newFolder("write-fresh-${slot.name.lowercase()}")
            val freshStore = FileWallpaperStore(fresh, slot)
            for (strength in rejectedStrengths) {
                assertEquals(
                    "fresh $strength in ${slot.name}",
                    WallpaperError.INVALID_ARGUMENT,
                    failureOf {
                        freshStore.write(SavedWallpaper(preferences = WallpaperPreferences(strength = strength)))
                    },
                )
            }
            assertFalse(File(fresh, slot.manifestName).exists())
            assertEquals(SavedWallpaper(), freshStore.read())

            val directory = temporary.newFolder("write-existing-${slot.name.lowercase()}")
            val store = FileWallpaperStore(directory, slot)
            val asset = store.stage(png("write-${slot.name}.png", 28, 28, 0x667788))
            val saved = SavedWallpaper(asset, WallpaperPreferences(true, WallpaperScope.PLAYER, 120))
            store.write(saved)
            val manifest = File(directory, slot.manifestName)
            val committed = manifest.readBytes()
            val image = File(directory, asset.fileName).readBytes()

            for (strength in rejectedStrengths) {
                assertEquals(
                    "existing $strength in ${slot.name}",
                    WallpaperError.INVALID_ARGUMENT,
                    failureOf { store.write(saved.copy(preferences = saved.preferences.copy(strength = strength))) },
                )
                assertArrayEquals(committed, manifest.readBytes())
                assertArrayEquals(image, File(directory, asset.fileName).readBytes())
            }
            assertEquals(saved, FileWallpaperStore(directory, slot).read())
        }
    }

    private fun apply(repository: WallpaperRepository, source: File, strength: Int): WallpaperAsset {
        assertNull(repository.choose(source))
        val asset = repository.state.value.candidate!!
        assertNull(repository.preview(asset.id))
        assertNull(repository.setPreferences(WallpaperPreferences(true, WallpaperScope.PLAYER, strength)))
        assertNull(repository.apply(asset.id))
        assertEquals(asset, repository.state.value.saved.asset)
        return asset
    }

    private fun target(library: WallpaperLibrary, slot: WallpaperSlot): WallpaperRepository =
        if (slot == WallpaperSlot.PLAYER) library.player else library.interfaceWallpaper

    private fun failureOf(block: () -> Unit): WallpaperError =
        assertThrows(WallpaperFailure::class.java) { block() }.reason

    private fun png(name: String, width: Int, height: Int, rgb: Int): File {
        val file = File(temporary.root, name)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = java.awt.Color(rgb)
            graphics.fillRect(0, 0, width, height)
        } finally {
            graphics.dispose()
        }
        assertTrue(ImageIO.write(image, "png", file))
        return file
    }

    /** Hand-written manifest: magic, version, empty asset, enabled/scope/strength, optional frame, CRC32. */
    private fun manifestBytes(version: Int, strength: Int, frame: WallpaperFrame?): ByteArray {
        val payload = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(0x4d585750)
                output.writeInt(version)
                output.writeBoolean(false)
                output.writeBoolean(false)
                output.writeUTF(WallpaperScope.PLAYER.name)
                output.writeInt(strength)
                frame?.let {
                    output.writeFloat(it.centerX)
                    output.writeFloat(it.centerY)
                    output.writeFloat(it.zoom)
                }
            }
        }.toByteArray()
        return payload + ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { it.writeLong(CRC32().apply { update(payload) }.value) }
        }.toByteArray()
    }
}
