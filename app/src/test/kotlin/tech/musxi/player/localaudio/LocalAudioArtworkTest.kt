package tech.musxi.player.localaudio

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource
import tech.musxi.core.localaudio.LocalAudioMetadata

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ShadowMediaMetadataRetriever::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalAudioArtworkTest {
    @get:Rule val temporary = TemporaryFolder()
    private val uri = Uri.parse("content://local-artwork-test/document/a")
    private val metadata = LocalAudioMetadata("clip.mp4", durationMs = 60_000)
    private lateinit var app: Application

    @Before fun setup() {
        ShadowMediaMetadataRetriever.reset()
        app = RuntimeEnvironment.getApplication()
    }

    private fun directory() = File(temporary.root, "local-artwork-v1")

    private fun artwork(source: LocalArtworkFrameSource) = AndroidLocalAudioArtwork(directory(), source)

    /** Records every stream the artwork layer opens, including whether it was closed. */
    private class TrackedStream(bytes: ByteArray) : java.io.ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true }
    }

    /** An artwork layer over picked images; the media retriever is recorded but never expected. */
    private fun imageArtwork(bytes: ByteArray, opened: MutableList<TrackedStream> = mutableListOf(),
                            frameCalls: MutableList<Int> = mutableListOf()) =
        AndroidLocalAudioArtwork(
            directory(),
            LocalArtworkFrameSource { _, _, _ -> frameCalls += 1; null },
            LocalArtworkImageSource { TrackedStream(bytes).also(opened::add) },
        )

    private fun picture(width: Int = 800, height: Int = 600, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            for (x in 0 until width) {
                bitmap.setPixel(x, x % height, Color.rgb(x % 256, 120, 200 - x % 200))
            }
            return ByteArrayOutputStream().also { bitmap.compress(format, 100, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun frame(width: Int = 800, height: Int = 600): Bitmap =
        picture(width, height).let { BitmapFactory.decodeByteArray(it, 0, it.size)!! }

    private fun managedFiles(): List<String> = directory().listFiles()?.map { it.name }?.sorted() ?: emptyList()

    @Test fun embeddedPictureIsSampledScaledAndEncodedWithinTheLimit() {
        val bytes = picture(2400, 1200)
        val decoded = LocalArtworkImages.decodeBounded(bytes, LocalArtworkImages.MAX_DECODE_EDGE)!!
        try {
            assertTrue(maxOf(decoded.width, decoded.height) <= LocalArtworkImages.MAX_DECODE_EDGE)
            assertEquals(2, decoded.width / decoded.height.coerceAtLeast(1))
        } finally {
            decoded.recycle()
        }

        val large = frame(1600, 800)
        val scaled = LocalArtworkImages.scaleToMaxEdge(large, LocalArtworkImages.MAX_EDGE)
        try {
            assertEquals(512, scaled.width)
            assertEquals(256, scaled.height)
        } finally {
            scaled.recycle()
            large.recycle()
        }

        val jpeg = LocalArtworkImages.encodeJpeg(frame(64, 32))
        val reread = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertNotNull(reread)
        reread!!.recycle()
    }

    @Test fun samplingAndFitKeepAspectRatioInsideBounds() {
        assertEquals(1, LocalArtworkImages.sampleSize(512, 512))
        assertEquals(2, LocalArtworkImages.sampleSize(1025, 10))
        assertEquals(4, LocalArtworkImages.sampleSize(4000, 3000))
        assertEquals(1, LocalArtworkImages.sampleSize(0, 100))
        assertEquals(512 to 384, LocalArtworkImages.fitWithin(800, 600, 512))
        assertEquals(512 to 384, LocalArtworkImages.fitWithin(1000, 750, 512))
        assertEquals(512 to 512, LocalArtworkImages.fitWithin(4096, 4096, 512))
        assertEquals(1 to 1, LocalArtworkImages.fitWithin(0, 0, 512))
    }

    @Test fun identicalPicturesShareOneContentKeyAndOneManagedFile() {
        val bytes = picture()
        val artwork = artwork { _, _, _ -> LocalArtworkImages.decodeBounded(bytes, LocalArtworkImages.MAX_EDGE) }

        val first = artwork.prepare(uri, metadata)!!
        val second = artwork.prepare(uri, metadata)!!
        try {
            assertEquals(first.key, second.key)
            assertTrue(first.key.matches(Regex("[0-9a-f]{64}")))
            assertEquals(listOf(first.key + ".jpg"), managedFiles())
            assertEquals(File(directory(), first.key + ".jpg"), artwork.resolve(first.key))
        } finally {
            first.close()
            second.close()
        }
    }

    @Test fun openLeaseProtectsArtworkUntilTheLastHolderCloses() {
        val bytes = picture()
        val artwork = artwork { _, _, _ -> LocalArtworkImages.decodeBounded(bytes, LocalArtworkImages.MAX_EDGE) }
        val first = artwork.prepare(uri, metadata)!!
        val second = artwork.prepare(uri, metadata)!!
        val key = first.key

        artwork.prune(emptySet())
        assertNotNull(artwork.resolve(key))

        first.close()
        artwork.prune(emptySet())
        assertNotNull("A second holder must still protect the shared file", artwork.resolve(key))

        second.close()
        second.close()
        artwork.prune(emptySet())
        assertNull(artwork.resolve(key))
        assertTrue(managedFiles().isEmpty())
    }

    @Test fun pruneKeepsRetainedArtworkAndIgnoresForeignEntries() {
        val artwork = artwork { _, _, _ -> LocalArtworkImages.decodeBounded(picture(), LocalArtworkImages.MAX_EDGE) }
        val lease = artwork.prepare(uri, metadata)!!
        lease.close()
        assertTrue(directory().mkdirs() || directory().isDirectory)

        val keep = File(directory(), lease.key + ".jpg")
        val foreignText = File(directory(), "notes.txt").apply { writeText("keep me") }
        val shortHex = File(directory(), "abc.jpg").apply { writeText("keep me") }
        val abandonedStaging = File(directory(), "part-11111111-1111-1111-1111-111111111111.tmp").apply { writeText("stale") }
        val foreignStaging = File(directory(), "part-staging.tmp").apply { writeText("not ours") }

        artwork.prune(setOf(lease.key))
        assertNotNull(artwork.resolve(lease.key))
        assertTrue(foreignText.exists())
        assertTrue(shortHex.exists())
        assertTrue(foreignStaging.exists())
        assertFalse("Abandoned staging files are cleaned up", abandonedStaging.exists())

        artwork.prune(emptySet())
        assertFalse(keep.exists())
        assertTrue(foreignText.exists())
    }

    @Test fun onlyCanonicalRegularFilesOfThisLayerCanBeResolved() {
        val artwork = artwork { _, _, _ -> LocalArtworkImages.decodeBounded(picture(), LocalArtworkImages.MAX_EDGE) }
        val lease = artwork.prepare(uri, metadata)!!
        val key = lease.key
        try {
            assertNull(artwork.resolve("../" + key))
            assertNull(artwork.resolve(key.uppercase()))
            assertNull(artwork.resolve(key + ".jpg"))
            assertNull(artwork.resolve(key.dropLast(1)))
            assertNull(artwork.resolve(""))
            assertNull(artwork.resolve("0".repeat(65)))

            val asDirectory = File(directory(), "f".repeat(64) + ".jpg").apply { mkdirs() }
            assertNull(artwork.resolve("f".repeat(64)))
            asDirectory.delete()

            val outside = File(temporary.root, "outside.jpg").apply { writeText("not managed") }
            assertNull(artwork.resolve(outside.nameWithoutExtension))
            assertTrue(outside.exists())
        } finally {
            lease.close()
        }
    }

    @Test fun extractionFailureReturnsNullAndPublishesNothing() {
        val throwing = artwork { _, _, _ -> throw IllegalStateException("decoder exploded") }
        assertNull(throwing.prepare(uri, metadata))
        assertTrue(managedFiles().isEmpty())

        val missing = artwork { _, _, _ -> null }
        assertNull(missing.prepare(uri, metadata))
        assertTrue(managedFiles().isEmpty())
        // A later prune on the empty directory is a no-op rather than a failure.
        missing.prune(emptySet())
    }

    @Test fun cancellationIsRethrownAndLeavesNoStagingOrPendingReference() {
        var calls = 0
        val cancelling = artwork { _, _, _ -> LocalArtworkImages.decodeBounded(picture(), LocalArtworkImages.MAX_EDGE) }
        try {
            cancelling.prepare(uri, metadata) {
                calls++
                if (calls == 3) throw CancellationException("abandoned import")
            }
            fail("Cancellation must not be swallowed")
        } catch (expected: CancellationException) {
            assertEquals("abandoned import", expected.message)
        }
        assertTrue("Staging must be removed on cancellation", managedFiles().isEmpty())

        // The following successful prepare proves no pending reference leaked from the cancelled one.
        val lease = cancelling.prepare(uri, metadata)!!
        lease.close()
        cancelling.prune(emptySet())
        assertTrue(managedFiles().isEmpty())
    }

    @Test fun concurrentPruneCannotDeleteArtworkThatIsStillBeingPrepared() {
        val bytes = picture()
        val artwork = artwork { _, _, _ -> LocalArtworkImages.decodeBounded(bytes, LocalArtworkImages.MAX_EDGE) }
        var calls = 0
        val lease = artwork.prepare(uri, metadata) {
            calls++
            // The staging file exists at this point but is not published yet.
            if (calls == 4) artwork.prune(emptySet())
        }!!
        try {
            assertTrue(managedFiles().none { it.endsWith(".tmp") })
            assertNotNull(artwork.resolve(lease.key))
        } finally {
            lease.close()
        }
    }

    @Test fun videoFrameTimeAvoidsIntroAndNeverReachesTheEnd() {
        assertEquals(1_000L, artworkFrameTimeMs(null))
        assertEquals(1_000L, artworkFrameTimeMs(0))
        assertEquals(1_000L, artworkFrameTimeMs(5_000))
        assertEquals(6_000L, artworkFrameTimeMs(60_000))
        assertTrue(artworkFrameTimeMs(300) < 300)
        assertTrue(artworkFrameTimeMs(1_200) < 1_200)
    }

    @Test fun platformSourceDegradesToNoArtworkForAnUnreadableSelection() {
        val artwork = AndroidLocalAudioArtwork(app)
        assertNull(artwork.prepare(Uri.parse("content://local-artwork-test/document/missing"), metadata))
        assertEquals(null, artwork.resolve("a".repeat(64)))
    }

    @Test fun videoFrameIsRequestedAtTheBoundedSizeForTheTenPercentPosition() {
        val source = DataSource.toDataSource(uri.toString())
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO, "yes")
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, "1920")
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, "1080")
        val frame = frame(512, 288)
        ShadowMediaMetadataRetriever.addScaledFrame(source, 6_000_000L, 512, 288, frame)

        val artwork = AndroidLocalAudioArtwork(directory(), MediaMetadataArtworkFrameSource(app))
        val lease = artwork.prepare(uri, metadata)
        try {
            assertNotNull("A bounded video keyframe becomes the cover", lease)
            assertNotNull(artwork.resolve(lease!!.key))
        } finally {
            lease?.close()
            if (!frame.isRecycled) frame.recycle()
        }
    }

    @Test fun videoWithoutReportedDimensionsSkipsTheFrameInsteadOfDecodingIt() {
        val source = DataSource.toDataSource(uri.toString())
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO, "yes")
        val artwork = AndroidLocalAudioArtwork(directory(), MediaMetadataArtworkFrameSource(app))
        assertNull(artwork.prepare(uri, metadata))
        assertTrue(managedFiles().isEmpty())
    }

    @Test fun audioWithoutAnEmbeddedPictureProducesNoArtwork() {
        val source = DataSource.toDataSource(uri.toString())
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO, "no")
        val artwork = AndroidLocalAudioArtwork(directory(), MediaMetadataArtworkFrameSource(app))
        assertNull(artwork.prepare(uri, LocalAudioMetadata("song.mp3", durationMs = 5_000)))
        assertTrue(managedFiles().isEmpty())
    }

    @Test fun oversizedFullFramesAreSkippedWhenNoScaledApiIsAvailable() {
        assertEquals(512 to 288, LocalArtworkImages.frameRequest(1920, 1080, scaledApi = true))
        assertNull(LocalArtworkImages.frameRequest(7680, 4320, scaledApi = false))
        assertEquals(1920 to 1080, LocalArtworkImages.frameRequest(1920, 1080, scaledApi = false))
        assertNull(LocalArtworkImages.frameRequest(0, 1080, scaledApi = true))
    }

    @Test fun pickedPictureIsDecodedByStreamingAndStoredAsOneSharedCover() {
        val opened = mutableListOf<TrackedStream>()
        val artwork = imageArtwork(picture(1600, 800, Bitmap.CompressFormat.PNG), opened)

        val first = artwork.prepareImage(uri)!!
        val second = artwork.prepareImage(uri)!!
        try {
            assertEquals("The same picture must hash to one content key", first.key, second.key)
            assertEquals(listOf(first.key + ".jpg"), managedFiles())
            val stored = artwork.resolve(first.key)!!
            val decoded = stored.inputStream().use { BitmapFactory.decodeStream(it) }!!
            try {
                assertEquals(512, maxOf(decoded.width, decoded.height))
                assertEquals(2.0, decoded.width.toDouble() / decoded.height, 0.01)
            } finally {
                decoded.recycle()
            }
            // Each prepare opens the provider once, then reads EXIF/bounds/pixels from its spool.
            assertEquals(2, opened.size)
            assertTrue("Picked streams must be closed", opened.all { it.closed })
        } finally {
            first.close()
            second.close()
        }
    }

    @Test fun pickedPictureIsNeverTreatedAsAMediaSource() {
        val retrieverCalls = mutableListOf<Int>()
        val artwork = imageArtwork(picture(64, 64), frameCalls = retrieverCalls)
        val lease = artwork.prepareImage(uri)!!
        lease.close()
        assertNotNull(artwork.resolve(lease.key))
        assertTrue("A picked image must not reach the media retriever", retrieverCalls.isEmpty())
        assertNull("Automatic extraction still needs a real media file", artwork.prepare(uri, metadata))
        assertEquals(1, retrieverCalls.size)
    }

    @Test fun hugeOrElongatedPickedPicturesAreRejectedBeforeDecoding() {
        assertTrue(LocalArtworkImages.pickedSizeAccepted(4096, 4096))
        assertFalse(LocalArtworkImages.pickedSizeAccepted(20_000, 200))
        assertFalse(LocalArtworkImages.pickedSizeAccepted(4096, 4096, maxPixels = 1_000_000))
        assertFalse(LocalArtworkImages.pickedSizeAccepted(0, 100))

        val opened = mutableListOf<TrackedStream>()
        val bytes = picture(64, 64)
        assertNull(LocalArtworkImages.decodeImage({ TrackedStream(bytes).also(opened::add) },
            File(temporary.root, "too-large.tmp"), maxLongEdge = 32))
        assertEquals(1, opened.size)
        assertTrue(opened.all { it.closed })
    }

    @Test fun unreadableOrInvalidPickedPicturesPublishNothing() {
        val invalid = imageArtwork(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertNull("Random bytes are not a picture", invalid.prepareImage(uri))
        assertTrue(managedFiles().isEmpty())

        val missing = AndroidLocalAudioArtwork(directory(), LocalArtworkFrameSource { _, _, _ ->
            throw AssertionError("image passed to media retriever")
        }, LocalArtworkImageSource { null })
        assertNull(missing.prepareImage(uri))
        assertTrue(managedFiles().isEmpty())
    }

    @Test fun pickedPictureReadBudgetCutsOffANonImageStream() {
        val bytes = picture(64, 64)
        assertNull(LocalArtworkImages.decodeImage({ TrackedStream(bytes) },
            File(temporary.root, "budget.tmp"), maxBytes = 8))
        val guarded = LocalArtworkImages.BoundedReadStream(java.io.ByteArrayInputStream(bytes), limit = 16)
        assertThrows(java.io.IOException::class.java) {
            val chunk = ByteArray(32)
            while (guarded.read(chunk) >= 0) Unit
        }.also { guarded.close() }
    }

    @Test fun cancelledOrForbiddenPickedPictureLeavesNoArtworkBehind() {
        val cancelled = imageArtwork(picture(64, 64))
        try {
            cancelled.prepareImage(uri) { throw CancellationException("abandoned pick") }
            fail("Cancellation must not be swallowed")
        } catch (expected: CancellationException) {
            assertEquals("abandoned pick", expected.message)
        }
        assertTrue(managedFiles().isEmpty())

        // Cancelling once the staging file exists must remove it instead of leaving a fragment.
        val staged = imageArtwork(picture(64, 64))
        try {
            staged.prepareImage(uri) {
                if (managedFiles().any { it.startsWith("part-") }) throw CancellationException("abandoned late pick")
            }
            fail("Cancellation must not be swallowed")
        } catch (expected: CancellationException) {
            assertEquals("abandoned late pick", expected.message)
        }
        assertTrue(managedFiles().isEmpty())

        val forbidden = AndroidLocalAudioArtwork(directory(), LocalArtworkFrameSource { _, _, _ ->
            throw AssertionError("image passed to media retriever")
        }, LocalArtworkImageSource { throw SecurityException("grant revoked") })
        assertThrows(SecurityException::class.java) { forbidden.prepareImage(uri) }
        assertTrue("A revoked grant must publish nothing", managedFiles().isEmpty())
    }

    @Test fun artworkImplementationsWithoutImageSupportReportNoCover() {
        val audioOnly = object : LocalAudioArtwork {
            override fun prepare(uri: Uri, metadata: LocalAudioMetadata, checkCancelled: () -> Unit) = null
            override fun resolve(key: String): File? = null
            override fun prune(retained: Set<String>) = Unit
        }
        assertNull(audioOnly.prepareImage(uri))
    }
}
