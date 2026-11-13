package tech.musxi.core.wallpaper

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WallpaperRepositoryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val savedId = "a".repeat(64)
    private val savedAsset = WallpaperAsset(savedId, WallpaperImageInfo(WallpaperFormat.JPEG, 1_080, 1_920), 4_096)

    @Test
    fun loadPublishesSavedSnapshotAndCommandsBeforeLoadAreRejected() {
        val store = RecordingStore().apply {
            saved = SavedWallpaper(savedAsset, WallpaperPreferences(enabled = true, scope = WallpaperScope.GLOBAL, strength = 60))
        }
        val repository = WallpaperRepository(store)

        assertFalse(repository.state.value.loaded)
        assertEquals(WallpaperError.STORAGE_FAILED, repository.choose(image("early")))
        assertEquals(WallpaperError.STORAGE_FAILED, repository.preview(savedId))
        assertEquals(WallpaperError.STORAGE_FAILED, repository.state.value.error)
        assertTrue(store.staged.isEmpty())
        assertTrue(store.written.isEmpty())

        assertNull(repository.load())
        val state = repository.state.value
        assertTrue(state.loaded)
        assertFalse(state.busy)
        assertNull(state.error)
        assertEquals(store.saved, state.saved)
        assertEquals(savedAsset, state.effectiveAsset)
        assertEquals(WallpaperScope.GLOBAL, state.effectivePreferences.scope)
        assertEquals(savedAsset.fileName, repository.resolveImage(savedId).name)
        assertEquals(WallpaperError.STALE_CANDIDATE, expectFailure { repository.resolveImage("f".repeat(64)) })
    }

    @Test
    fun cancelAfterPreviewDiscardsDraftWithoutTouchingSavedManifest() {
        val store = RecordingStore().apply {
            saved = SavedWallpaper(savedAsset, WallpaperPreferences(enabled = true, strength = 40, frame = WallpaperFrame(0.2f, 0.7f, 2f)))
        }
        val repository = loaded(store)
        val staged = stage(repository, "cancel")

        assertNull(repository.preview(staged.id))
        assertTrue(repository.state.value.previewing)
        assertEquals(WallpaperPreferences(enabled = true, strength = 40), repository.state.value.draft)
        val draft = WallpaperPreferences(enabled = true, scope = WallpaperScope.GLOBAL, strength = 85, frame = WallpaperFrame(0.8f, 0.3f, 3f))
        assertNull(repository.setPreferences(draft))
        assertEquals(draft, repository.state.value.draft)
        assertEquals(staged, repository.state.value.effectiveAsset)
        assertTrue(store.written.isEmpty())

        assertNull(repository.cancel())
        val state = repository.state.value
        assertFalse(state.previewing)
        assertNull(state.candidate)
        assertNull(state.draft)
        assertNull(state.error)
        assertEquals(store.saved, state.saved)
        assertEquals(WallpaperFrame(0.2f, 0.7f, 2f), state.effectivePreferences.frame)
        assertEquals(savedAsset, state.effectiveAsset)
        assertTrue(store.written.isEmpty())
    }

    @Test
    fun applyCommitsCandidateWithDraftSoColdReadReturnsIt() {
        val store = RecordingStore().apply { saved = SavedWallpaper(savedAsset) }
        val repository = loaded(store)
        val staged = stage(repository, "apply")
        assertNull(repository.preview(staged.id))
        val committed = WallpaperPreferences(enabled = true, scope = WallpaperScope.GLOBAL, strength = 72, frame = WallpaperFrame(0.1f, 0.9f, 4f))
        assertNull(repository.setPreferences(committed))
        assertTrue(store.written.isEmpty())

        assertNull(repository.apply(staged.id))

        val applied = SavedWallpaper(staged, committed)
        assertEquals(listOf(applied), store.written)
        assertNull(repository.state.value.candidate)
        assertNull(repository.state.value.draft)
        val cold = loaded(RecordingStore().apply { saved = store.saved })
        assertEquals(applied, cold.state.value.saved)
        assertEquals(staged, cold.state.value.effectiveAsset)
    }

    @Test
    fun failedApplyKeepsCommittedImageAndPendingDraft() {
        val store = RecordingStore().apply { saved = SavedWallpaper(savedAsset) }
        val repository = loaded(store)
        val staged = stage(repository, "apply-failure")
        assertNull(repository.preview(staged.id))
        val draft = WallpaperPreferences(enabled = true, strength = 20, frame = WallpaperFrame(0.2f, 0.4f, 2f))
        assertNull(repository.setPreferences(draft))
        store.writeFailure = IOException("manifest write failed")

        assertEquals(WallpaperError.STORAGE_FAILED, repository.apply(staged.id))

        val state = repository.state.value
        assertEquals(WallpaperError.STORAGE_FAILED, state.error)
        assertTrue(state.previewing)
        assertEquals(staged, state.candidate)
        assertEquals(draft, state.draft)
        assertEquals(savedAsset, state.saved.asset)
        assertEquals(savedAsset, store.saved.asset)
        assertTrue(store.written.isEmpty())
    }

    @Test
    fun staleExpectedIdNeverAppliesCandidateOrSavedReplacement() {
        val store = RecordingStore().apply { saved = SavedWallpaper(savedAsset) }
        val repository = loaded(store)
        val staged = stage(repository, "stale")

        assertEquals(WallpaperError.STALE_CANDIDATE, repository.apply(staged.id))
        assertEquals(WallpaperError.STALE_CANDIDATE, repository.preview(savedId))
        assertFalse(repository.state.value.previewing)

        assertNull(repository.preview(staged.id))
        assertEquals(WallpaperError.STALE_CANDIDATE, repository.apply(savedId))
        assertTrue(repository.state.value.previewing)
        assertEquals(staged, repository.state.value.candidate)
        assertEquals(savedAsset, repository.state.value.saved.asset)
        assertTrue(store.written.isEmpty())
    }

    @Test
    fun failedStageKeepsSavedImageAndUnappliedDraft() {
        val store = RecordingStore().apply {
            saved = SavedWallpaper(savedAsset, WallpaperPreferences(enabled = true, strength = 40))
        }
        val repository = loaded(store)
        val staged = stage(repository, "pending")
        assertNull(repository.preview(staged.id))
        val draft = WallpaperPreferences(enabled = false, strength = 55, frame = WallpaperFrame(0.7f, 0.8f, 3f))
        assertNull(repository.setPreferences(draft))
        store.stageFailure = WallpaperFailure(WallpaperError.READ_FAILED)

        assertEquals(WallpaperError.READ_FAILED, repository.choose(image("rejected")))

        val state = repository.state.value
        assertEquals(WallpaperError.READ_FAILED, state.error)
        assertEquals(staged, state.candidate)
        assertTrue(state.previewing)
        assertEquals(draft, state.draft)
        assertEquals(store.saved, state.saved)
        assertTrue(store.written.isEmpty())
    }

    @Test
    fun importTransfersTemporaryFileOwnershipAndFailureKeepsSaved() {
        val store = RecordingStore().apply { saved = SavedWallpaper(savedAsset) }
        val repository = loaded(store)

        val rejected = temporary.newFile("rejected.png").apply { writeBytes("broken".toByteArray()) }
        store.stageFailure = WallpaperFailure(WallpaperError.INVALID_IMAGE)
        assertEquals(WallpaperError.INVALID_IMAGE, repository.importImage { rejected })
        assertFalse(rejected.exists())
        assertEquals(savedAsset, repository.state.value.saved.asset)
        assertNull(repository.state.value.candidate)

        store.stageFailure = null
        val accepted = temporary.newFile("accepted.png").apply { writeBytes("image-bytes".toByteArray()) }
        assertNull(repository.importImage { accepted })
        assertFalse(accepted.exists())
        assertEquals(sha256("image-bytes".toByteArray()), repository.state.value.candidate!!.id)
        assertEquals(savedAsset, repository.state.value.saved.asset)
    }

    @Test
    fun resetClearsSavedImageAndCollectsManagedFiles() {
        val store = RecordingStore().apply {
            saved = SavedWallpaper(savedAsset, WallpaperPreferences(enabled = true, frame = WallpaperFrame(0f, 1f, 4f)))
        }
        val repository = loaded(store)
        val staged = stage(repository, "reset")
        assertNull(repository.preview(staged.id))
        assertNull(repository.setPreferences(repository.state.value.effectivePreferences.copy(frame = WallpaperFrame(1f, 0f, 2f))))

        assertNull(repository.reset())

        val state = repository.state.value
        assertTrue(state.loaded)
        assertEquals(SavedWallpaper(), state.saved)
        assertEquals(SavedWallpaper(), store.saved)
        assertNull(state.candidate)
        assertFalse(state.previewing)
        assertNull(state.draft)
        assertNull(state.effectiveAsset)
        assertEquals(WallpaperFrame(), state.effectivePreferences.frame)
        assertEquals(emptySet<WallpaperAsset>(), store.pruned.last())
    }

    @Test
    fun replacingCandidateResetsOnlyFrameAndRepeatedPreviewKeepsEdits() {
        val store = RecordingStore().apply {
            saved = SavedWallpaper(savedAsset, WallpaperPreferences(enabled = true, strength = 45, frame = WallpaperFrame(0.1f, 0.9f, 2f)))
        }
        val repository = loaded(store)
        val first = stage(repository, "first-frame")
        // Before preview the saved wallpaper and its applied frame remain effective.
        assertNull(repository.state.value.draft)
        assertEquals(store.saved.preferences, repository.state.value.effectivePreferences)
        assertNull(repository.preview(first.id))
        assertEquals(WallpaperFrame(), repository.state.value.draft!!.frame)
        val draft = repository.state.value.effectivePreferences.copy(
            enabled = false, scope = WallpaperScope.GLOBAL, strength = 80, frame = WallpaperFrame(0.8f, 0.2f, 3f),
        )
        assertNull(repository.setPreferences(draft))
        assertNull(repository.preview(first.id))
        assertEquals(draft, repository.state.value.draft)

        val second = stage(repository, "second-frame")

        val centered = draft.copy(frame = WallpaperFrame())
        assertEquals(centered, repository.state.value.draft)
        assertEquals(store.saved, repository.state.value.saved)
        assertNull(repository.preview(second.id))
        assertEquals(centered, repository.state.value.draft)
        assertEquals(WallpaperError.STALE_CANDIDATE, repository.apply(first.id))
        assertTrue(store.written.isEmpty())
        assertNull(repository.apply(second.id))
        assertEquals(SavedWallpaper(second, centered), store.saved)
    }

    @Test
    fun alreadyAppliedImageCanBeReframedThenCancelledOrAppliedWithoutChoosing() {
        val preferences = WallpaperPreferences(enabled = false, strength = 50, frame = WallpaperFrame(0.2f, 0.6f, 2f))
        val store = RecordingStore().apply { saved = SavedWallpaper(savedAsset, preferences) }
        val repository = loaded(store)
        assertNull(repository.preview(savedId))
        assertEquals(preferences.copy(enabled = true), repository.state.value.draft)
        val adjusted = repository.state.value.effectivePreferences.copy(strength = 70, frame = WallpaperFrame(0.9f, 0.1f, 4f))
        assertNull(repository.setPreferences(adjusted))
        assertNull(repository.cancel())
        assertEquals(preferences, repository.state.value.effectivePreferences)
        assertTrue(store.written.isEmpty())
        assertNull(repository.preview(savedId))
        assertNull(repository.setPreferences(adjusted))
        assertNull(repository.apply(savedId))

        assertEquals(SavedWallpaper(savedAsset, adjusted), loaded(store).state.value.saved)
        assertTrue(store.staged.isEmpty())
        assertNull(repository.state.value.candidate)
    }

    @Test
    fun preferencesOutsidePreviewCommitFrameStrengthAndEnabledTogether() {
        val store = RecordingStore().apply { saved = SavedWallpaper(savedAsset, WallpaperPreferences(enabled = true)) }
        val repository = loaded(store)
        val updated = repository.state.value.effectivePreferences.copy(
            enabled = false, scope = WallpaperScope.GLOBAL, strength = 65, frame = WallpaperFrame(0.9f, 0.4f, 2.5f),
        )

        assertNull(repository.setPreferences(updated))

        assertEquals(SavedWallpaper(savedAsset, updated), store.saved)
        assertEquals(updated, loaded(store).state.value.effectivePreferences)
        assertNull(repository.state.value.draft)
        assertFalse(repository.state.value.previewing)
        assertEquals(listOf(store.saved), store.written)
        // Updating only the strength using copy preserves the frame, and vice versa.
        assertNull(repository.setPreferences(updated.copy(strength = 20)))
        assertNull(repository.setPreferences(repository.state.value.effectivePreferences.copy(frame = WallpaperFrame())))
        assertEquals(updated.copy(strength = 20, frame = WallpaperFrame()), store.saved.preferences)
    }

    @Test
    fun invalidFramesAreRejectedWithoutMutatingSavedOrDraft() {
        val invalid = listOf(
            WallpaperFrame(centerX = -0.01f), WallpaperFrame(centerX = 1.01f),
            WallpaperFrame(centerY = -0.01f), WallpaperFrame(centerY = 1.01f),
            WallpaperFrame(zoom = 0.99f), WallpaperFrame(zoom = 4.01f),
        ) + listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).flatMap {
            listOf(WallpaperFrame(centerX = it), WallpaperFrame(centerY = it), WallpaperFrame(zoom = it))
        }
        for (previewing in listOf(false, true)) {
            val store = RecordingStore().apply {
                saved = SavedWallpaper(savedAsset, WallpaperPreferences(enabled = true, frame = WallpaperFrame(0.2f, 0.8f, 2f)))
            }
            val repository = loaded(store)
            if (previewing) assertNull(repository.preview(savedId))
            val before = repository.state.value
            for (frame in invalid) {
                val attempted = before.effectivePreferences.copy(strength = 99, enabled = false, frame = frame)
                assertEquals("preview=$previewing, frame=$frame", WallpaperError.INVALID_ARGUMENT, repository.setPreferences(attempted))
                val state = repository.state.value
                assertEquals(before.saved, state.saved)
                assertEquals(before.draft, state.draft)
                assertEquals(before.effectivePreferences, state.effectivePreferences)
                assertFalse(state.busy)
                assertEquals(WallpaperError.INVALID_ARGUMENT, state.error)
                assertTrue(store.written.isEmpty())
            }
            // Exact bounds remain legal, and clear the previous validation error.
            for (frame in listOf(WallpaperFrame(0f, 1f, 1f), WallpaperFrame(1f, 0f, 4f))) {
                assertNull(repository.setPreferences(before.effectivePreferences.copy(frame = frame)))
                assertEquals(frame, repository.state.value.effectivePreferences.frame)
                assertNull(repository.state.value.error)
            }
        }
    }

    @Test
    fun delayedFailureForOldImageDoesNotDiscardNewerPreview() {
        val store = RecordingStore().apply { saved = SavedWallpaper(savedAsset, WallpaperPreferences(enabled = true)) }
        val repository = loaded(store)
        val staged = stage(repository, "newer")
        assertNull(repository.preview(staged.id))
        val draft = WallpaperPreferences(enabled = true, scope = WallpaperScope.GLOBAL, strength = 30)
        assertNull(repository.setPreferences(draft))

        repository.imageFailed(savedId)
        assertEquals(staged, repository.state.value.candidate)
        assertTrue(repository.state.value.previewing)
        assertEquals(draft, repository.state.value.draft)
        assertNull(repository.state.value.error)

        repository.imageFailed(staged.id)
        val state = repository.state.value
        assertNull(state.candidate)
        assertFalse(state.previewing)
        assertNull(state.draft)
        assertEquals(WallpaperError.INVALID_IMAGE, state.error)
        assertEquals(savedAsset, state.saved.asset)
        assertTrue(state.saved.preferences.enabled)
    }

    @Test
    fun unavailableSavedImageDisablesWallpaperButRetainsFile() {
        val store = RecordingStore().apply { saved = SavedWallpaper(savedAsset, WallpaperPreferences(enabled = true)) }
        val repository = loaded(store)

        repository.imageFailed(savedId)

        val state = repository.state.value
        assertEquals(savedAsset, state.saved.asset)
        assertFalse(state.saved.preferences.enabled)
        assertEquals(WallpaperError.SAVED_IMAGE_UNAVAILABLE, state.error)
        assertNull(state.effectiveAsset)
        assertTrue(store.written.isEmpty())

        store.missing += savedId
        val second = loaded(store)
        assertEquals(WallpaperError.SAVED_IMAGE_UNAVAILABLE, expectFailure { second.resolveImage(savedId) })
        assertEquals(WallpaperError.SAVED_IMAGE_UNAVAILABLE, second.state.value.error)
        assertEquals(savedAsset, second.state.value.saved.asset)
    }

    private fun loaded(store: WallpaperStore): WallpaperRepository =
        WallpaperRepository(store).also { assertNull(it.load()) }

    @Test
    fun failedCandidateThumbnailIsDiscardedBeforePreviewWithoutChangingSaved() {
        val store = RecordingStore().apply { saved = SavedWallpaper(savedAsset, WallpaperPreferences(enabled = true)) }
        val repository = loaded(store)
        val candidate = stage(repository, "broken-thumbnail")
        assertFalse(repository.state.value.previewing)
        repository.imageFailed(candidate.id)
        assertNull(repository.state.value.candidate)
        assertEquals(savedAsset, repository.state.value.effectiveAsset)
        assertEquals(WallpaperError.INVALID_IMAGE, repository.state.value.error)
        assertTrue(store.written.isEmpty())
    }

    private fun stage(repository: WallpaperRepository, tag: String): WallpaperAsset {
        assertNull(repository.choose(image(tag)))
        return repository.state.value.candidate!!
    }

    private fun image(tag: String): File =
        temporary.newFile("$tag-source.bin").apply { writeBytes(tag.toByteArray()) }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun expectFailure(block: () -> Unit): WallpaperError =
        assertThrows(WallpaperFailure::class.java) { block() }.reason

    /** Records commands instead of touching disk so failures can be injected deterministically. */
    private class RecordingStore : WallpaperStore {
        var saved = SavedWallpaper()
        var stageFailure: Exception? = null
        var writeFailure: Exception? = null
        val staged = mutableListOf<WallpaperAsset>()
        val written = mutableListOf<SavedWallpaper>()
        val pruned = mutableListOf<Set<WallpaperAsset>>()
        val missing = mutableSetOf<String>()

        override fun read(): SavedWallpaper = saved

        override fun stage(normalizedImage: File): WallpaperAsset {
            stageFailure?.let { throw it }
            val bytes = normalizedImage.readBytes()
            val asset = WallpaperAsset(
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
                WallpaperImageInfo(WallpaperFormat.PNG, 64, 48),
                bytes.size.toLong(),
            )
            staged += asset
            return asset
        }

        override fun write(saved: SavedWallpaper) {
            writeFailure?.let { throw it }
            written += saved
            this.saved = saved
        }

        override fun resolve(asset: WallpaperAsset): File {
            if (asset.id in missing) throw WallpaperFailure(WallpaperError.SAVED_IMAGE_UNAVAILABLE)
            return File("managed", asset.fileName)
        }

        override fun prune(keep: Set<WallpaperAsset>) {
            pruned += keep
        }
    }
}
