package tech.musxi.player.localaudio

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest
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
import tech.musxi.core.localaudio.LocalAudioDirectoryImport
import tech.musxi.core.localaudio.LocalAudioError
import tech.musxi.core.localaudio.LocalAudioFailure
import tech.musxi.core.localaudio.LocalAudioFolder
import tech.musxi.core.localaudio.LocalAudioLimits
import tech.musxi.core.localaudio.LocalAudioMetadata
import tech.musxi.core.localaudio.LocalAudioProgress
import tech.musxi.core.localaudio.LocalAudioSource
import tech.musxi.core.localaudio.LocalImportMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalDirectoryStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var dbName: String
    private lateinit var store: SqliteLocalAudioStore
    private lateinit var folder: LocalAudioFolder
    private val tree = "content://dir.test/tree/root"

    @Before fun setUp() {
        dbName = "dir-${LocalAudioLimits.newId().take(8)}.db"
        store = SqliteLocalAudioStore(context, dbName)
        folder = store.createFolder("音声")
    }

    @After fun tearDown() {
        store.close()
        context.deleteDatabase(dbName)
    }

    @Test fun repeatLinkKeepsUserNameAndRejectsChangedConfig() {
        val link = store.linkDirectory(folder.id, tree, "下载目录", LocalImportMode.COPY, false)

        assertEquals("下载目录", store.linkDirectory(folder.id, tree, "改名尝试", LocalImportMode.COPY, false).name)
        assertEquals(listOf(link), store.read().directories)
        assertEquals(LocalAudioError.SOURCE_CHANGED,
            assertThrows(LocalAudioFailure::class.java) {
                store.linkDirectory(folder.id, tree, "下载目录", LocalImportMode.REFERENCE, false)
            }.error)
        assertEquals(LocalAudioError.SOURCE_CHANGED,
            assertThrows(LocalAudioFailure::class.java) {
                store.linkDirectory(folder.id, tree, "下载目录", LocalImportMode.COPY, true)
            }.error)
        assertEquals(LocalImportMode.COPY, store.read().directories.single().mode)
    }

    @Test fun provenanceIsCommittedWithTheImportAndSurvivesUnlink() {
        val link = store.linkDirectory(folder.id, tree, "目录", LocalImportMode.REFERENCE, false)
        val origin = LocalAudioDirectoryImport(link.id, "doc-1", 1_024L, 5_000L)
        val item = store.importAudio(folder.id, metadata("a.mp3"), reference("doc-1"), origin)

        val entry = store.directoryEntry(link.id, "doc-1") ?: error("provenance missing")
        assertEquals(item.id, entry.audioId)
        assertEquals(1_024L, entry.origin.sizeBytes)
        store.saveProgress(folder.id, progress(item.id, 30_000L))

        store.unlinkDirectory(link.id)

        assertNull(store.directoryEntry(link.id, "doc-1"))
        assertNull(store.directory(link.id))
        val state = store.read()
        assertEquals(listOf(item.id), state.items.map { it.id })
        assertEquals(1, state.members.size)
        assertEquals(30_000L, state.progress.single().positionMs)
        assertTrue(state.directories.isEmpty())
    }

    @Test fun failedDirectoryImportWritesNeitherProvenanceNorAudio() {
        val link = store.linkDirectory(folder.id, tree, "目录", LocalImportMode.REFERENCE, false)
        val orphan = LocalAudioDirectoryImport(LocalAudioLimits.newId(), "doc-x", null, null)

        assertThrows(LocalAudioFailure::class.java) {
            store.importAudio(folder.id, metadata("x.mp3"), reference("doc-x"), orphan)
        }

        assertTrue(store.read().items.isEmpty())
        assertNull(store.directoryEntry(orphan.directoryId, "doc-x"))
        assertNull(store.directoryEntry(link.id, "doc-x"))
    }

    @Test fun missingIsCountedForBothModesButOnlyReferenceLosesAvailability() {
        val copyLink = store.linkDirectory(folder.id, tree, "复制", LocalImportMode.COPY, true)
        val refTree = "content://dir.test/tree/ref"
        val refLink = store.linkDirectory(folder.id, refTree, "引用", LocalImportMode.REFERENCE, false)
        val copied = store.importAudio(folder.id, metadata("c.mp3"), copy("c"),
            LocalAudioDirectoryImport(copyLink.id, "doc-c", 1_024L, null))
        // The source grant must be the directory's own tree, or the entry is a different origin.
        val referenced = store.importAudio(folder.id, metadata("r.mp3"), reference("doc-r", refTree),
            LocalAudioDirectoryImport(refLink.id, "doc-r", 1_024L, null))

        // A copy whose document vanished is counted, yet the library-owned copy stays playable.
        assertEquals(1, store.finishDirectoryRefresh(copyLink.id, emptySet(), 7_000L))
        assertEquals(LocalAudioAvailability.AVAILABLE,
            store.read().items.first { it.id == copied.id }.availability)

        assertEquals(1, store.finishDirectoryRefresh(refLink.id, emptySet(), 8_000L))
        assertEquals(LocalAudioAvailability.MISSING,
            store.read().items.first { it.id == referenced.id }.availability)
        assertEquals(8_000L, store.read().directories.first { it.id == refLink.id }.lastRefreshedAtMs)
        assertEquals(1, store.read().directories.first { it.id == refLink.id }.missingCount)
        assertEquals(1, store.read().directories.first { it.id == copyLink.id }.missingCount)
    }

    @Test fun seenEntryIsNotMissingAndAPerEntryErrorStillCountsTheListing() {
        val link = store.linkDirectory(folder.id, tree, "引用", LocalImportMode.REFERENCE, false)
        val kept = store.importAudio(folder.id, metadata("r.mp3"), reference("doc-r"),
            LocalAudioDirectoryImport(link.id, "doc-r", 1_024L, null))
        val vanished = store.importAudio(folder.id, metadata("g.mp3"), reference("doc-g"),
            LocalAudioDirectoryImport(link.id, "doc-g", 1_024L, null))

        // Duplicate identities in the listing collapse; both documents were seen.
        assertEquals(0, store.finishDirectoryRefresh(link.id, setOf("doc-r", "doc-g", "doc-r"), 9_000L))
        assertTrue(store.read().items.all { it.availability == LocalAudioAvailability.AVAILABLE })

        // The listing was complete; one entry failed. That is not a listing failure, so the missing
        // entry is still counted and the seen entry keeps its availability.
        assertEquals(1, store.finishDirectoryRefresh(link.id, setOf("doc-r"), 10_000L,
            LocalAudioError.PERMISSION_REQUIRED))
        assertEquals(LocalAudioError.PERMISSION_REQUIRED, store.read().directories.single().error)
        assertEquals(10_000L, store.read().directories.single().lastRefreshedAtMs)
        assertEquals(1, store.read().directories.single().missingCount)
        assertEquals(LocalAudioAvailability.AVAILABLE,
            store.read().items.first { it.id == kept.id }.availability)
        assertEquals(LocalAudioAvailability.MISSING,
            store.read().items.first { it.id == vanished.id }.availability)
    }

    @Test fun aFailedListingTouchesOnlyTheRecordedError() {
        val link = store.linkDirectory(folder.id, tree, "引用", LocalImportMode.REFERENCE, false)
        store.importAudio(folder.id, metadata("r.mp3"), reference("doc-r"),
            LocalAudioDirectoryImport(link.id, "doc-r", 1_024L, null))
        assertEquals(0, store.finishDirectoryRefresh(link.id, setOf("doc-r"), 9_000L))

        // A cancelled or failed listing is reported without inventing a refresh or a missing entry.
        store.setDirectoryError(link.id, LocalAudioError.PERMISSION_REQUIRED)

        val after = store.read()
        assertEquals(LocalAudioError.PERMISSION_REQUIRED, after.directories.single().error)
        assertEquals(9_000L, after.directories.single().lastRefreshedAtMs)
        assertEquals(0, after.directories.single().missingCount)
        assertEquals(LocalAudioAvailability.AVAILABLE, after.items.single().availability)

        store.setDirectoryError(link.id, null)
        assertNull(store.read().directories.single().error)
        assertEquals(9_000L, store.read().directories.single().lastRefreshedAtMs)
    }

    @Test fun unusableSeenIdentitiesFailInsteadOfShrinkingTheListing() {
        val link = store.linkDirectory(folder.id, tree, "引用", LocalImportMode.REFERENCE, false)
        store.importAudio(folder.id, metadata("r.mp3"), reference("doc-r"),
            LocalAudioDirectoryImport(link.id, "doc-r", 1_024L, null))

        assertEquals(LocalAudioError.READ_FAILED, assertThrows(LocalAudioFailure::class.java) {
            store.finishDirectoryRefresh(link.id, setOf("doc-r", " "), 13_000L)
        }.error)
        assertEquals(LocalAudioError.LIMIT_REACHED, assertThrows(LocalAudioFailure::class.java) {
            store.finishDirectoryRefresh(link.id,
                (0..LocalAudioLimits.MAX_ITEMS).mapTo(HashSet<String>()) { "doc-$it" }, 13_000L)
        }.error)
        // A rejected listing must not record a refresh or mark anything.
        assertNull(store.read().directories.single().lastRefreshedAtMs)
        assertEquals(LocalAudioAvailability.AVAILABLE, store.read().items.single().availability)
    }

    @Test fun importWithAForeignOrMismatchedDirectoryIsRejectedAtomically() {
        val other = store.createFolder("别处")
        val foreign = store.linkDirectory(other.id, tree, "别处目录", LocalImportMode.REFERENCE, false)
        val ownLink = store.linkDirectory(folder.id, "content://dir.test/tree/ref", "引用",
            LocalImportMode.REFERENCE, false)

        // Wrong folder for that association.
        assertEquals(LocalAudioError.SOURCE_CHANGED, assertThrows(LocalAudioFailure::class.java) {
            store.importAudio(folder.id, metadata("a.mp3"), reference("doc-a"),
                LocalAudioDirectoryImport(foreign.id, "doc-a", 1_024L, null))
        }.error)
        // Right folder, wrong mode for the source being imported.
        assertEquals(LocalAudioError.SOURCE_CHANGED, assertThrows(LocalAudioFailure::class.java) {
            store.importAudio(folder.id, metadata("a.mp3"), copy("a"),
                LocalAudioDirectoryImport(ownLink.id, "doc-a", 1_024L, null))
        }.error)

        val state = store.read()
        assertTrue(state.items.isEmpty())
        assertTrue(state.members.isEmpty())
        assertNull(store.directoryEntry(foreign.id, "doc-a"))
        assertNull(store.directoryEntry(ownLink.id, "doc-a"))
    }

    @Test fun relinkedReferenceIsNotMarkedMissingByAnOlderRefresh() {
        val link = store.linkDirectory(folder.id, tree, "引用", LocalImportMode.REFERENCE, false)
        val item = store.importAudio(folder.id, metadata("r.mp3"), reference("doc-r"),
            LocalAudioDirectoryImport(link.id, "doc-r", 1_024L, null))
        store.relink(item.id, metadata("r.mp3", sizeBytes = 2_048L), reference("doc-r-v2"))
        store.renameAudio(item.id, "改名")
        store.addBookmark(item.id, 1_000L, "书签")

        assertEquals(0, store.finishDirectoryRefresh(link.id, emptySet(), 11_000L))

        val state = store.read()
        assertEquals(LocalAudioAvailability.AVAILABLE, state.items.single().availability)
        assertEquals("改名", state.items.single().displayName)
        assertEquals(1, state.bookmarks.size)
        assertEquals(0, state.directories.single().missingCount)
    }

    @Test fun deleteFolderDropsDirectoriesButKeepsAudioAndGrantsOfOtherLinks() {
        val keep = store.createFolder("保留")
        val keepLink = store.linkDirectory(keep.id, "content://dir.test/tree/keep", "保留目录",
            LocalImportMode.COPY, false)
        val link = store.linkDirectory(folder.id, tree, "目录", LocalImportMode.COPY, false)
        val item = store.importAudio(folder.id, metadata("a.mp3"), copy("a"),
            LocalAudioDirectoryImport(link.id, "doc-a", 1_024L, null))

        store.deleteFolder(folder.id)

        val state = store.read()
        assertNull(store.directory(link.id))
        assertEquals(listOf(keepLink.id), state.directories.map { it.id })
        assertEquals(listOf(item.id), state.items.map { it.id })
        assertTrue(state.members.isEmpty())
        // Removing the folder drops its own tree; the surviving link keeps its grant.
        assertEquals(setOf("content://dir.test/tree/keep"), store.retainedGrantUris())
    }

    @Test fun modifiedTimeIsStoredAndGuardsReferenceContentChanges() {
        val link = store.linkDirectory(folder.id, tree, "引用", LocalImportMode.REFERENCE, false)
        val origin = LocalAudioDirectoryImport(link.id, "doc-r", 1_024L, 4_000L)
        val item = store.importAudio(folder.id, metadata("r.mp3", modifiedAtMs = 4_000L), reference("doc-r"), origin)

        assertEquals(4_000L, store.read().items.single().metadata.modifiedAtMs)
        // A known, unchanged pair is a plain re-scan and keeps the same row.
        assertEquals(item.id, store.importAudio(folder.id, metadata("r.mp3", modifiedAtMs = 4_000L),
            reference("doc-r"), origin).id)
        // A known, changed modification time is a different version of the document.
        assertEquals(LocalAudioError.SOURCE_CHANGED, assertThrows(LocalAudioFailure::class.java) {
            store.importAudio(folder.id, metadata("r.mp3", modifiedAtMs = 9_000L), reference("doc-r"), origin)
        }.error)
        // An unknown value is never read as a change.
        assertEquals(item.id, store.importAudio(folder.id, metadata("r.mp3", modifiedAtMs = null),
            reference("doc-r"), origin).id)
        assertEquals(4_000L, store.read().items.single().metadata.modifiedAtMs)
    }

    @Test fun changedCopyContentBecomesNewAudioAndKeepsRemovedMembershipOut() {
        val link = store.linkDirectory(folder.id, tree, "复制", LocalImportMode.COPY, false)
        val first = store.importAudio(folder.id, metadata("a.mp3"), copy("a-v1"),
            LocalAudioDirectoryImport(link.id, "doc-a", 1_024L, null))
        // The same document now holds other bytes, so the managed copy has a new audio ID.
        val second = store.importAudio(folder.id, metadata("a.mp3"), copy("a-v2"),
            LocalAudioDirectoryImport(link.id, "doc-a", 2_048L, null))
        assertNotEquals(first.id, second.id)
        assertEquals(2, store.read().items.size)
        assertEquals(2, store.read().members.size)

        // The user removed the file from the folder; a later refresh must not re-add it.
        store.removeFromFolder(folder.id, second.id)
        store.importAudio(folder.id, metadata("a.mp3"), copy("a-v3"),
            LocalAudioDirectoryImport(link.id, "doc-a", 4_096L, null))

        val state = store.read()
        assertEquals(3, state.items.size)
        assertEquals(setOf(first.id), state.members.map { it.audioId }.toSet())
        assertEquals(4_096L, store.directoryEntry(link.id, "doc-a")?.origin?.sizeBytes)
    }

    @Test fun versionOneLibraryIsMigratedWithoutLosingUserData() {
        // setUp already opened a schema-2 database; replace it with a hand-written version-1 file.
        store.close()
        context.deleteDatabase(dbName)
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        val audioId = LocalAudioLimits.newId()
        val legacyTree = "content://dir.test/tree/legacy"
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE folders (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, " +
                "continue_playback INTEGER NOT NULL, created_at_ms INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE audio (id TEXT NOT NULL PRIMARY KEY, mode TEXT NOT NULL, identity TEXT NOT NULL, " +
                "location TEXT NOT NULL, grant_uri TEXT, file_name TEXT NOT NULL, title TEXT, creator TEXT, " +
                "duration_ms INTEGER, size_bytes INTEGER, display_name TEXT, availability TEXT NOT NULL, " +
                "added_at_ms INTEGER NOT NULL, UNIQUE (mode, identity))")
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
            db.insertOrThrow("folders", null, ContentValues().apply {
                put("id", folder.id); put("name", "旧分组"); put("continue_playback", 1); put("created_at_ms", 1L)
            })
            db.insertOrThrow("audio", null, ContentValues().apply {
                put("id", audioId); put("mode", LocalImportMode.REFERENCE.name); put("identity", "doc-old")
                put("location", "content://dir.test/document/doc-old")
                put("grant_uri", legacyTree); put("file_name", "old.mp3"); put("title", "旧标题")
                put("size_bytes", 2_048L); put("display_name", "旧名称")
                put("availability", LocalAudioAvailability.AVAILABLE.name); put("added_at_ms", 2L)
            })
            db.insertOrThrow("members", null, ContentValues().apply {
                put("folder_id", folder.id); put("audio_id", audioId); put("position", 0)
            })
            db.insertOrThrow("progress", null, ContentValues().apply {
                put("audio_id", audioId); put("position_ms", 65_000L); put("completed", 0); put("updated_at_ms", 3L)
            })
            db.insertOrThrow("cursors", null, ContentValues().apply {
                put("folder_id", folder.id); put("audio_id", audioId)
            })
            db.insertOrThrow("bookmarks", null, ContentValues().apply {
                put("id", LocalAudioLimits.newId()); put("audio_id", audioId)
                put("position_ms", 1_000L); put("name", "旧书签"); put("created_at_ms", 4L)
            })
            db.insertOrThrow("meta", null, ContentValues().apply { put("key", "revision"); put("value", "7") })
            db.version = 1
        }
        store = SqliteLocalAudioStore(context, dbName)

        val state = store.read()
        assertEquals(audioId, state.items.single().id)
        assertEquals("旧名称", state.items.single().displayName)
        assertEquals("旧标题", state.items.single().metadata.title)
        assertEquals(2_048L, state.items.single().metadata.sizeBytes)
        // The upgrade leaves the unknown legacy modification time empty instead of guessing.
        assertNull(state.items.single().metadata.modifiedAtMs)
        assertEquals(1, state.members.size)
        assertEquals(65_000L, state.progress.single().positionMs)
        assertEquals(audioId, state.cursors.single().audioId)
        assertEquals("旧书签", state.bookmarks.single().name)
        assertEquals(7L, state.revision)

        // Schema 2 is usable immediately: directories can be linked and refreshed.
        val link = store.linkDirectory(folder.id, legacyTree, "新目录", LocalImportMode.REFERENCE, false)
        // The legacy audio is attributable to no provenance row, so nothing turns missing.
        assertEquals(0, store.finishDirectoryRefresh(link.id, emptySet(), 12_000L))
        assertEquals(12_000L, store.read().directories.single().lastRefreshedAtMs)
    }

    @Test fun publicSnapshotHidesTreeUriAndProvenanceIdentity() {
        val link = store.linkDirectory(folder.id, tree, "目录", LocalImportMode.REFERENCE, true)
        store.importAudio(folder.id, metadata("a.mp3"), reference("secret-doc"),
            LocalAudioDirectoryImport(link.id, "secret-doc", 1_024L, 5_000L))

        val published = store.read().toString()

        assertFalse(published.contains("content://"))
        assertFalse(published.contains("secret-doc"))
        // The backend keeps the tree and identity reachable, but redacts them in toString().
        assertEquals(tree, store.directory(link.id)?.treeUri)
        assertFalse(store.directory(link.id).toString().contains("secret-doc"))
        assertFalse(store.directory(link.id).toString().contains("content://"))
    }

    @Test fun unknownOnDiskVersionFailsWithoutResettingTheLibrary() {
        store.close()
        context.deleteDatabase(dbName)
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE meta (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
            db.version = 99
        }
        val before = file.readBytes()
        store = SqliteLocalAudioStore(context, dbName)

        assertEquals(LocalAudioError.READ_FAILED,
            assertThrows(LocalAudioFailure::class.java) { store.read() }.error)
        org.junit.Assert.assertArrayEquals(before, file.readBytes())
    }

    private fun metadata(
        fileName: String,
        title: String? = "标题",
        creator: String? = null,
        durationMs: Long? = 60_000L,
        sizeBytes: Long? = 1_024L,
        modifiedAtMs: Long? = null,
    ) = LocalAudioMetadata(fileName, title, creator, durationMs, sizeBytes, modifiedAtMs)

    private fun copy(identity: String) = LocalAudioSource(LocalImportMode.COPY, sha256(identity), identity)

    private fun reference(documentId: String, grant: String = tree): LocalAudioSource {
        val uri = "content://dir.test/document/$documentId"
        return LocalAudioSource(LocalImportMode.REFERENCE, uri, documentId, grant)
    }

    private fun progress(audioId: String, positionMs: Long) =
        LocalAudioProgress(audioId, positionMs, completed = false, updatedAtMs = 1_700_000_000_000L)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
            "%02x".format(it.toInt() and 0xFF)
        }
}
