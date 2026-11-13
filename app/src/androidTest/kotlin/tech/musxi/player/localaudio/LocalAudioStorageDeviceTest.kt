package tech.musxi.player.localaudio

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.localaudio.LocalAudioBackup
import tech.musxi.core.localaudio.LocalAudioBackupCodec
import tech.musxi.core.localaudio.LocalAudioBackupItem
import tech.musxi.core.localaudio.LocalAudioBackupKeys
import tech.musxi.core.localaudio.LocalAudioBatchAction
import tech.musxi.core.localaudio.LocalAudioError
import tech.musxi.core.localaudio.LocalAudioFailure
import tech.musxi.core.localaudio.LocalAudioLimits
import tech.musxi.core.localaudio.LocalAudioMetadata
import tech.musxi.core.localaudio.LocalAudioProgress
import tech.musxi.core.localaudio.LocalAudioProgressFilter
import tech.musxi.core.localaudio.LocalAudioQuery
import tech.musxi.core.localaudio.LocalAudioSearch
import tech.musxi.core.localaudio.LocalAudioSort
import tech.musxi.core.localaudio.LocalAudioSource
import tech.musxi.core.localaudio.LocalImportMode

/**
 * Runs the real [SqliteLocalAudioStore] against the device's own SQLite implementation.
 *
 * Every test opens a unique throwaway database name, never `local-audio-v1.db`, and only deletes
 * the files it created. Nothing here touches the user's library, accounts, queue, documents,
 * directory grants or playback; no audio byte is ever written or read.
 */
class LocalAudioStorageDeviceTest {

    /**
     * Goal: importing into two folders and batch-organizing through real transactions still reads
     * back the same library after the database is closed and reopened (no in-memory state involved).
     */
    @Test fun importAndBatchOrganizationSurviveColdReopen() = isolated { devices ->
        val database = devices.newDatabase()
        val store = database.open()
        val bedtime = store.createFolder("睡前")
        val favourites = store.createFolder("收藏")
        val voice = store.importAudio(bedtime.id, metadata("voice.mp3"), copySource("copy-voice"))
        val book = store.importAudio(bedtime.id, metadata("book.m4b", title = "长书"), referenceSource("doc-book"))
        val radio = store.importAudio(bedtime.id, metadata("radio.m4a"), copySource("copy-radio"))
        store.renameAudio(voice.id, "开场白")

        // The same file in a second folder stays one row with two memberships.
        val reimported = store.importAudio(favourites.id, metadata("voice.mp3"), copySource("copy-voice"))
        assertEquals(voice.id, reimported.id)
        // Re-adding existing members is an honest no-op: nothing changes, so nothing is bumped.
        val batchRevision = store.read().revision
        val noop = store.organize(listOf(voice.id, radio.id), LocalAudioBatchAction.ADD, bedtime.id,
            expectedRevision = batchRevision)
        assertEquals(0, noop.addedMemberships)
        assertEquals(batchRevision, store.read().revision)

        val moved = store.organize(listOf(radio.id), LocalAudioBatchAction.MOVE, bedtime.id, favourites.id,
            expectedRevision = store.read().revision)
        assertEquals(1, moved.removedMemberships)
        assertEquals(1, moved.addedMemberships)
        store.organize(listOf(voice.id), LocalAudioBatchAction.ADD, favourites.id,
            expectedRevision = store.read().revision)
        val beforeReopen = store.read()

        val cold = database.reopen()
        val reopened = cold.read()
        assertEquals(listOf(bedtime, favourites), reopened.folders)
        assertEquals(3, reopened.items.size)
        assertEquals(setOf(voice.id, book.id, radio.id), reopened.items.map { it.id }.toSet())
        assertEquals("开场白", reopened.items.single { it.id == voice.id }.displayName)
        assertEquals(
            listOf(bedtime.id to voice.id, bedtime.id to book.id),
            reopened.members.filter { it.folderId == bedtime.id }.map { it.folderId to it.audioId },
        )
        assertEquals(
            listOf(voice.id, radio.id),
            reopened.members.filter { it.folderId == favourites.id }.map { it.audioId },
        )
        assertEquals(listOf(0, 1), reopened.members.filter { it.folderId == favourites.id }.map { it.position })
        assertEquals(beforeReopen.revision, reopened.revision)
        assertTrue(reopened.revision > 0L)

        // Private locations stay available through the backend capability, never through the state.
        assertEquals(setOf(sha256("copy-voice"), sha256("copy-radio")), cold.retainedCopyNames())
        assertEquals(setOf("content://grant/doc-book"), cold.retainedGrantUris())
        val storedBook = cold.source(book.id) ?: error("reference source missing after reopen")
        assertEquals(LocalImportMode.REFERENCE, storedBook.source.mode)
        assertEquals("content://com.android.externalstorage.documents/document/doc-book", storedBook.source.location)
        assertNull(cold.source("not-a-uuid"))
        assertFalse(reopened.toString().contains("content://"))
        assertFalse(reopened.toString().contains(sha256("copy-voice")))
    }

    /**
     * Goal: a listening position beyond 24 hours (and beyond Int milliseconds) and a bookmark in the
     * same range come back bit-identical after a cold reopen, so Long columns are not truncated.
     */
    @Test fun multiDayProgressAndBookmarkRoundTripAsLongAfterColdReopen() = isolated { devices ->
        val database = devices.newDatabase()
        val store = database.open()
        val folder = store.createFolder("整本有声书")
        val saga = store.importAudio(folder.id, metadata("saga.m4b", title = "长篇"), referenceSource("doc-saga"))
        val short = store.importAudio(folder.id, metadata("short.mp3"), copySource("copy-short"))
        assertTrue(MULTI_DAY_POSITION > Int.MAX_VALUE)

        store.saveProgress(folder.id, progress(saga.id, MULTI_DAY_POSITION))
        store.saveProgress(null, LocalAudioProgress(short.id, 5_000L, completed = true, updatedAtMs = 1_700_000_000_001L))
        val bookmark = store.addBookmark(saga.id, MULTI_DAY_POSITION - 1_000L, "第 700 小时")
        val revisionBeforeReopen = store.read().revision

        val cold = database.reopen().read()
        assertEquals(MULTI_DAY_POSITION, cold.progress.single { it.audioId == saga.id }.positionMs)
        assertTrue(cold.progress.single { it.audioId == short.id }.completed)
        assertEquals(5_000L, cold.progress.single { it.audioId == short.id }.positionMs)
        assertEquals(listOf(bookmark), cold.bookmarks)
        assertEquals(MULTI_DAY_POSITION - 1_000L, cold.bookmarks.single().positionMs)
        assertEquals("第 700 小时", cold.bookmarks.single().name)
        assertEquals(listOf(folder.id to saga.id), cold.cursors.map { it.folderId to it.audioId })
        assertEquals(revisionBeforeReopen, cold.revision)
    }

    /**
     * Goal: a batch that is stale, refers to an unknown row, repeats an ID or cancels mid-flight
     * leaves the memberships exactly as they were — the transaction never writes a prefix.
     */
    @Test fun staleUnknownDuplicateOrCancelledBatchWritesNothing() = isolated { devices ->
        val store = devices.newDatabase().open()
        val from = store.createFolder("来源")
        val to = store.createFolder("目标")
        val first = store.importAudio(from.id, metadata("a.mp3"), copySource("copy-a"))
        val second = store.importAudio(from.id, metadata("b.mp3"), copySource("copy-b"))
        val third = store.importAudio(from.id, metadata("c.mp3"), copySource("copy-c"))
        val staleRevision = store.read().revision
        store.renameFolder(from.id, "来源改名")
        val revisionAfterRename = store.read().revision
        assertEquals(staleRevision + 1, revisionAfterRename)

        assertEquals(LocalAudioError.STALE_STATE, failureOf {
            store.organize(listOf(first.id), LocalAudioBatchAction.MOVE, from.id, to.id,
                expectedRevision = staleRevision)
        })
        assertEquals(LocalAudioError.NOT_FOUND, failureOf {
            store.organize(listOf(first.id, second.id, LocalAudioLimits.newId()), LocalAudioBatchAction.MOVE,
                from.id, to.id, expectedRevision = revisionAfterRename)
        })
        assertEquals(LocalAudioError.INVALID_ORDER, failureOf {
            store.organize(listOf(first.id, first.id), LocalAudioBatchAction.ADD, from.id,
                expectedRevision = revisionAfterRename)
        })
        assertEquals(LocalAudioError.INVALID_ORDER, failureOf {
            store.organize(listOf(first.id), LocalAudioBatchAction.MOVE, from.id, from.id,
                expectedRevision = revisionAfterRename)
        })
        var checks = 0
        val cancelled = runCatching {
            // The seventh cancellation check is after dropping the first source membership.
            // Rolling back here exercises a real partial SQL write, not only pre-validation.
            store.organize(listOf(first.id), LocalAudioBatchAction.MOVE, from.id, to.id,
                expectedRevision = revisionAfterRename) {
                checks++
                if (checks == 7) throw CancellationException("user left after first removal")
            }
        }
        assertTrue("cancellation must surface unchanged", cancelled.exceptionOrNull() is CancellationException)
        assertEquals(7, checks)

        val untouched = store.read()
        assertEquals(revisionAfterRename, untouched.revision)
        assertEquals(listOf(first.id, second.id, third.id),
            untouched.members.filter { it.folderId == from.id }.map { it.audioId })
        assertTrue(untouched.members.none { it.folderId == to.id })

        val accepted = store.organize(listOf(first.id), LocalAudioBatchAction.MOVE, from.id, to.id,
            expectedRevision = revisionAfterRename)
        assertEquals(1, accepted.addedMemberships)
        assertEquals(1, accepted.removedMemberships)
        assertEquals(revisionAfterRename + 1, store.read().revision)
        assertEquals(listOf(first.id), store.read().members.filter { it.folderId == to.id }.map { it.audioId })
        assertEquals(listOf(second.id, third.id),
            store.read().members.filter { it.folderId == from.id }.map { it.audioId })
    }

    /**
     * Goal: persisted rows are searched and ordered by the shared pure query layer with natural
     * numeric order, folder unfiled scoping and progress/bookmark filters, and a stale expected
     * revision is refused instead of paging over a library that already moved on.
     */
    @Test fun searchOrdersNaturallyAndFiltersPersistedRows() = isolated { devices ->
        val store = devices.newDatabase().open()
        val folder = store.createFolder("章节")
        val unfiled = store.createFolder("待整理")
        val one = store.importAudio(folder.id, metadata("c1.m4b", title = "第1章"), referenceSource("doc-1"))
        val two = store.importAudio(folder.id, metadata("c2.m4b", title = "第2章"), referenceSource("doc-2"))
        val ten = store.importAudio(folder.id, metadata("c10.m4b", title = "第10章"), referenceSource("doc-10"))
        val loose = store.importAudio(unfiled.id, metadata("loose.mp3", title = "第3章"), copySource("copy-loose"))
        store.removeFromFolder(unfiled.id, loose.id)
        store.saveProgress(folder.id, progress(two.id, 90_000L))
        store.saveProgress(null, LocalAudioProgress(ten.id, 1_000L, completed = true, updatedAtMs = 1_700_000_000_002L))
        store.addBookmark(one.id, 15_000L, "开头")
        val state = store.read()

        val byTitle = LocalAudioSearch.query(state, LocalAudioQuery(folderId = folder.id, sort = LocalAudioSort.TITLE))
        assertEquals(listOf(one.id, two.id, ten.id), byTitle.items.map { it.id })
        assertEquals(3, byTitle.total)
        assertNull(byTitle.nextOffset)
        assertEquals(state.revision, byTitle.revision)
        val descending = LocalAudioSearch.query(state,
            LocalAudioQuery(folderId = folder.id, sort = LocalAudioSort.TITLE, descending = true))
        assertEquals(listOf(ten.id, two.id, one.id), descending.items.map { it.id })
        val byFileName = LocalAudioSearch.query(state,
            LocalAudioQuery(folderId = folder.id, sort = LocalAudioSort.FILE_NAME))
        assertEquals(listOf(one.id, two.id, ten.id), byFileName.items.map { it.id })

        val inProgress = LocalAudioSearch.query(state, LocalAudioQuery(folderId = folder.id, text = " 第 ",
            progress = LocalAudioProgressFilter.IN_PROGRESS))
        assertEquals(listOf(two.id), inProgress.items.map { it.id })
        val completed = LocalAudioSearch.query(state, LocalAudioQuery(progress = LocalAudioProgressFilter.COMPLETED))
        assertEquals(listOf(ten.id), completed.items.map { it.id })
        val bookmarked = LocalAudioSearch.query(state, LocalAudioQuery(folderId = folder.id, bookmarkedOnly = true))
        assertEquals(listOf(one.id), bookmarked.items.map { it.id })
        val onlyLoose = LocalAudioSearch.query(state, LocalAudioQuery(unfiledOnly = true))
        assertEquals(listOf(loose.id), onlyLoose.items.map { it.id })
        assertFalse(byTitle.toString().contains("content://"))

        assertEquals(LocalAudioError.STALE_STATE, failureOf {
            LocalAudioSearch.query(state,
                LocalAudioQuery(folderId = folder.id, expectedRevision = state.revision - 1))
        })
        assertEquals(LocalAudioError.NOT_FOUND, failureOf {
            LocalAudioSearch.query(state, LocalAudioQuery(folderId = LocalAudioLimits.newId()))
        })
    }

    /**
     * Goal: an organization-only backup written and re-read by the real codec merges onto matching
     * audio in a second database — it adds folders, membership, progress and bookmarks without
     * importing rows, overwriting the local alias or touching anything immediately stale.
     */
    @Test fun backupMergeRestoresOrganizationWithoutTouchingCurrentAudio() = isolated { devices ->
        val origin = devices.newDatabase().open()
        val folder = origin.createFolder("有声书")
        val kept = origin.importAudio(folder.id, metadata("book-1.m4b", title = "第一章"), referenceSource("doc-book-1"))
        origin.importAudio(folder.id, metadata("book-2.m4b", title = "第二章"), referenceSource("doc-book-2"))
        origin.saveProgress(folder.id, progress(kept.id, MULTI_DAY_POSITION))
        val bookmark = origin.addBookmark(kept.id, MULTI_DAY_POSITION - 60_000L, "长书签")
        val snapshot = origin.read()
        val backup = LocalAudioBackup(
            id = LocalAudioLimits.newId(),
            createdAtMs = 1_700_000_000_000L,
            items = snapshot.items.map { item ->
                LocalAudioBackupItem(item, LocalAudioBackupKeys.source(origin.source(item.id) ?: error("no source")))
            },
            folders = snapshot.folders,
            members = snapshot.members,
            progress = snapshot.progress,
            cursors = snapshot.cursors,
            bookmarks = snapshot.bookmarks,
        )
        val bytes = ByteArrayOutputStream().also { LocalAudioBackupCodec.write(backup, it) }.toByteArray()
        val decoded = LocalAudioBackupCodec.read(bytes.inputStream())
        assertEquals(2, decoded.items.size)
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("content://"))

        val target = devices.newDatabase().open()
        val localFolder = target.createFolder("本机")
        val localKept = target.importAudio(localFolder.id,
            metadata("book-1.m4b", title = "第一章"), referenceSource("doc-book-1"))
        target.renameAudio(localKept.id, "本机改名")

        val preview = target.previewOrganization(decoded)
        assertEquals(1, preview.matchedItems)
        assertEquals(1, preview.unmatchedItems)
        assertEquals(1, preview.folders)
        assertEquals(1, preview.bookmarks)
        val result = target.restoreOrganization(decoded, preview.expectedRevision)
        assertEquals(backup.id, result.backupId)
        assertEquals(1, result.matchedItems)
        assertEquals(1, result.unmatchedItems)
        assertEquals(1, result.addedFolders)
        assertEquals(1, result.addedMemberships)
        assertEquals(1, result.addedBookmarks)

        val merged = target.read()
        // A merge never imports audio rows, and the local alias wins over the backup alias.
        assertEquals(1, merged.items.size)
        assertEquals(localKept.id, merged.items.single().id)
        assertEquals("本机改名", merged.items.single().displayName)
        assertEquals(listOf(localFolder, folder), merged.folders)
        // The restored membership points at the matched local row, not at the backup's audio ID.
        assertEquals(listOf(localKept.id), merged.members.filter { it.folderId == folder.id }.map { it.audioId })
        assertEquals(listOf(localKept.id), merged.members.filter { it.folderId == localFolder.id }.map { it.audioId })
        assertEquals(MULTI_DAY_POSITION, merged.progress.single().positionMs)
        assertEquals(bookmark.name, merged.bookmarks.single().name)
        assertEquals(bookmark.positionMs, merged.bookmarks.single().positionMs)

        val revisionAfterMerge = merged.revision
        assertEquals(LocalAudioError.STALE_STATE, failureOf {
            target.restoreOrganization(decoded, preview.expectedRevision)
        })
        val afterStale = target.read()
        assertEquals(revisionAfterMerge, afterStale.revision)
        assertEquals(1, afterStale.folders.count { it.id == folder.id })
        assertEquals(1, afterStale.members.count { it.folderId == folder.id })
        assertEquals(1, afterStale.bookmarks.size)
    }

    private fun isolated(block: (ScopedDevices) -> Unit) {
        val devices = ScopedDevices(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            block(devices)
        } finally {
            devices.cleanUp()
        }
    }

    /** One throwaway database; [reopen] simulates a cold start against the same file. */
    private class IsolatedDatabase(private val context: Context, val name: String) {
        private var store = SqliteLocalAudioStore(context, name)

        fun open(): SqliteLocalAudioStore = store

        fun reopen(): SqliteLocalAudioStore {
            store.close()
            store = SqliteLocalAudioStore(context, name)
            return store
        }

        fun close() = store.close()
    }

    /** Owns every database a single test creates and removes only those files afterwards. */
    private class ScopedDevices(private val context: Context) {
        private val databases = mutableListOf<IsolatedDatabase>()

        fun newDatabase(): IsolatedDatabase {
            val name = "local-audio-device-${UUID.randomUUID()}.db"
            check(name != SqliteLocalAudioStore.DB_NAME) { "device test must never open the production library" }
            return IsolatedDatabase(context, name).also { databases += it }
        }

        fun cleanUp() {
            databases.forEach { database ->
                runCatching { database.close() }
                val path = context.getDatabasePath(database.name)
                context.deleteDatabase(database.name)
                listOf("-journal", "-wal", "-shm").forEach { suffix -> File(path.path + suffix).delete() }
                check(!path.exists()) { "device database cleanup failed" }
            }
        }
    }

    private fun metadata(
        fileName: String,
        title: String? = null,
        durationMs: Long? = 60_000L,
        sizeBytes: Long? = 1_024L,
    ) = LocalAudioMetadata(fileName, title, null, durationMs, sizeBytes)

    private fun copySource(identity: String) =
        LocalAudioSource(LocalImportMode.COPY, sha256(identity), identity)

    private fun referenceSource(documentId: String): LocalAudioSource {
        val uri = "content://com.android.externalstorage.documents/document/$documentId"
        return LocalAudioSource(LocalImportMode.REFERENCE, uri, documentId, "content://grant/$documentId")
    }

    private fun progress(audioId: String, positionMs: Long) =
        LocalAudioProgress(audioId, positionMs, completed = false, updatedAtMs = 1_700_000_000_000L)

    private fun failureOf(block: () -> Unit): LocalAudioError {
        try {
            block()
        } catch (e: LocalAudioFailure) {
            return e.error
        }
        throw AssertionError("expected a LocalAudioFailure")
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
            "%02x".format(it.toInt() and 0xFF)
        }

    private companion object {
        /** 700 hours, deliberately past the 24-hour platform cap and past Int milliseconds. */
        const val MULTI_DAY_POSITION = 700L * 60 * 60 * 1000
    }
}
