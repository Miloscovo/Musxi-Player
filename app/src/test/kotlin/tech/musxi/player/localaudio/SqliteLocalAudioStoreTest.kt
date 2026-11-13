package tech.musxi.player.localaudio

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.musxi.core.localaudio.LocalAudioAvailability
import tech.musxi.core.localaudio.LocalAudioError
import tech.musxi.core.localaudio.LocalAudioFailure
import tech.musxi.core.localaudio.LocalAudioItem
import tech.musxi.core.localaudio.LocalAudioLimits
import tech.musxi.core.localaudio.LocalAudioMetadata
import tech.musxi.core.localaudio.LocalAudioProgress
import tech.musxi.core.localaudio.LocalAudioSource
import tech.musxi.core.localaudio.LocalImportMode
import tech.musxi.core.localaudio.LocalMediaKind
import tech.musxi.player.design.DesignTestApplication
import java.security.MessageDigest
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DesignTestApplication::class)
class SqliteLocalAudioStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var dbName: String
    private lateinit var store: SqliteLocalAudioStore

    @Before fun setUp() {
        dbName = "local-audio-test-${UUID.randomUUID()}.db"
        store = SqliteLocalAudioStore(context, dbName)
    }

    @After fun tearDown() {
        store.close()
        context.deleteDatabase(dbName)
    }

    @Test fun copyImportIsReadableWithoutExposingSourceUri() {
        val folder = store.createFolder("睡前音声")
        val item = store.importAudio(folder.id, metadata("voice.mp3"), copySource("copy-1"))

        val state = store.read()
        assertEquals(listOf(item), state.items)
        assertEquals(listOf(folder), state.folders)
        assertEquals(listOf(item.id), state.members.map { it.audioId })
        // The controller-facing snapshot must not carry the private location or grant.
        assertFalse(state.toString().contains("content://"))
        assertFalse(state.toString().contains(sha256("copy-1")))

        val stored = store.source(item.id) ?: error("source missing")
        assertEquals(LocalImportMode.COPY, stored.source.mode)
        assertEquals(sha256("copy-1"), stored.source.location)
        assertNull(stored.source.grantUri)
        assertEquals(setOf(sha256("copy-1")), store.retainedCopyNames())
        assertEquals(emptySet<String>(), store.retainedGrantUris())
    }

    @Test fun rescanOfSameSourceKeepsIdNameProgressAndBookmarks() {
        val folder = store.createFolder("音声")
        val first = store.importAudio(folder.id, metadata("a.m4b"), referenceSource("doc-a"))
        store.renameAudio(first.id, "喜欢的片段")
        store.saveProgress(folder.id, progress(first.id, 90 * 60_000L))
        val bookmark = store.addBookmark(first.id, 5_000L, "开头")
        val revisionAfterFirstImport = store.read().revision

        val rescanned = store.importAudio(folder.id, metadata("a.m4b", title = "新标题", sizeBytes = 1_024L), referenceSource("doc-a"))

        assertEquals(first.id, rescanned.id)
        assertEquals("喜欢的片段", rescanned.displayName)
        assertEquals("新标题", rescanned.metadata.title)
        assertEquals(1_024L, rescanned.metadata.sizeBytes)
        assertEquals(1, store.read().items.size)
        assertEquals(1, store.read().members.size)
        assertEquals(90 * 60_000L, store.read().progress.single().positionMs)
        assertEquals(listOf(bookmark), store.read().bookmarks)
        assertTrue(store.read().revision > revisionAfterFirstImport)
    }

    @Test fun rescanWithChangedFileSizeIsRejectedUntilRelink() {
        val folder = store.createFolder("音声")
        val original = store.importAudio(folder.id, metadata("v.m4a", title = "原标签", sizeBytes = 1_024L), referenceSource("doc-v"))
        store.renameAudio(original.id, "原名")
        store.saveProgress(folder.id, progress(original.id, 30_000L))

        val failure = assertThrows(LocalAudioFailure::class.java) {
            store.importAudio(folder.id, metadata("v.m4a", title = "新标签", sizeBytes = 2_048L), referenceSource("doc-v"))
        }

        assertEquals(LocalAudioError.SOURCE_CHANGED, failure.error)
        // The rejected re-scan must not touch the stored name, metadata or progress.
        val kept = store.read()
        assertEquals("原名", kept.items.single().displayName)
        assertEquals("原标签", kept.items.single().metadata.title)
        assertEquals(1_024L, kept.items.single().metadata.sizeBytes)
        assertEquals(30_000L, kept.progress.single().positionMs)
    }

    @Test fun sameFileCanSitInTwoFoldersWithOneRecord() {
        val bedtime = store.createFolder("睡前")
        val favourites = store.createFolder("收藏")
        val item = store.importAudio(bedtime.id, metadata("x.flac"), referenceSource("doc-x"))

        store.importAudio(favourites.id, metadata("x.flac"), referenceSource("doc-x"))
        store.addToFolder(favourites.id, item.id)

        val state = store.read()
        assertEquals(1, state.items.size)
        assertEquals(2, state.members.size)
        assertEquals(setOf(bedtime.id, favourites.id), state.members.map { it.folderId }.toSet())

        // Leaving one logical folder keeps the record and its other membership intact.
        store.removeFromFolder(favourites.id, item.id)
        assertEquals(listOf(bedtime.id to item.id), store.read().members.map { it.folderId to it.audioId })
        assertEquals(listOf(item.id), store.read().items.map { it.id })
        assertEquals(
            LocalAudioError.NOT_FOUND,
            assertThrows(LocalAudioFailure::class.java) { store.removeFromFolder(favourites.id, item.id) }.error,
        )
    }

    @Test fun deletingFolderKeepsAudioAndDropsMembersAndCursor() {
        val folder = store.createFolder("临时")
        val item = store.importAudio(folder.id, metadata("y.ogg"), copySource("copy-y"))
        store.saveProgress(folder.id, progress(item.id, 1_000L))
        assertEquals(1, store.read().cursors.size)

        store.deleteFolder(folder.id)

        val state = store.read()
        assertTrue(state.folders.isEmpty())
        assertTrue(state.members.isEmpty())
        assertTrue(state.cursors.isEmpty())
        assertEquals(listOf(item.id), state.items.map { it.id })
        // The progress belongs to the audio, not to the membership that was deleted.
        assertEquals(item.id, state.progress.single().audioId)
    }

    @Test fun removingAudioCascadesDependentsAndReturnsItsSource() {
        val folder = store.createFolder("组")
        val item = store.importAudio(folder.id, metadata("z.wav"), referenceSource("doc-z"))
        store.saveProgress(folder.id, progress(item.id, 2_000L))
        store.addBookmark(item.id, 3_000L, "标记")

        val removed = store.removeAudio(item.id)

        assertEquals(referenceSource("doc-z").location, removed.location)
        val state = store.read()
        assertTrue(state.items.isEmpty())
        assertTrue(state.members.isEmpty())
        assertTrue(state.progress.isEmpty())
        assertTrue(state.bookmarks.isEmpty())
        assertTrue(state.cursors.isEmpty())
        assertNull(store.source(item.id))
    }

    @Test fun relinkKeepsIdUserNameProgressAndBookmarks() {
        val folder = store.createFolder("组")
        val item = store.importAudio(folder.id, metadata("old.m4a"), referenceSource("doc-old"))
        store.renameAudio(item.id, "用户名称")
        store.saveProgress(folder.id, progress(item.id, 42_000L))
        val bookmark = store.addBookmark(item.id, 7_000L, "片段")

        val newSource = referenceSource("doc-new")
        store.relink(item.id, metadata("new.m4a"), newSource)

        val state = store.read()
        assertEquals(listOf(item.id), state.items.map { it.id })
        assertEquals("用户名称", state.items.single().displayName)
        assertEquals("new.m4a", state.items.single().metadata.fileName)
        assertEquals(42_000L, state.progress.single().positionMs)
        assertEquals(listOf(bookmark), state.bookmarks)
        assertEquals(newSource.location, store.source(item.id)?.source?.location)
    }

    @Test fun relinkRejectsSourceAlreadyLinkedToAnotherAudio() {
        val folder = store.createFolder("组")
        val taken = store.importAudio(folder.id, metadata("taken.m4a"), referenceSource("doc-taken"))
        val mine = store.importAudio(folder.id, metadata("mine.m4a"), referenceSource("doc-mine"))

        val failure = assertThrows(LocalAudioFailure::class.java) {
            store.relink(mine.id, metadata("mine.m4a"), referenceSource("doc-taken"))
        }

        assertEquals(LocalAudioError.SOURCE_CHANGED, failure.error)
        assertEquals(taken.id, store.source(taken.id)?.item?.id)
        assertEquals(referenceSource("doc-mine").location, store.source(mine.id)?.source?.location)
    }

    @Test fun relinkRejectsCopyModeAndLeavesTheRowUntouched() {
        val folder = store.createFolder("组")
        val copied = store.importAudio(folder.id, metadata("c.mp3"), copySource("copy-c"))

        val failure = assertThrows(LocalAudioFailure::class.java) {
            store.relink(copied.id, metadata("c.mp3"), referenceSource("doc-c"))
        }

        assertEquals(LocalAudioError.SOURCE_CHANGED, failure.error)
        assertEquals(LocalImportMode.COPY, store.source(copied.id)?.source?.mode)
        assertEquals(sha256("copy-c"), store.source(copied.id)?.source?.location)
    }

    @Test fun reorderRejectsDuplicateMissingOrForeignListsWithoutWriting() {
        val folder = store.createFolder("顺序")
        val first = store.importAudio(folder.id, metadata("1.mp3"), copySource("copy-1"))
        val second = store.importAudio(folder.id, metadata("2.mp3"), copySource("copy-2"))
        val revision = store.read().revision

        for (bad in listOf(
            listOf(first.id),
            listOf(first.id, first.id),
            emptyList(),
            listOf(first.id, second.id, LocalAudioLimits.newId()),
        )) {
            val failure = assertThrows(LocalAudioFailure::class.java) { store.reorder(folder.id, bad) }
            assertEquals(LocalAudioError.INVALID_ORDER, failure.error)
        }

        assertEquals(listOf(first.id, second.id), store.read().members.map { it.audioId })
        assertEquals(revision, store.read().revision)
    }

    @Test fun reorderAppliesTheRequestedOrderExactly() {
        val folder = store.createFolder("顺序")
        val a = store.importAudio(folder.id, metadata("1.mp3"), copySource("copy-a"))
        val b = store.importAudio(folder.id, metadata("2.mp3"), copySource("copy-b"))
        val c = store.importAudio(folder.id, metadata("3.mp3"), copySource("copy-c"))

        store.reorder(folder.id, listOf(c.id, a.id, b.id))

        assertEquals(listOf(c.id, a.id, b.id), store.read().members.map { it.audioId })
        assertEquals(listOf(0, 1, 2), store.read().members.map { it.position })
    }

    @Test fun progressAndCursorSurviveAReopen() {
        val folder = store.createFolder("有声书")
        val item = store.importAudio(folder.id, metadata("book.m4b"), referenceSource("doc-book"))
        // 25 hours: longer than the platform-song cap, still inside the local audio limit.
        val position = 25 * 60 * 60 * 1000L
        store.saveProgress(folder.id, progress(item.id, position))

        store.close()
        store = SqliteLocalAudioStore(context, dbName)

        val state = store.read()
        assertEquals(listOf(folder), state.folders)
        assertEquals(listOf(item.id), state.items.map { it.id })
        assertEquals(position, state.progress.single().positionMs)
        assertEquals(listOf(folder.id to item.id), state.cursors.map { it.folderId to it.audioId })
        assertEquals(referenceSource("doc-book").location, store.source(item.id)?.source?.location)
    }

    @Test fun folderLimitIsEnforcedWithoutLosingTheExistingLibrary() {
        store.close()
        store = SqliteLocalAudioStore(context, null)
        repeat(LocalAudioLimits.MAX_FOLDERS) { store.createFolder("组 $it") }
        val revision = store.read().revision

        val failure = assertThrows(LocalAudioFailure::class.java) { store.createFolder("溢出") }

        assertEquals(LocalAudioError.LIMIT_REACHED, failure.error)
        assertEquals(LocalAudioLimits.MAX_FOLDERS, store.read().folders.size)
        assertEquals(revision, store.read().revision)
    }

    @Test fun rejectedWritesThrowAndDoNotAdvanceTheRevision() {
        val folder = store.createFolder("组")
        val item = store.importAudio(folder.id, metadata("a.mp3"), copySource("copy-a"))
        val revision = store.read().revision

        assertThrows(LocalAudioFailure::class.java) { store.renameFolder(folder.id, " ") }
        assertThrows(LocalAudioFailure::class.java) { store.addToFolder(LocalAudioLimits.newId(), item.id) }
        assertThrows(LocalAudioFailure::class.java) { store.removeBookmark(LocalAudioLimits.newId()) }
        assertThrows(LocalAudioFailure::class.java) { store.saveProgress(LocalAudioLimits.newId(), progress(item.id, 10L)) }
        assertEquals(
            LocalAudioError.INVALID_NAME,
            assertThrows(LocalAudioFailure::class.java) { store.createFolder("") }.error,
        )
        assertEquals(
            LocalAudioError.INVALID_NAME,
            assertThrows(LocalAudioFailure::class.java) { store.renameAudio(item.id, "坏\u0007名字") }.error,
        )
        assertEquals(
            LocalAudioError.NOT_FOUND,
            assertThrows(LocalAudioFailure::class.java) { store.deleteFolder(LocalAudioLimits.newId()) }.error,
        )
        assertEquals(
            LocalAudioError.NOT_FOUND,
            assertThrows(LocalAudioFailure::class.java) { store.saveProgress(null, progress(LocalAudioLimits.newId(), 10L)) }.error,
        )
        assertNull(store.source("not-a-uuid"))
        assertEquals(
            LocalAudioError.STORAGE_FAILED,
            assertThrows(LocalAudioFailure::class.java) { store.addBookmark(item.id, -1L, "越界") }.error,
        )

        assertEquals(revision, store.read().revision)
        assertEquals("组", store.read().folders.single().name)
        assertEquals(1, store.read().members.size)
        assertTrue(store.read().bookmarks.isEmpty())
    }

    @Test fun corruptDatabaseReportsReadFailureInsteadOfReset() {
        store.close()
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        val garbage = "not a sqlite database".repeat(64).toByteArray()
        file.writeBytes(garbage)
        store = SqliteLocalAudioStore(context, dbName)

        val failure = assertThrows(LocalAudioFailure::class.java) { store.read() }

        assertEquals(LocalAudioError.READ_FAILED, failure.error)
        assertTrue("corrupt file must be preserved for recovery", file.exists())
        assertEquals(garbage.size.toLong(), file.length())
        org.junit.Assert.assertArrayEquals(garbage, file.readBytes())
        assertThrows(LocalAudioFailure::class.java) { store.read() }
    }

    @Test fun folderSettingsAndAvailabilityPersistAndRevisionMonotonic() {
        val folder = store.createFolder("组")
        val item = store.importAudio(folder.id, metadata("a.mp3"), copySource("copy-a"))
        val revisions = mutableListOf(store.read().revision)

        store.renameFolder(folder.id, "改名")
        store.setContinuePlayback(folder.id, false)
        store.setAvailability(item.id, LocalAudioAvailability.MISSING)
        assertEquals(LocalAudioAvailability.MISSING, store.read().items.single().availability)
        store.addBookmark(item.id, 1_000L, "书签")
        store.removeBookmark(store.read().bookmarks.single().id)
        store.removeAudio(item.id)

        val state = store.read()
        assertEquals("改名", state.folders.single().name)
        assertFalse(state.folders.single().continuePlayback)
        assertTrue(state.items.isEmpty())
        assertTrue(state.bookmarks.isEmpty())
        assertEquals(store.read().revision, revisions.first() + 6)
        assertNotEquals(0L, store.read().revision)
    }

    @Test fun videoImportKeepsKindAndArtworkAcrossAReopen() {
        val folder = store.createFolder("视频")
        val artwork = sha256("cover-v")
        val item = store.importAudio(folder.id,
            metadata("clip.mp4", kind = LocalMediaKind.VIDEO, artworkKey = artwork), referenceSource("doc-clip"))

        store.close()
        store = SqliteLocalAudioStore(context, dbName)

        val stored = store.read().items.single()
        assertEquals(LocalMediaKind.VIDEO, stored.kind)
        assertEquals(artwork, stored.artworkKey)
        // The item properties only forward the metadata; there is no second stored copy.
        assertEquals(LocalMediaKind.VIDEO, stored.metadata.kind)
        assertEquals(artwork, stored.metadata.artworkKey)
        assertEquals(setOf(artwork), store.retainedArtworkKeys())
        assertEquals(item.id, stored.id)
    }

    @Test fun setArtworkAdvancesTheRevisionWithoutTouchingTheSourceVersion() {
        val folder = store.createFolder("音频")
        val item = store.importAudio(folder.id, metadata("song.flac", sizeBytes = 2_048L), referenceSource("doc-song"))
        val artwork = sha256("cover-song")
        val before = store.read()

        store.setArtwork(item.id, artwork)

        val withCover = store.read()
        assertEquals(before.revision + 1, withCover.revision)
        assertEquals(artwork, withCover.items.single().artworkKey)
        // The version fields decide content changes, so a new cover must not look like new bytes.
        assertEquals(2_048L, withCover.items.single().metadata.sizeBytes)
        assertNull(withCover.items.single().metadata.modifiedAtMs)
        assertEquals(setOf(artwork), store.retainedArtworkKeys())

        store.setArtwork(item.id, null)

        val withoutCover = store.read()
        assertEquals(withCover.revision + 1, withoutCover.revision)
        assertNull(withoutCover.items.single().artworkKey)
        assertTrue(store.retainedArtworkKeys().isEmpty())
        assertEquals(2_048L, withoutCover.items.single().metadata.sizeBytes)
        assertEquals("doc-song", store.source(item.id)?.source?.identity)
    }

    @Test fun artworkKeysMustBeLowercaseSha256OrNull() {
        val folder = store.createFolder("音频")
        val item = store.importAudio(folder.id, metadata("song.mp3"), referenceSource("doc-song"))
        val revision = store.read().revision

        assertEquals(LocalAudioError.INVALID_TARGET, assertThrows(LocalAudioFailure::class.java) {
            store.setArtwork(item.id, "../../files/cover.jpg")
        }.error)
        assertEquals(LocalAudioError.INVALID_TARGET, assertThrows(LocalAudioFailure::class.java) {
            // Uppercase hex is the same hash but not the canonical cache key.
            store.setArtwork(item.id, "A".repeat(64))
        }.error)
        assertEquals(LocalAudioError.INVALID_TARGET, assertThrows(LocalAudioFailure::class.java) {
            store.setArtwork(item.id, "0".repeat(63))
        }.error)
        assertEquals(LocalAudioError.INVALID_TARGET, assertThrows(LocalAudioFailure::class.java) {
            store.setArtwork(item.id, "0".repeat(65))
        }.error)
        assertEquals(LocalAudioError.NOT_FOUND, assertThrows(LocalAudioFailure::class.java) {
            store.setArtwork(LocalAudioLimits.newId(), sha256("cover-c"))
        }.error)
        assertThrows(IllegalArgumentException::class.java) {
            LocalAudioMetadata("bad.mp3", artworkKey = "not-a-hash")
        }

        assertEquals(revision, store.read().revision)
        assertNull(store.read().items.single().artworkKey)
    }

    @Test fun rescanWithoutArtworkKeepsTheKnownCoverAndRelinkReplacesOrClearsIt() {
        val folder = store.createFolder("音频")
        val first = sha256("cover-first")
        val item = store.importAudio(folder.id, metadata("mix.m4a", artworkKey = first), referenceSource("doc-mix"))

        // The same source was scanned again and produced no cover: the known one survives.
        store.importAudio(folder.id, metadata("mix.m4a", title = "新标签"), referenceSource("doc-mix"))
        assertEquals(first, store.read().items.single().artworkKey)
        assertEquals("新标签", store.read().items.single().metadata.title)

        val second = sha256("cover-second")
        store.relink(item.id, metadata("mix.m4a", artworkKey = second), referenceSource("doc-mix-new"))
        assertEquals(second, store.read().items.single().artworkKey)
        assertEquals(setOf(second), store.retainedArtworkKeys())

        // A relink to a version without a cover must not keep the previous picture.
        store.relink(item.id, metadata("mix.m4a"), referenceSource("doc-mix-new"))
        val relinked = store.read()
        assertNull(relinked.items.single().artworkKey)
        assertTrue(store.retainedArtworkKeys().isEmpty())
        assertEquals(item.id, relinked.items.single().id)
        // Only the cover and source change; the user's own name, progress and bookmarks stay.
        assertEquals("doc-mix-new", store.source(item.id)?.source?.identity)
    }

    @Test fun versionTwoLibraryMigratesToTheCurrentSchemaKeepingUserData() {
        val legacy = installLegacyLibrary(version = 2)

        val state = store.read()
        val item = state.items.single()
        assertEquals(legacy.audioId, item.id)
        assertEquals(LocalMediaKind.AUDIO, item.kind)
        assertNull(item.artworkKey)
        // Schema 4 defaults: a legacy row keeps normal speed and has no chosen cover.
        assertEquals(1f, item.playbackSpeed, 0f)
        assertNull(item.customArtworkKey)
        assertFalse(item.artworkCustom)
        assertEquals("旧名称", item.displayName)
        assertEquals("旧标题", item.metadata.title)
        assertEquals(2_048L, item.metadata.sizeBytes)
        assertEquals(listOf(legacy.folderId), state.members.map { it.folderId })
        assertEquals(65_000L, state.progress.single().positionMs)
        assertEquals(listOf("旧书签"), state.bookmarks.map { it.name })
        assertEquals("旧分组", state.folders.single().name)
        assertEquals(7L, state.revision)

        // The migrated row is fully writable under the new schema, old rows stay untouched.
        val artwork = sha256("cover-legacy")
        store.setArtwork(legacy.audioId, artwork)
        store.importAudio(legacy.folderId, metadata("new.mp4", kind = LocalMediaKind.VIDEO), referenceSource("doc-new"))
        val migrated = store.read()
        assertEquals(2, migrated.items.size)
        assertEquals(artwork, migrated.items.first { it.id == legacy.audioId }.artworkKey)
        assertEquals(LocalMediaKind.VIDEO, migrated.items.first { it.id != legacy.audioId }.kind)
    }

    @Test fun versionOneLibraryMigratesThroughEveryStepToTheCurrentSchema() {
        val legacy = installLegacyLibrary(version = 1)

        val state = store.read()
        val item = state.items.single()
        assertEquals(legacy.audioId, item.id)
        assertEquals(LocalMediaKind.AUDIO, item.kind)
        assertNull(item.artworkKey)
        // The version-2 step adds a nullable modification time; legacy rows keep it unknown.
        assertNull(item.metadata.modifiedAtMs)
        // Every intermediate step ran: the version-4 columns exist with their defaults.
        assertEquals(1f, item.playbackSpeed, 0f)
        assertNull(item.customArtworkKey)
        assertEquals("旧名称", item.displayName)
        assertEquals(65_000L, state.progress.single().positionMs)
        assertEquals(legacy.folderId, state.cursors.single().folderId)

        val artwork = sha256("cover-legacy-1")
        store.setArtwork(legacy.audioId, artwork)
        store.setAvailability(legacy.audioId, LocalAudioAvailability.MISSING)

        // A migrated library must survive its own reopen at the current version.
        store.close()
        store = SqliteLocalAudioStore(context, dbName)
        val reopened = store.read().items.single()
        assertEquals(artwork, reopened.artworkKey)
        assertEquals(LocalMediaKind.AUDIO, reopened.kind)
        assertEquals(LocalAudioAvailability.MISSING, reopened.availability)
    }

    @Test fun versionThreeLibraryMigratesToVersionFourKeepingItsAutomaticCover() {
        val automatic = sha256("cover-v3")
        val legacy = installLegacyLibrary(version = 3, artworkKey = automatic)

        val state = store.read()
        val item = state.items.single()
        assertEquals(legacy.audioId, item.id)
        // The version-3 column survives the new step and stays the automatic cover.
        assertEquals(automatic, item.metadata.artworkKey)
        assertEquals(automatic, item.artworkKey)
        assertNull(item.customArtworkKey)
        assertFalse(item.artworkCustom)
        // The added column default must be a real 1x, not 0 or NULL.
        assertEquals(1f, item.playbackSpeed, 0f)
        assertEquals(setOf(automatic), store.retainedArtworkKeys())
        assertEquals("旧名称", item.displayName)
        assertEquals(listOf("旧书签"), state.bookmarks.map { it.name })

        // The migrated row is writable under the new columns and survives its own reopen.
        store.setPlaybackSpeed(legacy.audioId, 1.75f)
        store.setCustomArtwork(legacy.audioId, sha256("cover-v3-custom"))
        store.close()
        store = SqliteLocalAudioStore(context, dbName)
        val reopened = store.read().items.single()
        assertEquals(1.75f, reopened.playbackSpeed, 0f)
        assertEquals(automatic, reopened.metadata.artworkKey)
        assertEquals(sha256("cover-v3-custom"), reopened.customArtworkKey)
    }

    @Test fun playbackSpeedIsStoredPerAudioAcrossAReopen() {
        val folder = store.createFolder("音频")
        val slow = store.importAudio(folder.id, metadata("a.mp3"), copySource("copy-speed-a"))
        val fast = store.importAudio(folder.id, metadata("b.mp3"), copySource("copy-speed-b"))
        val revision = store.read().revision

        store.setPlaybackSpeed(slow.id, 0.75f)
        store.setPlaybackSpeed(fast.id, 2f)

        assertEquals(revision + 2, store.read().revision)
        store.close()
        store = SqliteLocalAudioStore(context, dbName)
        val speeds = store.read().items.associate { it.id to it.playbackSpeed }
        assertEquals(mapOf(slow.id to 0.75f, fast.id to 2f), speeds)
        // A rate is a preference, not content: the source version fields stay exactly as imported.
        val stored = store.read().items.single { it.id == fast.id }
        assertEquals(1_024L, stored.metadata.sizeBytes)
        assertNull(stored.metadata.modifiedAtMs)
        assertEquals("copy-speed-b", store.source(fast.id)?.source?.identity)
    }

    @Test fun unsupportedPlaybackSpeedsAreRejectedWithoutWriting() {
        val folder = store.createFolder("音频")
        val item = store.importAudio(folder.id, metadata("a.mp3"), copySource("copy-speed-bad"))
        val revision = store.read().revision

        for (bad in listOf(0f, 0.25f, 1.1f, 3f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(
                LocalAudioError.INVALID_TARGET,
                assertThrows(LocalAudioFailure::class.java) { store.setPlaybackSpeed(item.id, bad) }.error,
            )
        }
        assertEquals(
            LocalAudioError.NOT_FOUND,
            assertThrows(LocalAudioFailure::class.java) {
                store.setPlaybackSpeed(LocalAudioLimits.newId(), 1.5f)
            }.error,
        )

        assertEquals(revision, store.read().revision)
        assertEquals(1f, store.read().items.single().playbackSpeed, 0f)
        // The model enforces the same rule, so a bad value cannot be constructed either.
        assertThrows(IllegalArgumentException::class.java) {
            LocalAudioItem(item.id, item.metadata, item.mode, addedAtMs = 0L, playbackSpeed = Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalAudioItem(item.id, item.metadata, item.mode, addedAtMs = 0L, playbackSpeed = 1.1f)
        }
    }

    @Test fun customArtworkOverridesTheAutomaticCoverAndCanBeCleared() {
        val folder = store.createFolder("音频")
        val automatic = sha256("cover-auto")
        val custom = sha256("cover-custom")
        val item = store.importAudio(folder.id, metadata("song.mp3", artworkKey = automatic), referenceSource("doc-song"))
        assertFalse(store.read().items.single().artworkCustom)

        store.setCustomArtwork(item.id, custom)

        val withCustom = store.read().items.single()
        assertTrue(withCustom.artworkCustom)
        assertEquals(custom, withCustom.artworkKey)
        assertEquals(custom, withCustom.customArtworkKey)
        // Both rows are stored: the automatic cover is not overwritten by the chosen one.
        assertEquals(automatic, withCustom.metadata.artworkKey)
        // The cache keeps both, so clearing the choice needs no re-extraction.
        assertEquals(setOf(automatic, custom), store.retainedArtworkKeys())
        // A copy that only rewrites the automatic cover keeps the user's choice and the rate.
        val copied = withCustom.copy(metadata = withCustom.metadata.copy(artworkKey = null), playbackSpeed = 1.5f)
        assertEquals(custom, copied.customArtworkKey)
        assertEquals(custom, copied.artworkKey)
        assertTrue(copied.artworkCustom)
        assertEquals(1.5f, copied.playbackSpeed, 0f)

        store.close()
        store = SqliteLocalAudioStore(context, dbName)
        assertEquals(custom, store.read().items.single().artworkKey)

        store.setCustomArtwork(item.id, null)
        val cleared = store.read().items.single()
        assertFalse(cleared.artworkCustom)
        assertNull(cleared.customArtworkKey)
        assertEquals(automatic, cleared.artworkKey)
        assertEquals(setOf(automatic), store.retainedArtworkKeys())
    }

    @Test fun customArtworkWithoutAnAutomaticOneHasNoFallback() {
        val folder = store.createFolder("音频")
        val item = store.importAudio(folder.id, metadata("plain.mp3"), copySource("copy-plain"))
        val custom = sha256("cover-only-custom")

        store.setCustomArtwork(item.id, custom)
        assertEquals(custom, store.read().items.single().artworkKey)

        store.setCustomArtwork(item.id, null)
        assertNull(store.read().items.single().artworkKey)
        assertTrue(store.retainedArtworkKeys().isEmpty())
        assertEquals(
            LocalAudioError.INVALID_TARGET,
            assertThrows(LocalAudioFailure::class.java) { store.setCustomArtwork(item.id, "not-a-hash") }.error,
        )
        assertEquals(
            LocalAudioError.NOT_FOUND,
            assertThrows(LocalAudioFailure::class.java) {
                store.setCustomArtwork(LocalAudioLimits.newId(), custom)
            }.error,
        )
    }

    @Test fun rescanAndRelinkNeverTouchTheChosenCoverOrTheSpeed() {
        val folder = store.createFolder("音频")
        val automatic = sha256("cover-keep-auto")
        val custom = sha256("cover-keep-custom")
        val item = store.importAudio(folder.id, metadata("mix.m4a", artworkKey = automatic), referenceSource("doc-mix"))
        store.setCustomArtwork(item.id, custom)
        store.setPlaybackSpeed(item.id, 1.25f)

        // A same-source re-scan extracts a different automatic cover; neither user value moves.
        store.importAudio(folder.id,
            metadata("mix.m4a", artworkKey = sha256("cover-rescan")), referenceSource("doc-mix"))
        val rescanned = store.read().items.single()
        assertEquals(custom, rescanned.artworkKey)
        assertEquals(custom, rescanned.customArtworkKey)
        assertEquals(1.25f, rescanned.playbackSpeed, 0f)

        store.relink(item.id, metadata("mix.m4a"), referenceSource("doc-mix-new"))
        val relinked = store.read().items.single()
        assertEquals(item.id, relinked.id)
        // The automatic cover was cleared by the relink, so the chosen cover is the visible one.
        assertNull(relinked.metadata.artworkKey)
        assertEquals(custom, relinked.artworkKey)
        assertEquals(1.25f, relinked.playbackSpeed, 0f)
        assertEquals(setOf(custom), store.retainedArtworkKeys())
        // Only the source moved; the user's speed and cover stayed with the entry.
        assertEquals("doc-mix-new", store.source(item.id)?.source?.identity)

        // Clearing the choice now leaves no cover at all, because the automatic one was dropped.
        store.setCustomArtwork(item.id, null)
        assertNull(store.read().items.single().artworkKey)
    }

    @Test fun renameBookmarkKeepsItsPositionRevisionAndNeighbours() {
        val folder = store.createFolder("音频")
        val item = store.importAudio(folder.id, metadata("a.mp3"), copySource("copy-bookmark"))
        val first = store.addBookmark(item.id, 5_000L, "书签 1")
        val second = store.addBookmark(item.id, 90_000L, "书签 2")
        val revision = store.read().revision

        store.renameBookmark(second.id, "第三章")

        val state = store.read()
        assertEquals(revision + 1, state.revision)
        // The stored order is by creation, so a rename keeps both position and order.
        assertEquals(listOf(first, second.copy(name = "第三章")), state.bookmarks)
        assertEquals(listOf(5_000L, 90_000L), state.bookmarks.map { it.positionMs })
    }

    @Test fun rejectedBookmarkRenameLeavesTheNameAndRevisionAlone() {
        val folder = store.createFolder("音频")
        val item = store.importAudio(folder.id, metadata("a.mp3"), copySource("copy-bookmark-bad"))
        val bookmark = store.addBookmark(item.id, 1_000L, "原名")
        val revision = store.read().revision

        assertEquals(
            LocalAudioError.INVALID_NAME,
            assertThrows(LocalAudioFailure::class.java) { store.renameBookmark(bookmark.id, " ") }.error,
        )
        assertEquals(
            LocalAudioError.INVALID_NAME,
            assertThrows(LocalAudioFailure::class.java) {
                store.renameBookmark(bookmark.id, "坏\u0007名字")
            }.error,
        )
        assertEquals(
            LocalAudioError.INVALID_NAME,
            assertThrows(LocalAudioFailure::class.java) {
                store.renameBookmark(bookmark.id, "长".repeat(LocalAudioLimits.MAX_NAME + 1))
            }.error,
        )
        assertEquals(
            LocalAudioError.NOT_FOUND,
            assertThrows(LocalAudioFailure::class.java) {
                store.renameBookmark(LocalAudioLimits.newId(), "新名")
            }.error,
        )

        assertEquals(listOf(bookmark), store.read().bookmarks)
        assertEquals(revision, store.read().revision)
    }

    private data class LegacyLibrary(val folderId: String, val audioId: String)

    /**
     * Writes a hand-made on-disk library exactly as the given schema version created it, so the
     * upgrade path runs against a real legacy file instead of a database this build just created.
     */
    private fun installLegacyLibrary(version: Int, artworkKey: String? = null): LegacyLibrary {
        store.close()
        context.deleteDatabase(dbName)
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        val folderId = LocalAudioLimits.newId()
        val audioId = LocalAudioLimits.newId()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE folders (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, " +
                "continue_playback INTEGER NOT NULL, created_at_ms INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE audio (id TEXT NOT NULL PRIMARY KEY, mode TEXT NOT NULL, identity TEXT NOT NULL, " +
                "location TEXT NOT NULL, grant_uri TEXT, file_name TEXT NOT NULL, title TEXT, creator TEXT, " +
                "duration_ms INTEGER, size_bytes INTEGER, " +
                (if (version >= 2) "modified_at_ms INTEGER, " else "") +
                "display_name TEXT, availability TEXT NOT NULL, added_at_ms INTEGER NOT NULL, " +
                (if (version >= 3) "kind TEXT NOT NULL DEFAULT 'AUDIO', artwork_key TEXT, " else "") +
                "UNIQUE (mode, identity))")
            db.execSQL("CREATE TABLE members (folder_id TEXT NOT NULL REFERENCES folders (id) ON DELETE CASCADE, " +
                "audio_id TEXT NOT NULL REFERENCES audio (id) ON DELETE CASCADE, position INTEGER NOT NULL, " +
                "PRIMARY KEY (folder_id, audio_id))")
            db.execSQL("CREATE TABLE progress (audio_id TEXT NOT NULL PRIMARY KEY REFERENCES audio (id) ON DELETE CASCADE, " +
                "position_ms INTEGER NOT NULL, completed INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE cursors (folder_id TEXT NOT NULL PRIMARY KEY REFERENCES folders (id) ON DELETE CASCADE, " +
                "audio_id TEXT NOT NULL REFERENCES audio (id) ON DELETE CASCADE)")
            db.execSQL("CREATE TABLE bookmarks (id TEXT NOT NULL PRIMARY KEY, " +
                "audio_id TEXT NOT NULL REFERENCES audio (id) ON DELETE CASCADE, position_ms INTEGER NOT NULL, " +
                "name TEXT NOT NULL, created_at_ms INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE meta (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
            if (version >= 2) {
                db.execSQL("CREATE TABLE directories (id TEXT NOT NULL PRIMARY KEY, " +
                    "folder_id TEXT NOT NULL REFERENCES folders (id) ON DELETE CASCADE, tree_uri TEXT NOT NULL, " +
                    "name TEXT NOT NULL, mode TEXT NOT NULL, recursive INTEGER NOT NULL, " +
                    "last_refreshed_at_ms INTEGER, missing_count INTEGER NOT NULL DEFAULT 0, error TEXT, " +
                    "created_at_ms INTEGER NOT NULL, UNIQUE (folder_id, tree_uri))")
                db.execSQL("CREATE TABLE directory_entries (directory_id TEXT NOT NULL REFERENCES directories (id) " +
                    "ON DELETE CASCADE, identity TEXT NOT NULL, audio_id TEXT NOT NULL REFERENCES audio (id) " +
                    "ON DELETE CASCADE, size_bytes INTEGER, modified_at_ms INTEGER, " +
                    "PRIMARY KEY (directory_id, identity))")
            }
            db.insertOrThrow("folders", null, ContentValues().apply {
                put("id", folderId); put("name", "旧分组"); put("continue_playback", 1); put("created_at_ms", 1L)
            })
            db.insertOrThrow("audio", null, ContentValues().apply {
                put("id", audioId); put("mode", LocalImportMode.REFERENCE.name); put("identity", "doc-old")
                put("location", "content://com.android.externalstorage.documents/document/doc-old")
                put("grant_uri", "content://grant/doc-old"); put("file_name", "old.mp3"); put("title", "旧标题")
                put("size_bytes", 2_048L); put("display_name", "旧名称")
                put("availability", LocalAudioAvailability.AVAILABLE.name); put("added_at_ms", 2L)
                if (version >= 3) {
                    put("kind", LocalMediaKind.AUDIO.name)
                    if (artworkKey == null) putNull("artwork_key") else put("artwork_key", artworkKey)
                }
            })
            db.insertOrThrow("members", null, ContentValues().apply {
                put("folder_id", folderId); put("audio_id", audioId); put("position", 0)
            })
            db.insertOrThrow("progress", null, ContentValues().apply {
                put("audio_id", audioId); put("position_ms", 65_000L); put("completed", 0); put("updated_at_ms", 3L)
            })
            db.insertOrThrow("cursors", null, ContentValues().apply {
                put("folder_id", folderId); put("audio_id", audioId)
            })
            db.insertOrThrow("bookmarks", null, ContentValues().apply {
                put("id", LocalAudioLimits.newId()); put("audio_id", audioId)
                put("position_ms", 12_000L); put("name", "旧书签"); put("created_at_ms", 4L)
            })
            db.insertOrThrow("meta", null, ContentValues().apply {
                put("key", "revision"); put("value", "7")
            })
            db.version = version
        }
        store = SqliteLocalAudioStore(context, dbName)
        return LegacyLibrary(folderId, audioId)
    }

    private fun metadata(
        fileName: String,
        title: String? = "标题",
        creator: String? = null,
        durationMs: Long? = 60_000L,
        sizeBytes: Long? = 1_024L,
        modifiedAtMs: Long? = null,
        kind: LocalMediaKind = LocalMediaKind.AUDIO,
        artworkKey: String? = null,
    ) = LocalAudioMetadata(fileName, title, creator, durationMs, sizeBytes, modifiedAtMs, kind, artworkKey)

    private fun copySource(identity: String) =
        LocalAudioSource(LocalImportMode.COPY, sha256(identity), identity)

    private fun referenceSource(documentId: String): LocalAudioSource {
        val uri = "content://com.android.externalstorage.documents/document/$documentId"
        return LocalAudioSource(LocalImportMode.REFERENCE, uri, documentId, "content://grant/$documentId")
    }

    private fun progress(audioId: String, positionMs: Long) =
        LocalAudioProgress(audioId, positionMs, completed = false, updatedAtMs = 1_700_000_000_000L)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
            "%02x".format(it.toInt() and 0xFF)
        }
}
