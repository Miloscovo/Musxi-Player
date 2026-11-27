package tech.musxi.player.localaudio

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.musxi.core.localaudio.LocalAudioBackup
import tech.musxi.core.localaudio.LocalAudioBackupItem
import tech.musxi.core.localaudio.LocalAudioBackupKeys
import tech.musxi.core.localaudio.LocalAudioAvailability
import tech.musxi.core.localaudio.LocalAudioError
import tech.musxi.core.localaudio.LocalAudioFailure
import tech.musxi.core.localaudio.LocalAudioLimits
import tech.musxi.core.localaudio.LocalAudioMetadata
import tech.musxi.core.localaudio.LocalAudioProgress
import tech.musxi.core.localaudio.LocalAudioSegment
import tech.musxi.core.localaudio.LocalAudioSource
import tech.musxi.core.localaudio.LocalCueImportTrack
import tech.musxi.core.localaudio.LocalImportMode
import tech.musxi.core.localaudio.LocalMediaKind
import tech.musxi.player.design.DesignTestApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DesignTestApplication::class)
class SqliteCueImportTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var dbName: String
    private lateinit var store: SqliteLocalAudioStore

    @Before fun setUp() {
        dbName = "cue-store-${UUID.randomUUID()}.db"
        store = SqliteLocalAudioStore(context, dbName)
    }

    @After fun tearDown() {
        store.close()
        context.deleteDatabase(dbName)
    }

    @Test fun twoTracksImportAtomicallyAndPersistTheirRangesAcrossAReopen() {
        val folder = store.createFolder("专辑")
        val tracks = cueTracks(copy = true)
        val revisionBefore = store.read().revision

        val imported = store.importCue(folder.id, tracks, revisionBefore)

        assertEquals(tracks.map { it.segment }, imported.map { it.segment })
        assertEquals(listOf("第一轨", "第二轨"), imported.map { it.title })
        assertEquals(listOf(60_000L, 30_000L), imported.map { it.metadata.durationMs })
        assertEquals(20_000L, imported.single { it.segment?.trackNumber == 1 }.metadata.sizeBytes)
        assertEquals(revisionBefore + 1, store.read().revision)
        assertEquals(imported.map { it.id }, store.read().members.map { it.audioId })
        assertEquals(setOf(albumCopy), store.retainedCopyNames())

        store.close()
        store = SqliteLocalAudioStore(context, dbName)
        val reopened = store.read().items
        assertEquals(tracks.map { it.segment }, reopened.map { it.segment })
        assertEquals(listOf("第一轨", "第二轨"), reopened.map { it.title })
        assertEquals(imported.map { it.id }, reopened.map { it.id })
    }

    @Test fun repeatedImportPreservesUserDataAndAddsOnlyTheMissingMembership() {
        val folder = store.createFolder("专辑")
        val other = store.createFolder("收藏")
        val tracks = cueTracks(copy = true)
        val first = store.importCue(folder.id, tracks, store.read().revision)
        val target = first.first()
        store.renameAudio(target.id, "用户命名")
        store.setPlaybackSpeed(target.id, 1.25f)
        store.setCustomArtwork(target.id, sha256("user-cover"))
        store.saveProgress(folder.id, progress(target.id, 5_000L))
        val bookmark = store.addBookmark(target.id, 4_000L, "用户书签")
        val beforeRepeat = store.read()

        val repeated = store.importCue(other.id, tracks, beforeRepeat.revision)

        assertEquals(first.map { it.id }, repeated.map { it.id })
        val kept = store.read().items.single { it.id == target.id }
        assertEquals("用户命名", kept.displayName)
        assertEquals(1.25f, kept.playbackSpeed, 0f)
        assertEquals(sha256("user-cover"), kept.customArtworkKey)
        assertEquals(5_000L, store.read().progress.single().positionMs)
        assertEquals(listOf(bookmark), store.read().bookmarks)
        assertEquals(4, store.read().members.size)
        assertEquals(setOf(folder.id, other.id), store.read().members.map { it.folderId }.toSet())
        assertEquals(beforeRepeat.revision + 1, store.read().revision)
    }

    @Test fun unknownVersionFieldsDoNotTurnAValidRepeatIntoAConflict() {
        val folder = store.createFolder("专辑")
        val reference = cueTracks(copy = false).map {
            cueTrack(it.segment,
                source = referenceSource("doc-${it.segment.trackNumber}"),
                metadata = it.metadata.copy(sizeBytes = null))
        }
        val first = store.importCue(folder.id, reference, store.read().revision)
        val copyWithUnknownSize = cueTracks(copy = true).map {
            cueTrack(it.segment, source = it.source, metadata = metadata(
                fileName = "album.flac", durationMs = it.segment.durationMs,
                sizeBytes = null, modifiedAtMs = 9_999L, title = it.metadata.title,
            ))
        }
        val copied = store.importCue(folder.id, copyWithUnknownSize, store.read().revision)

        val againReference = store.importCue(folder.id, reference, store.read().revision)
        val sameBytesNewTimestamp = copyWithUnknownSize.map {
            cueTrack(it.segment, source = it.source, metadata = it.metadata.copy(modifiedAtMs = 10_001L))
        }
        val againCopy = store.importCue(folder.id, sameBytesNewTimestamp, store.read().revision)

        assertEquals(first.map { it.id }, againReference.map { it.id })
        assertEquals(copied.map { it.id }, againCopy.map { it.id })
        assertEquals(4, store.read().items.size)
    }

    @Test fun staleRevisionRejectsTheWholeBatchBeforeAnyWrite() {
        val folder = store.createFolder("专辑")
        val revision = store.read().revision
        store.createFolder("并发变化")

        val failure = assertThrows(LocalAudioFailure::class.java) {
            store.importCue(folder.id, cueTracks(copy = true), revision)
        }

        assertEquals(LocalAudioError.STALE_STATE, failure.error)
        assertTrue(store.read().items.isEmpty())
        assertEquals(2, store.read().folders.size)
    }

    @Test fun cancellationAfterWritesRollsBackRowsMembershipsAndRevision() {
        val folder = store.createFolder("专辑")
        val before = store.read()
        var checks = 0

        assertThrows(CancellationException::class.java) {
            store.importCue(folder.id, cueTracks(copy = true, count = 3), before.revision) {
                checks++
                if (checks == 9) throw CancellationException("cancelled")
            }
        }

        assertEquals(9, checks)
        assertEquals(before, store.read())
    }

    @Test fun sharedFileIsReleasedOnlyAfterTheLastSliceIsRemoved() {
        val folder = store.createFolder("专辑")
        val tracks = cueTracks(copy = true)
        val imported = store.importCue(folder.id, tracks, store.read().revision)

        store.removeAudio(imported.first().id)
        assertEquals(setOf(albumCopy), store.retainedCopyNames())
        store.removeAudio(imported.last().id)
        assertTrue(store.retainedCopyNames().isEmpty())
    }

    @Test fun oldLibraryMigratesToSchemaFiveWithoutLosingWholeFileRows() {
        installVersionFourLibrary()

        val state = store.read()
        val item = state.items.single()
        assertNull(item.segment)
        assertEquals("旧名称", item.displayName)
        assertEquals(60_000L, item.metadata.durationMs)
        assertEquals(25L * 60 * 60 * 1000, state.progress.single().positionMs)

        val folder = state.folders.single()
        val imported = store.importCue(folder.id, cueTracks(copy = true), state.revision)
        assertEquals(2, imported.size)
        assertEquals(1, store.read().items.count { it.segment == null })
        store.close()
        store = SqliteLocalAudioStore(context, dbName)
        assertNull(store.read().items.single { it.id == legacyAudioId }.segment)
        assertEquals(2, store.read().items.count { it.segment != null })
    }

    @Test fun segmentRelinkIsRejectedAndOldProgressBookmarkLimitsStillApply() {
        val folder = store.createFolder("专辑")
        val segment = store.importCue(folder.id, cueTracks(copy = false), store.read().revision).first()
        val duration = segment.segment!!.durationMs
        val before = store.read()

        val relinkFailure = assertThrows(LocalAudioFailure::class.java) {
            store.relink(segment.id, metadata("other.flac"), referenceSource("doc-other"))
        }
        val progressFailure = assertThrows(LocalAudioFailure::class.java) {
            store.saveProgress(folder.id, progress(segment.id, duration + 1))
        }
        val bookmarkFailure = assertThrows(LocalAudioFailure::class.java) {
            store.addBookmark(segment.id, duration + 1, "越界")
        }

        assertEquals(LocalAudioError.INVALID_TARGET, relinkFailure.error)
        assertEquals(LocalAudioError.INVALID_TARGET, progressFailure.error)
        assertEquals(LocalAudioError.INVALID_TARGET, bookmarkFailure.error)
        assertEquals(before, store.read())

        store.saveProgress(folder.id, progress(segment.id, duration))
        store.addBookmark(segment.id, duration, "末尾")
        assertEquals(duration, store.read().progress.single().positionMs)
        assertEquals(duration, store.read().bookmarks.single().positionMs)
    }

    @Test fun rangeAndSourceConflictsRejectTheEntireRequest() {
        val folder = store.createFolder("专辑")
        val original = cueTracks(copy = false).map {
            cueTrack(it.segment, source = it.source, metadata = it.metadata.copy(modifiedAtMs = 1_000L))
        }
        val imported = store.importCue(folder.id, original, store.read().revision)
        val before = store.read()

        val changedRange = original.mapIndexed { index, track ->
            if (index == 0) cueTrack(LocalAudioSegment(1L, track.segment.endUs, track.segment.trackNumber),
                source = track.source, metadata = metadata(track.metadata.fileName,
                    durationMs = track.segment.durationMs - 1L, sizeBytes = 20_000L, modifiedAtMs = 1_000L))
            else track
        }
        assertSourceChanged { store.importCue(folder.id, changedRange, before.revision) }

        val wrongMode = original.mapIndexed { index, track ->
            if (index == 0) cueTrack(track.segment, source = copySource(), metadata = track.metadata)
            else track
        }
        assertSourceChanged { store.importCue(folder.id, wrongMode, before.revision) }

        val mismatchedGrant = original.mapIndexed { index, track ->
            if (index == 0) cueTrack(track.segment,
                source = referenceSource("doc-1", grant = "content://grant/other"))
            else track
        }
        assertSourceChanged { store.importCue(folder.id, mismatchedGrant, before.revision) }

        val changedSize = original.mapIndexed { index, track ->
            if (index == 0) cueTrack(track.segment, source = track.source,
                metadata = track.metadata.copy(sizeBytes = 20_001L))
            else track
        }
        assertSourceChanged { store.importCue(folder.id, changedSize, before.revision) }

        val changedReferenceTime = original.mapIndexed { index, track ->
            if (index == 0) cueTrack(track.segment, source = track.source,
                metadata = track.metadata.copy(modifiedAtMs = 2_000L))
            else track
        }
        assertSourceChanged { store.importCue(folder.id, changedReferenceTime, before.revision) }

        assertEquals(before, store.read())
        assertEquals(imported.map { it.id }, store.read().items.map { it.id })
    }

    @Test fun duplicateNumbersIdentitiesAndEmptyOrOversizedBatchesAreRejectedBeforeWriting() {
        val folder = store.createFolder("专辑")
        val revision = store.read().revision
        val tracks = cueTracks(copy = true, count = 3)

        assertFailure(LocalAudioError.INVALID_ORDER, revision) {
            store.importCue(folder.id, listOf(tracks[0], tracks[1], tracks[1]), revision)
        }
        assertFailure(LocalAudioError.LIMIT_REACHED, revision) { store.importCue(folder.id, emptyList(), revision) }
        assertFailure(LocalAudioError.LIMIT_REACHED, revision) {
            store.importCue(folder.id, List(100) { index ->
                cueTrack(LocalAudioSegment(index * 1_000L, (index + 1) * 1_000L, index % 99 + 1),
                    source = referenceSource("doc-$index"))
            }, revision)
        }
        assertTrue(store.read().items.isEmpty())
    }

    @Test fun duplicatedInputNumbersAndIdentitiesAreRejectedBeforeWriting() {
        val folder = store.createFolder("专辑")
        val tracks = cueTracks(copy = true)
        val duplicateTrack = cueTrack(tracks[0].segment, source = copySource(
            identity = "cue-v1:second"))
        val duplicateIdentity = tracks[1].let { second ->
            cueTrack(LocalAudioSegment(60_000_000L, 75_000_000L, 2), source = tracks[0].source,
                metadata = second.metadata.copy(durationMs = 15_000L))
        }
        val revision = store.read().revision

        val numberFailure = assertThrows(LocalAudioFailure::class.java) {
            store.importCue(folder.id, listOf(tracks[0], duplicateTrack), revision)
        }
        val identityFailure = assertThrows(LocalAudioFailure::class.java) {
            store.importCue(folder.id, listOf(tracks[0], duplicateIdentity), revision)
        }

        assertEquals(LocalAudioError.INVALID_ORDER, numberFailure.error)
        assertEquals(LocalAudioError.INVALID_ORDER, identityFailure.error)
        assertTrue(store.read().items.isEmpty())
    }

    @Test fun normalWholeFileImportCannotRewriteASegmentRow() {
        val folder = store.createFolder("专辑")
        val segment = store.importCue(folder.id, cueTracks(copy = true), store.read().revision).first()
        val before = store.read()

        val failure = assertThrows(LocalAudioFailure::class.java) {
            store.importAudio(folder.id, metadata("album.flac"), segmentSource(store.source(segment.id)!!.source.identity))
        }

        assertEquals(LocalAudioError.SOURCE_CHANGED, failure.error)
        assertEquals(before, store.read())
    }

    @Test fun backupMatchesSlicesIndependentlyAndRejectsForgedRanges() {
        val folder = store.createFolder("专辑")
        val items = store.importCue(folder.id, cueTracks(copy = true), store.read().revision)
        val entries = items.map { item -> LocalAudioBackupItem(item, LocalAudioBackupKeys.source(store.source(item.id)!!)) }
        val valid = LocalAudioBackup(LocalAudioLimits.newId(), 1, entries, emptyList(), emptyList(),
            listOf(progress(items[0].id, 1_000), progress(items[1].id, 2_000)), emptyList(), emptyList())
        assertEquals(2, store.previewOrganization(valid).matchedItems)
        store.restoreOrganization(valid, store.read().revision)
        assertEquals(listOf(1_000L, 2_000L), store.read().progress.sortedBy { it.positionMs }.map { it.positionMs })
        val forgedSegment = LocalAudioSegment(0, 300_000_000, 1)
        val forgedItem = items[0].copy(metadata = items[0].metadata.copy(durationMs = forgedSegment.durationMs), segment = forgedSegment)
        val forged = LocalAudioBackup(LocalAudioLimits.newId(), 1,
            listOf(LocalAudioBackupItem(forgedItem, entries[0].sourceKey)), emptyList(), emptyList(),
            listOf(progress(forgedItem.id, 200_000)), emptyList(), emptyList())
        assertEquals(0, store.previewOrganization(forged).matchedItems)
        val before = store.read().progress
        store.restoreOrganization(forged, store.read().revision)
        assertEquals(before, store.read().progress)
    }
    private fun assertSourceChanged(block: () -> Unit) {
        val failure = assertThrows(LocalAudioFailure::class.java) { block() }
        assertEquals(LocalAudioError.SOURCE_CHANGED, failure.error)
    }

    private fun assertFailure(error: LocalAudioError, revision: Long, block: () -> Unit) {
        val failure = assertThrows(LocalAudioFailure::class.java) { block() }
        assertEquals(error, failure.error)
        assertEquals(revision, store.read().revision)
    }

    private fun cueTracks(copy: Boolean, count: Int = 2): List<LocalCueImportTrack> {
        val starts = listOf(0L, 60_000_000L, 90_000_000L)
        val ends = listOf(60_000_000L, 90_000_000L, 105_000_000L)
        return (0 until count).map { index ->
            val segment = LocalAudioSegment(starts[index], ends[index], index + 1)
            val source = if (copy) copySource(identity = "cue-v1:${index + 1}") else
                referenceSource("doc-${index + 1}")
            cueTrack(segment, source = source)
        }
    }

    private fun cueTrack(segment: LocalAudioSegment, source: LocalAudioSource,
        metadata: LocalAudioMetadata = metadata("album.flac",
            durationMs = segment.durationMs, sizeBytes = 20_000L,
            title = if (segment.trackNumber == 1) "第一轨" else "第二轨")) =
        LocalCueImportTrack(metadata, source, segment)

    private fun metadata(fileName: String, title: String? = null, durationMs: Long? = 60_000L,
        sizeBytes: Long? = 20_000L, modifiedAtMs: Long? = null) =
        LocalAudioMetadata(fileName, title, null, durationMs, sizeBytes, modifiedAtMs,
            LocalMediaKind.AUDIO, null)

    private fun copySource(identity: String = "cue-v1:copy") =
        LocalAudioSource(LocalImportMode.COPY, albumCopy, identity)

    private fun segmentSource(identity: String) = LocalAudioSource(LocalImportMode.COPY, albumCopy, identity)

    private fun referenceSource(documentId: String, grant: String = "content://grant/album",
        identity: String = "cue-v1:$documentId"): LocalAudioSource =
        LocalAudioSource(LocalImportMode.REFERENCE,
            "content://com.android.externalstorage.documents/document/album",
            identity, grant)

    private fun progress(audioId: String, positionMs: Long) =
        LocalAudioProgress(audioId, positionMs, completed = false, updatedAtMs = 1_700_000_000_000L)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
            "%02x".format(it.toInt() and 0xFF)
        }

    private fun installVersionFourLibrary() {
        store.close()
        context.deleteDatabase(dbName)
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        val folderId = LocalAudioLimits.newId()
        legacyAudioId = LocalAudioLimits.newId()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE folders (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, " +
                "continue_playback INTEGER NOT NULL, created_at_ms INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE audio (id TEXT NOT NULL PRIMARY KEY, mode TEXT NOT NULL, identity TEXT NOT NULL, " +
                "location TEXT NOT NULL, grant_uri TEXT, file_name TEXT NOT NULL, title TEXT, creator TEXT, " +
                "duration_ms INTEGER, size_bytes INTEGER, modified_at_ms INTEGER, display_name TEXT, " +
                "availability TEXT NOT NULL, added_at_ms INTEGER NOT NULL, kind TEXT NOT NULL DEFAULT 'AUDIO', " +
                "artwork_key TEXT, playback_speed REAL NOT NULL DEFAULT 1.0, custom_artwork_key TEXT, " +
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
            db.execSQL("CREATE TABLE directories (id TEXT NOT NULL PRIMARY KEY, " +
                "folder_id TEXT NOT NULL REFERENCES folders (id) ON DELETE CASCADE, tree_uri TEXT NOT NULL, " +
                "name TEXT NOT NULL, mode TEXT NOT NULL, recursive INTEGER NOT NULL, last_refreshed_at_ms INTEGER, " +
                "missing_count INTEGER NOT NULL DEFAULT 0, error TEXT, created_at_ms INTEGER NOT NULL, " +
                "UNIQUE (folder_id, tree_uri))")
            db.execSQL("CREATE TABLE directory_entries (directory_id TEXT NOT NULL REFERENCES directories (id) " +
                "ON DELETE CASCADE, identity TEXT NOT NULL, audio_id TEXT NOT NULL REFERENCES audio (id) " +
                "ON DELETE CASCADE, size_bytes INTEGER, modified_at_ms INTEGER, " +
                "PRIMARY KEY (directory_id, identity))")
            db.insertOrThrow("folders", null, ContentValues().apply {
                put("id", folderId); put("name", "旧分组"); put("continue_playback", 1); put("created_at_ms", 1L)
            })
            db.insertOrThrow("audio", null, ContentValues().apply {
                put("id", legacyAudioId); put("mode", LocalImportMode.COPY.name)
                put("identity", "legacy-copy"); put("location", sha256("legacy-copy"))
                put("file_name", "legacy.mp3"); put("duration_ms", 60_000L); put("size_bytes", 1_024L)
                put("display_name", "旧名称"); put("availability", LocalAudioAvailability.AVAILABLE.name)
                put("added_at_ms", 2L); put("kind", LocalMediaKind.AUDIO.name)
            })
            db.insertOrThrow("members", null, ContentValues().apply {
                put("folder_id", folderId); put("audio_id", legacyAudioId); put("position", 0)
            })
            db.insertOrThrow("progress", null, ContentValues().apply {
                put("audio_id", legacyAudioId); put("position_ms", 25L * 60 * 60 * 1000)
                put("completed", 0); put("updated_at_ms", 3L)
            })
            db.insertOrThrow("meta", null, ContentValues().apply {
                put("key", "revision"); put("value", "7")
            })
            db.version = 4
        }
        store = SqliteLocalAudioStore(context, dbName)
    }

    private var legacyAudioId: String = ""

    private companion object {
        val albumCopy: String = "0123456789abcdef".repeat(4)
    }
}
