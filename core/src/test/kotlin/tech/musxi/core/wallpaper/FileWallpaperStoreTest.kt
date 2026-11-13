package tech.musxi.core.wallpaper

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.CRC32
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileWallpaperStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun stageWriteAndColdReadRoundTripRealPng() {
        val directory = temporary.newFolder("round-trip")
        val store = FileWallpaperStore(directory)
        val asset = store.stage(png("source-a.png", 48, 64))
        val saved = SavedWallpaper(
            asset,
            WallpaperPreferences(
                enabled = true,
                scope = WallpaperScope.GLOBAL,
                strength = 55,
                frame = WallpaperFrame(centerX = 0.2f, centerY = 0.8f, zoom = 3f),
            ),
        )

        store.write(saved)

        assertEquals(saved, FileWallpaperStore(directory).read())
        assertEquals("png", asset.fileName.substringAfterLast('.'))
        assertEquals(File(directory, asset.fileName).length(), asset.bytes)
        assertTrue(File(directory, "current.wallpaper").isFile)
    }

    @Test
    fun legacyV1ManifestSeedsDefaultFrameAndKeepsImageAndSettings() {
        val directory = temporary.newFolder("legacy-v1")
        val asset = FileWallpaperStore(directory).stage(png("legacy.png", 40, 30))
        val legacy = WallpaperPreferences(enabled = true, scope = WallpaperScope.GLOBAL, strength = 62)
        installManifest(directory, v1Payload(asset, legacy))

        val cold = FileWallpaperStore(directory).read()

        assertEquals(asset, cold.asset)
        assertEquals(legacy, cold.preferences)
        assertEquals(WallpaperFrame(), cold.preferences.frame)
        assertTrue(FileWallpaperStore(directory).resolve(asset).isFile)
        val legacyManifest = File(directory, "current.wallpaper").readBytes()
        val repository = WallpaperRepository(FileWallpaperStore(directory))
        assertEquals(null, repository.load())
        assertEquals(cold, repository.state.value.saved)
        org.junit.Assert.assertArrayEquals(legacyManifest, File(directory, "current.wallpaper").readBytes())
        val updated = legacy.copy(frame = WallpaperFrame(0.1f, 0.9f, 3f))
        assertEquals(null, repository.setPreferences(updated))
        assertEquals(SavedWallpaper(asset, updated), FileWallpaperStore(directory).read())
    }

    @Test
    fun legacyV1ManifestWithoutAssetReadsDefaults() {
        val directory = temporary.newFolder("legacy-v1-empty")
        installManifest(directory, v1Payload(null, WallpaperPreferences(scope = WallpaperScope.GLOBAL, strength = 20)))

        assertEquals(SavedWallpaper(null, WallpaperPreferences(scope = WallpaperScope.GLOBAL, strength = 20)),
            FileWallpaperStore(directory).read())
    }

    @Test
    fun v2FrameSurvivesColdRead() {
        val directory = temporary.newFolder("v2-frame")
        val asset = FileWallpaperStore(directory).stage(png("framed.png", 48, 32))
        val frame = WallpaperFrame(centerX = 0.25f, centerY = 0.75f, zoom = 2.5f)
        val preferences = WallpaperPreferences(true, WallpaperScope.PLAYER, 45, frame)
        installManifest(directory, v2Payload(asset, preferences, frame))

        val cold = FileWallpaperStore(directory).read()

        assertEquals(asset, cold.asset)
        assertEquals(preferences, cold.preferences)
    }

    @Test
    fun invalidPersistedV2FramesAreRejectedAsUnavailable() {
        val source = png("source-invalid.png", 24, 24)
        val preferences = WallpaperPreferences(enabled = true)
        val rejected = listOf(
            "nan-x" to floatArrayOf(Float.NaN, 0.5f, 2f),
            "nan-y" to floatArrayOf(0.5f, Float.NaN, 2f),
            "nan-zoom" to floatArrayOf(0.5f, 0.5f, Float.NaN),
            "infinite-x" to floatArrayOf(Float.POSITIVE_INFINITY, 0.5f, 2f),
            "infinite-zoom" to floatArrayOf(0.5f, 0.5f, Float.NEGATIVE_INFINITY),
            "negative-x" to floatArrayOf(-0.01f, 0.5f, 2f),
            "x-above-one" to floatArrayOf(1.01f, 0.5f, 2f),
            "negative-y" to floatArrayOf(0.5f, -0.2f, 2f),
            "y-above-one" to floatArrayOf(0.5f, 1.2f, 2f),
            "zoom-below-one" to floatArrayOf(0.5f, 0.5f, 0.999f),
            "zoom-above-four" to floatArrayOf(0.5f, 0.5f, 4.01f),
        )
        rejected.forEach { (name, floats) ->
            val directory = temporary.newFolder("invalid-$name")
            val asset = FileWallpaperStore(directory).stage(source)
            installManifest(
                directory,
                v2Payload(asset, preferences, WallpaperFrame(floats[0], floats[1], floats[2])),
            )

            assertEquals(name, WallpaperError.SAVED_IMAGE_UNAVAILABLE, failureOf { FileWallpaperStore(directory).read() })
            assertTrue(name, File(directory, "current.wallpaper").isFile)
            assertTrue(name, File(directory, asset.fileName).isFile)
        }
    }

    @Test
    fun unknownVersionTruncationTrailingDataAndBadChecksumAreRejected() {
        val source = png("malformed-source.png", 20, 20)
        val directory = temporary.newFolder("malformed")
        val asset = FileWallpaperStore(directory).stage(source)
        val preferences = WallpaperPreferences(enabled = true)
        val manifest = File(directory, "current.wallpaper")

        val unknown = temporary.newFolder("unknown-version")
        val unknownAsset = FileWallpaperStore(unknown).stage(source)
        installManifest(unknown, rawPayload(version = 3, unknownAsset, preferences, WallpaperFrame()))
        assertEquals(WallpaperError.SAVED_IMAGE_UNAVAILABLE, failureOf { FileWallpaperStore(unknown).read() })

        val zero = temporary.newFolder("zero-version")
        val zeroAsset = FileWallpaperStore(zero).stage(source)
        installManifest(zero, rawPayload(version = 0, zeroAsset, preferences, WallpaperFrame()))
        assertEquals(WallpaperError.SAVED_IMAGE_UNAVAILABLE, failureOf { FileWallpaperStore(zero).read() })

        val raw = payload(version = 2, asset, preferences, preferences.frame)
        installManifest(directory, checksummed(raw.copyOf(raw.size - 4)))
        assertEquals(WallpaperError.SAVED_IMAGE_UNAVAILABLE, failureOf { FileWallpaperStore(directory).read() })

        installManifest(directory, checksummed(raw + byteArrayOf(7)))
        assertEquals(WallpaperError.SAVED_IMAGE_UNAVAILABLE, failureOf { FileWallpaperStore(directory).read() })

        val bytes = checksummed(raw)
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 0xff).toByte()
        manifest.writeBytes(bytes)
        assertEquals(WallpaperError.SAVED_IMAGE_UNAVAILABLE, failureOf { FileWallpaperStore(directory).read() })
    }

    @Test
    fun writingInvalidFrameKeepsCommittedManifestAndImage() {
        val directory = temporary.newFolder("invalid-write")
        val store = FileWallpaperStore(directory)
        val asset = store.stage(png("kept.png", 36, 24))
        val saved = SavedWallpaper(asset, WallpaperPreferences(enabled = true, strength = 40))
        store.write(saved)
        val manifest = File(directory, "current.wallpaper")
        val committed = manifest.readBytes()
        val image = File(directory, asset.fileName).readBytes()

        listOf(
            WallpaperFrame(centerX = Float.NaN),
            WallpaperFrame(centerY = 1.5f),
            WallpaperFrame(zoom = 0.5f),
            WallpaperFrame(zoom = Float.POSITIVE_INFINITY),
        ).forEach { frame ->
            assertEquals(WallpaperError.INVALID_ARGUMENT, failureOf {
                store.write(saved.copy(preferences = saved.preferences.copy(frame = frame)))
            })
            org.junit.Assert.assertArrayEquals(committed, manifest.readBytes())
            org.junit.Assert.assertArrayEquals(image, File(directory, asset.fileName).readBytes())
        }

        assertEquals(saved, store.read())
    }

    @Test
    fun adjustingFramePreservesManagedImageBytesAndHash() {
        val directory = temporary.newFolder("reframe")
        val store = FileWallpaperStore(directory)
        val asset = store.stage(png("reframe.png", 64, 32))
        store.write(SavedWallpaper(asset, WallpaperPreferences(enabled = true, strength = 55)))
        val image = File(directory, asset.fileName)
        val original = image.readBytes()

        val reframed = WallpaperPreferences(
            enabled = true,
            scope = WallpaperScope.GLOBAL,
            strength = 55,
            frame = WallpaperFrame(centerX = 0.15f, centerY = 0.35f, zoom = 3.75f),
        )
        val applied = store.read()
        val repository = WallpaperRepository(FileWallpaperStore(directory))
        assertEquals(null, repository.load())
        assertEquals(null, repository.preview(asset.id))
        assertEquals(null, repository.setPreferences(reframed))
        assertEquals(applied, FileWallpaperStore(directory).read())
        assertEquals(null, repository.cancel())
        assertEquals(applied.preferences, repository.state.value.effectivePreferences)
        assertEquals(null, repository.preview(asset.id))
        assertEquals(null, repository.setPreferences(reframed))
        assertEquals(null, repository.apply(asset.id))

        val cold = FileWallpaperStore(directory).read()
        assertEquals(SavedWallpaper(asset, reframed), cold)
        assertEquals(asset.fileName, cold.asset!!.fileName)
        org.junit.Assert.assertArrayEquals(original, image.readBytes())
        assertTrue(FileWallpaperStore(directory).resolve(asset).isFile)
    }

    @Test
    fun tamperedManagedImageNeverSurfacesThroughResolveOrColdRead() {
        val directory = temporary.newFolder("tampered")
        val store = FileWallpaperStore(directory)
        val asset = store.stage(png("source.png", 32, 32))
        store.write(SavedWallpaper(asset, WallpaperPreferences(enabled = true)))
        assertTrue(store.resolve(asset).isFile)
        val managed = File(directory, asset.fileName)
        val bytes = managed.readBytes()
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 0xff).toByte()
        managed.writeBytes(bytes)

        assertThrows(WallpaperFailure::class.java) { store.resolve(asset) }
        assertThrows(WallpaperFailure::class.java) { FileWallpaperStore(directory).read() }

        managed.writeBytes(bytes.copyOf(asset.bytes.toInt() - 1))
        assertEquals(WallpaperError.SAVED_IMAGE_UNAVAILABLE, failureOf { store.resolve(asset) })
    }

    @Test
    fun corruptedManifestFailsInsteadOfReturningDefaults() {
        val directory = temporary.newFolder("manifest")
        val store = FileWallpaperStore(directory)
        store.write(SavedWallpaper(store.stage(png("source.png", 20, 20)), WallpaperPreferences(enabled = true)))
        val manifest = File(directory, "current.wallpaper")
        val bytes = manifest.readBytes()
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 0xff).toByte()
        manifest.writeBytes(bytes)

        assertEquals(WallpaperError.SAVED_IMAGE_UNAVAILABLE, failureOf { FileWallpaperStore(directory).read() })

        val missingDirectory = temporary.newFolder("missing")
        val missing = WallpaperAsset("b".repeat(64), WallpaperImageInfo(WallpaperFormat.PNG, 16, 16), 128)
        assertEquals(WallpaperError.SAVED_IMAGE_UNAVAILABLE, failureOf { FileWallpaperStore(missingDirectory).write(SavedWallpaper(missing)) })
        assertFalse(File(missingDirectory, "current.wallpaper").exists())
    }

    @Test
    fun pruneCollectsOrphansAndTemporariesButKeepsLiveAndForeignFiles() {
        val directory = temporary.newFolder("prune")
        val store = FileWallpaperStore(directory)
        val kept = store.stage(png("kept.png", 24, 24))
        val orphan = store.stage(png("orphan.png", 26, 26))
        val temporaryFile = File(directory, ".wallpaper-prune.tmp").apply { writeBytes("partial".toByteArray()) }
        val notes = File(directory, "notes.txt").apply { writeBytes("user notes".toByteArray()) }
        val lookalike = File(directory, "${"d".repeat(64)}.txt").apply { writeBytes("not ours".toByteArray()) }

        store.prune(setOf(kept))

        assertTrue(File(directory, kept.fileName).exists())
        assertFalse(File(directory, orphan.fileName).exists())
        assertFalse(temporaryFile.exists())
        assertTrue(notes.exists())
        assertTrue(lookalike.exists())
    }

    @Test
    fun stageRejectsImageBeyondDisplayBudget() {
        val directory = temporary.newFolder("too-large")
        val oversized = temporary.newFile("oversized.png")
        RandomAccessFile(oversized, "rw").use { it.setLength(WallpaperLimits.MAX_DISPLAY_BYTES + 1) }

        assertEquals(WallpaperError.IMAGE_TOO_LARGE, failureOf { FileWallpaperStore(directory).stage(oversized) })
        assertTrue(directory.listFiles()!!.none { it.name.endsWith(".png") })
    }

    @Test
    fun symlinkedManifestIsRejected() {
        val directory = temporary.newFolder("symlink")
        val store = FileWallpaperStore(directory)
        store.write(SavedWallpaper())
        val manifest = File(directory, "current.wallpaper")
        val outside = temporary.newFile("outside.wallpaper").apply { writeBytes(manifest.readBytes()) }
        manifest.delete()
        try {
            Files.createSymbolicLink(manifest.toPath(), outside.toPath())
        } catch (error: Exception) {
            assumeNoException("Platform does not allow unprivileged symbolic links", error)
        }

        assertEquals(WallpaperError.SAVED_IMAGE_UNAVAILABLE, failureOf { FileWallpaperStore(directory).read() })
    }

    private fun png(name: String, width: Int, height: Int): File {
        val file = File(temporary.root, name)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.fillRect(0, 0, width, height)
        } finally {
            graphics.dispose()
        }
        assertTrue(ImageIO.write(image, "png", file))
        return file
    }

    @Test
    fun disablingRetainsManagedImageAndFailedReplacementPreservesManifest() {
        val directory = temporary.newFolder("disabled")
        val repository = WallpaperRepository(FileWallpaperStore(directory))
        assertEquals(null, repository.load())
        assertEquals(null, repository.choose(png("source-disabled.png", 24, 32)))
        val asset = repository.state.value.candidate!!
        assertEquals(null, repository.preview(asset.id))
        assertEquals(null, repository.apply(asset.id))
        assertEquals(null, repository.setPreferences(repository.state.value.saved.preferences.copy(enabled = false)))
        val store = FileWallpaperStore(directory)
        val cold = store.read()
        assertFalse(cold.preferences.enabled)
        assertEquals(asset, cold.asset)
        assertTrue(store.resolve(asset).isFile)
        val manifest = File(directory, "current.wallpaper").readBytes()
        val absent = asset.copy(id = "e".repeat(64))
        assertThrows(WallpaperFailure::class.java) { store.write(SavedWallpaper(absent)) }
        org.junit.Assert.assertArrayEquals(manifest, File(directory, "current.wallpaper").readBytes())
        assertEquals(cold, store.read())
    }

    private fun failureOf(block: () -> Unit): WallpaperError =
        assertThrows(WallpaperFailure::class.java) { block() }.reason

    /** Hand-written v1 manifest: magic, version, asset, enabled/scope/strength, CRC32. No frame. */
    private fun v1Payload(asset: WallpaperAsset?, preferences: WallpaperPreferences): ByteArray =
        checksummed(payload(version = 1, asset = asset, preferences = preferences, frame = null))

    /** Hand-written v2 manifest writes the frame after strength; floats may be replaced to fake bad data. */
    private fun v2Payload(
        asset: WallpaperAsset?,
        preferences: WallpaperPreferences,
        frame: WallpaperFrame = WallpaperFrame(),
    ): ByteArray = checksummed(payload(version = 2, asset = asset, preferences = preferences, frame = frame))

    private fun rawPayload(
        version: Int,
        asset: WallpaperAsset?,
        preferences: WallpaperPreferences,
        frame: WallpaperFrame,
    ): ByteArray = checksummed(payload(version, asset, preferences, frame))

    private fun payload(
        version: Int,
        asset: WallpaperAsset?,
        preferences: WallpaperPreferences,
        frame: WallpaperFrame?,
    ): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(0x4d585750)
            output.writeInt(version)
            output.writeBoolean(asset != null)
            asset?.let {
                output.writeUTF(it.id)
                output.writeUTF(it.info.format.name)
                output.writeInt(it.info.width)
                output.writeInt(it.info.height)
                output.writeLong(it.bytes)
            }
            output.writeBoolean(preferences.enabled)
            output.writeUTF(preferences.scope.name)
            output.writeInt(preferences.strength)
            frame?.let {
                output.writeFloat(it.centerX)
                output.writeFloat(it.centerY)
                output.writeFloat(it.zoom)
            }
        }
    }.toByteArray()

    private fun checksummed(payload: ByteArray): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(payload)
            output.writeLong(CRC32().apply { update(payload) }.value)
        }
    }.toByteArray()

    private fun installManifest(directory: File, manifestBytes: ByteArray) {
        File(directory, "current.wallpaper").writeBytes(manifestBytes)
    }
}
