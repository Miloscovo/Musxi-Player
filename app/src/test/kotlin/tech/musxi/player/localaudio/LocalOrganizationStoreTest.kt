package tech.musxi.player.localaudio

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.musxi.core.localaudio.LocalAudioBatchAction
import tech.musxi.core.localaudio.LocalAudioBatchResult
import tech.musxi.core.localaudio.LocalAudioDirectoryImport
import tech.musxi.core.localaudio.LocalAudioError
import tech.musxi.core.localaudio.LocalAudioFailure
import tech.musxi.core.localaudio.LocalAudioLimits
import tech.musxi.core.localaudio.LocalAudioMetadata
import tech.musxi.core.localaudio.LocalAudioProgress
import tech.musxi.core.localaudio.LocalAudioSort
import tech.musxi.core.localaudio.LocalAudioSortResult
import tech.musxi.core.localaudio.LocalAudioSource
import tech.musxi.core.localaudio.LocalImportMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalOrganizationStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var dbName: String
    private lateinit var store: SqliteLocalAudioStore

    @Before fun setUp() {
        dbName = "organize-${LocalAudioLimits.newId().take(8)}.db"
        store = SqliteLocalAudioStore(context, dbName)
    }

    @After fun tearDown() {
        store.close()
        context.deleteDatabase(dbName)
    }

    @Test fun addBatchAppendsInGivenOrderAndKeepsAudioData() {
        val target = store.createFolder("目标")
        val source = store.createFolder("来源")
        val first = store.importAudio(target.id, metadata("a.mp3", title = "甲"), copySource("a"))
        val existing = store.importAudio(target.id, metadata("b.mp3", title = "乙"), copySource("b"))
        val one = store.importAudio(source.id, metadata("1.mp3", title = "一"), copySource("1"))
        val two = store.importAudio(source.id, metadata("2.mp3", title = "二"), copySource("2"))
        val three = store.importAudio(source.id, metadata("3.mp3", title = "三"), copySource("3"))
        store.renameAudio(three.id, "自定义名")
        store.saveProgress(source.id, progress(three.id, 42_000L))
        val bookmark = store.addBookmark(three.id, 7_000L, "标记")
        // Already filed in the destination: it must not be appended twice.
        store.addToFolder(target.id, one.id)
        val revision = store.read().revision

        val result = store.organize(
            listOf(three.id, one.id, two.id), LocalAudioBatchAction.ADD, target.id,
            expectedRevision = revision,
        )

        assertEquals(LocalAudioBatchResult(LocalAudioBatchAction.ADD, 3, 2, 0), result)
        val after = store.read()
        // The batch keeps the caller's order and continues after the existing tail.
        assertEquals(
            listOf(first.id, existing.id, one.id, three.id, two.id),
            after.members.filter { it.folderId == target.id }.sortedBy { it.position }.map { it.audioId },
        )
        // Source membership, user data and the audio rows themselves are untouched.
        assertEquals(3, after.members.count { it.folderId == source.id })
        assertEquals("自定义名", after.items.single { it.id == three.id }.displayName)
        assertEquals(42_000L, after.progress.single { it.audioId == three.id }.positionMs)
        assertEquals(listOf(bookmark), after.bookmarks)
        assertEquals(5, after.items.size)
        assertEquals(revision + 1, after.revision)

        // Repeating the identical batch is a no-op: nothing is added and the revision stays put.
        val repeated = store.organize(
            listOf(three.id, one.id, two.id), LocalAudioBatchAction.ADD, target.id,
            expectedRevision = after.revision,
        )
        assertEquals(LocalAudioBatchResult(LocalAudioBatchAction.ADD, 3, 0, 0), repeated)
        assertEquals(after.revision, store.read().revision)
        assertEquals(5, store.read().members.count { it.folderId == target.id })
    }

    @Test fun moveBatchKeepsUserDataAndOnlyClearsSourceCursor() {
        val source = store.createFolder("来源")
        val target = store.createFolder("目标")
        val kept = store.importAudio(target.id, metadata("keep.mp3"), copySource("keep"))
        val moved = store.importAudio(source.id, metadata("m.mp3", title = "搬"), copySource("m"))
        val other = store.importAudio(source.id, metadata("o.mp3", title = "留"), copySource("o"))
        store.saveProgress(target.id, progress(kept.id, 1_000L))
        store.saveProgress(source.id, progress(moved.id, 90_000L))
        val bookmark = store.addBookmark(moved.id, 3_000L, "书签")
        store.renameAudio(moved.id, "手动命名")
        val revision = store.read().revision
        assertEquals(moved.id, store.read().cursors.single { it.folderId == source.id }.audioId)

        val result = store.organize(
            listOf(moved.id), LocalAudioBatchAction.MOVE, source.id, target.id, revision,
        )

        assertEquals(LocalAudioBatchResult(LocalAudioBatchAction.MOVE, 1, 1, 1), result)
        val after = store.read()
        assertEquals(listOf(other.id), after.members.filter { it.folderId == source.id }.map { it.audioId })
        assertEquals(
            listOf(kept.id, moved.id),
            after.members.filter { it.folderId == target.id }.sortedBy { it.position }.map { it.audioId },
        )
        // Only the source cursor is cleared; the destination cursor and every other row survive.
        assertTrue(after.cursors.none { it.folderId == source.id })
        assertEquals(kept.id, after.cursors.single { it.folderId == target.id }.audioId)
        assertEquals(2, after.progress.size)
        assertEquals(90_000L, after.progress.single { it.audioId == moved.id }.positionMs)
        assertEquals(listOf(bookmark), after.bookmarks)
        assertEquals("手动命名", after.items.single { it.id == moved.id }.displayName)
        assertEquals(3, after.items.size)
        assertEquals(revision + 1, after.revision)

        // Moving something already present in the destination still drops the source membership,
        // but never creates a second target row.
        store.addToFolder(target.id, other.id)
        val beforeSecond = store.read().revision
        val second = store.organize(
            listOf(other.id), LocalAudioBatchAction.MOVE, source.id, target.id, beforeSecond,
        )
        assertEquals(LocalAudioBatchResult(LocalAudioBatchAction.MOVE, 1, 0, 1), second)
        val final = store.read()
        assertTrue(final.members.none { it.folderId == source.id })
        assertEquals(1, final.members.count { it.folderId == target.id && it.audioId == other.id })
        assertEquals(beforeSecond + 1, final.revision)
    }

    @Test fun removeBatchDropsMembershipAndSourceCursorOnly() {
        val folder = store.createFolder("组")
        val other = store.createFolder("另一组")
        val one = store.importAudio(folder.id, metadata("1.mp3"), referenceSource("doc-1"))
        val two = store.importAudio(folder.id, metadata("2.mp3"), referenceSource("doc-2"))
        store.addToFolder(other.id, two.id)
        store.saveProgress(folder.id, progress(two.id, 12_000L))
        val bookmark = store.addBookmark(two.id, 1_000L, "书签")
        val grantBefore = store.retainedGrantUris()
        val revision = store.read().revision

        val result = store.organize(listOf(one.id, two.id), LocalAudioBatchAction.REMOVE, folder.id,
            expectedRevision = revision)

        assertEquals(LocalAudioBatchResult(LocalAudioBatchAction.REMOVE, 2, 0, 2), result)
        val after = store.read()
        assertTrue(after.members.none { it.folderId == folder.id })
        assertTrue(after.cursors.isEmpty())
        // The audio, its other membership, progress, bookmark and stored grant all remain.
        assertEquals(2, after.items.size)
        assertEquals(listOf(two.id), after.members.map { it.audioId })
        assertEquals(other.id, after.members.single().folderId)
        assertEquals(12_000L, after.progress.single().positionMs)
        assertEquals(listOf(bookmark), after.bookmarks)
        assertEquals(grantBefore, store.retainedGrantUris())
        assertEquals(revision + 1, after.revision)
    }

    @Test fun invalidBatchesAreRejectedWithoutTouchingLibrary() {
        val folder = store.createFolder("组")
        val other = store.createFolder("另一组")
        val item = store.importAudio(folder.id, metadata("a.mp3"), copySource("a"))
        val second = store.importAudio(folder.id, metadata("b.mp3"), copySource("b"))
        val before = store.read()
        val missing = LocalAudioLimits.newId()

        fun organised(
            ids: List<String>,
            action: LocalAudioBatchAction,
            folderId: String = folder.id,
            destination: String? = null,
            revision: Long = before.revision,
        ) = assertThrows(LocalAudioFailure::class.java) {
            store.organize(ids, action, folderId, destination, revision)
        }.error

        assertEquals(LocalAudioError.LIMIT_REACHED, organised(emptyList(), LocalAudioBatchAction.ADD))
        assertEquals(
            LocalAudioError.LIMIT_REACHED,
            organised(List(LocalAudioLimits.MAX_BATCH + 1) { LocalAudioLimits.newId() }, LocalAudioBatchAction.ADD),
        )
        for (action in listOf(LocalAudioBatchAction.REMOVE, LocalAudioBatchAction.MOVE)) {
            assertEquals(LocalAudioError.LIMIT_REACHED,
                organised(List(LocalAudioLimits.MAX_BATCH + 1) { LocalAudioLimits.newId() }, action,
                    destination = other.id.takeIf { action == LocalAudioBatchAction.MOVE }))
        }
        assertEquals(
            LocalAudioError.INVALID_ORDER,
            organised(listOf(item.id, item.id), LocalAudioBatchAction.ADD),
        )
        assertEquals(LocalAudioError.INVALID_ORDER, organised(listOf("not-an-id"), LocalAudioBatchAction.ADD))
        assertEquals(LocalAudioError.NOT_FOUND, organised(listOf(missing), LocalAudioBatchAction.ADD))
        assertEquals(LocalAudioError.NOT_FOUND, organised(listOf(item.id, missing), LocalAudioBatchAction.ADD))
        assertEquals(LocalAudioError.NOT_FOUND, organised(listOf(item.id, missing), LocalAudioBatchAction.REMOVE))
        assertEquals(LocalAudioError.NOT_FOUND,
            organised(listOf(item.id, missing), LocalAudioBatchAction.MOVE, folder.id, other.id))
        assertEquals(
            LocalAudioError.NOT_FOUND,
            organised(listOf(missing), LocalAudioBatchAction.MOVE, folder.id, other.id),
        )
        // Argument shapes that cannot describe the requested action.
        assertEquals(
            LocalAudioError.INVALID_ORDER,
            organised(listOf(item.id), LocalAudioBatchAction.ADD, folder.id, other.id),
        )
        assertEquals(
            LocalAudioError.INVALID_ORDER,
            organised(listOf(item.id), LocalAudioBatchAction.REMOVE, folder.id, other.id),
        )
        assertEquals(LocalAudioError.INVALID_ORDER, organised(listOf(item.id), LocalAudioBatchAction.MOVE))
        assertEquals(
            LocalAudioError.INVALID_ORDER,
            organised(listOf(item.id), LocalAudioBatchAction.MOVE, folder.id, folder.id),
        )
        assertEquals(
            LocalAudioError.NOT_FOUND,
            organised(listOf(item.id), LocalAudioBatchAction.MOVE, folder.id, LocalAudioLimits.newId()),
        )
        assertEquals(
            LocalAudioError.NOT_FOUND,
            organised(listOf(second.id), LocalAudioBatchAction.ADD, LocalAudioLimits.newId()),
        )
        assertEquals(before, store.read())

        // An ID that exists but is not a member of the named source folder is a missing member, not
        // a silent no-op.
        val current = store.read()
        assertEquals(
            LocalAudioError.NOT_FOUND,
            organised(listOf(item.id), LocalAudioBatchAction.REMOVE, other.id, revision = current.revision),
        )
        assertEquals(current, store.read())
    }

    @Test fun staleRevisionRejectsWholeBatchAndSort() {
        val folder = store.createFolder("组")
        val item = store.importAudio(folder.id, metadata("a.mp3"), copySource("a"))
        val stale = store.read().revision - 1

        val batch = assertThrows(LocalAudioFailure::class.java) {
            store.organize(listOf(item.id), LocalAudioBatchAction.ADD, folder.id, expectedRevision = stale)
        }
        assertEquals(LocalAudioError.STALE_STATE, batch.error)

        store.addToFolder(folder.id, store.importAudio(folder.id, metadata("b.mp3"), copySource("b")).id)
        val current = store.read()
        val sort = assertThrows(LocalAudioFailure::class.java) {
            store.sortFolder(folder.id, LocalAudioSort.TITLE, descending = false, expectedRevision = stale)
        }
        assertEquals(LocalAudioError.STALE_STATE, sort.error)
        assertEquals(current, store.read())
    }

    @Test fun appendPositionOverflowRejectsTheWholeBatch() {
        val target = store.createFolder("尾部")
        val source = store.createFolder("来源")
        store.importAudio(target.id, metadata("old.mp3"), copySource("old"))
        val first = store.importAudio(source.id, metadata("first.mp3"), copySource("first"))
        val second = store.importAudio(source.id, metadata("second.mp3"), copySource("second"))
        store.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE members SET position = ? WHERE folder_id = ?", arrayOf<Any>(Int.MAX_VALUE - 1, target.id))
        }
        store = SqliteLocalAudioStore(context, dbName)
        val before = store.read()
        assertEquals(LocalAudioError.LIMIT_REACHED, assertThrows(LocalAudioFailure::class.java) {
            store.organize(listOf(first.id, second.id), LocalAudioBatchAction.ADD, target.id,
                expectedRevision = before.revision)
        }.error)
        assertEquals(before, store.read())
        store.organize(listOf(first.id), LocalAudioBatchAction.ADD, target.id, expectedRevision = before.revision)
        val fullTail = store.read()
        assertEquals(Int.MAX_VALUE, fullTail.members.first { it.folderId == target.id && it.audioId == first.id }.position)
        assertEquals(LocalAudioError.LIMIT_REACHED, assertThrows(LocalAudioFailure::class.java) {
            store.organize(listOf(second.id), LocalAudioBatchAction.ADD, target.id, expectedRevision = fullTail.revision)
        }.error)
        assertEquals(fullTail, store.read())
    }

    @Test fun batchOfFiveHundredIsAcceptedAndFiveHundredOneIsNot() {
        val target = store.createFolder("目标")
        val source = store.createFolder("来源")
        val ids = (1..LocalAudioLimits.MAX_BATCH).map { index ->
            store.importAudio(source.id, metadata("f$index.mp3", title = "曲$index"), copySource("f$index")).id
        }
        val revision = store.read().revision

        val result = store.organize(ids, LocalAudioBatchAction.ADD, target.id, expectedRevision = revision)

        assertEquals(LocalAudioBatchResult(LocalAudioBatchAction.ADD, LocalAudioLimits.MAX_BATCH, 500, 0), result)
        val after = store.read()
        assertEquals(ids, after.members.filter { it.folderId == target.id }.sortedBy { it.position }.map { it.audioId })

        val tooMany = assertThrows(LocalAudioFailure::class.java) {
            store.organize(
                List(LocalAudioLimits.MAX_BATCH + 1) { LocalAudioLimits.newId() },
                LocalAudioBatchAction.ADD, target.id, expectedRevision = after.revision,
            )
        }
        assertEquals(LocalAudioError.LIMIT_REACHED, tooMany.error)
        assertEquals(after.revision, store.read().revision)
    }

    @Test fun cancellationMidwayRollsBackTheWholeBatch() {
        val probe = store.createFolder("探针")
        val target = store.createFolder("目标")
        val source = store.createFolder("来源")
        val ids = (1..6).map { index ->
            store.importAudio(source.id, metadata("c$index.mp3"), copySource("c$index")).id
        }
        // Calibrate on an identical batch first, so the test cancels on the very last check of the
        // real batch: every membership is already written and only the revision is still pending.
        var checks = 0
        store.organize(ids, LocalAudioBatchAction.ADD, probe.id, expectedRevision = store.read().revision) {
            checks++
        }
        assertTrue(checks > ids.size)
        val before = store.read()
        var calls = 0

        val cancelled = assertThrows(CancellationException::class.java) {
            store.organize(ids, LocalAudioBatchAction.ADD, target.id, expectedRevision = before.revision) {
                if (++calls >= checks) throw CancellationException("cancelled")
            }
        }
        assertEquals("cancelled", cancelled.message)
        // Every appended membership was rolled back together with the revision.
        assertEquals(before, store.read())

        // An early cancel, before the first row is touched, is rejected exactly the same way.
        assertThrows(CancellationException::class.java) {
            store.organize(ids, LocalAudioBatchAction.ADD, target.id, expectedRevision = before.revision) {
                throw CancellationException("early")
            }
        }
        assertEquals(before, store.read())
    }

    @Test fun moveIntoFullFolderUsesFreedCapacityAndAddBeyondCapFails() {
        val target = store.createFolder("满")
        val source = store.createFolder("来源")
        val extra = store.createFolder("额外")
        val moved = store.importAudio(source.id, metadata("m.mp3"), copySource("m"))
        val spare = store.importAudio(extra.id, metadata("s.mp3"), copySource("s"))
        val beforeMove = store.read().revision
        val movedId = moved.id
        val spareId = spare.id
        val sourceId = source.id
        val targetId = target.id

        // Fill to MAX_MEMBERS by sharing <= MAX_ITEMS audio across several folders.
        store.close()
        bulkFill(beforeMove, targetId, LocalAudioLimits.MAX_MEMBERS - 2)
        store = SqliteLocalAudioStore(context, dbName)
        val cap = LocalAudioLimits.MAX_MEMBERS.toLong()
        assertEquals(cap, memberCount())
        assertEquals(beforeMove, revision())

        // The target is one short of full and the library is at the cap: the move still fits,
        // because it frees its own source membership first.
        val moved1 = store.organize(
            listOf(movedId), LocalAudioBatchAction.MOVE, sourceId, targetId, beforeMove,
        )
        assertEquals(LocalAudioBatchResult(LocalAudioBatchAction.MOVE, 1, 1, 1), moved1)
        assertEquals(cap, memberCount())
        assertEquals(beforeMove + 1, revision())

        // Adding one more membership at the cap is rejected and leaves the cap untouched.
        val tooFull = assertThrows(LocalAudioFailure::class.java) {
            store.organize(listOf(spareId), LocalAudioBatchAction.ADD, targetId,
                expectedRevision = beforeMove + 1)
        }
        assertEquals(LocalAudioError.LIMIT_REACHED, tooFull.error)
        assertEquals(beforeMove + 1, revision())
        assertEquals(cap, memberCount())
    }

    @Test fun sortFolderReordersWholeFolderAndKeepsUserData() {
        val folder = store.createFolder("组")
        val other = store.createFolder("另一组")
        val third = store.importAudio(folder.id, metadata("c.mp3", title = "C"), copySource("c"))
        val first = store.importAudio(folder.id, metadata("a.mp3", title = "A"), copySource("a"))
        val second = store.importAudio(folder.id, metadata("b.mp3", title = "B"), copySource("b"))
        val elsewhere = store.importAudio(other.id, metadata("z.mp3", title = "Z"), copySource("z"))
        store.saveProgress(folder.id, progress(second.id, 5_000L))
        val bookmark = store.addBookmark(first.id, 2_000L, "书签")
        val revision = store.read().revision

        val result = store.sortFolder(folder.id, LocalAudioSort.TITLE, descending = false,
            expectedRevision = revision)

        assertEquals(LocalAudioSortResult(folder.id, 3, true), result)
        val after = store.read()
        assertEquals(
            listOf(first.id, second.id, third.id),
            after.members.filter { it.folderId == folder.id }.sortedBy { it.position }.map { it.audioId },
        )
        assertEquals(
            listOf(0, 1, 2),
            after.members.filter { it.folderId == folder.id }.sortedBy { it.position }.map { it.position },
        )
        // Only this folder moves; the other folder keeps its own order and the user data survives.
        assertEquals(elsewhere.id, after.members.single { it.folderId == other.id }.audioId)
        assertEquals(5_000L, after.progress.single().positionMs)
        assertEquals(listOf(bookmark), after.bookmarks)
        assertEquals(revision + 1, after.revision)

        // Asking for the order the folder already has changes nothing, not even the revision.
        val repeated = store.sortFolder(folder.id, LocalAudioSort.TITLE, descending = false,
            expectedRevision = after.revision)
        assertEquals(LocalAudioSortResult(folder.id, 3, false), repeated)
        assertEquals(after, store.read())

        // Descending is a real reorder, and manual order is refused instead of being reversed.
        val descending = store.sortFolder(folder.id, LocalAudioSort.TITLE, descending = true,
            expectedRevision = after.revision)
        assertTrue(descending.changed)
        assertEquals(
            listOf(third.id, second.id, first.id),
            store.read().members.filter { it.folderId == folder.id }.sortedBy { it.position }.map { it.audioId },
        )
        assertEquals(
            LocalAudioError.INVALID_ORDER,
            assertThrows(LocalAudioFailure::class.java) {
                store.sortFolder(folder.id, LocalAudioSort.MANUAL, descending = false,
                    expectedRevision = store.read().revision)
            }.error,
        )
        assertEquals(
            LocalAudioError.NOT_FOUND,
            assertThrows(LocalAudioFailure::class.java) {
                store.sortFolder(LocalAudioLimits.newId(), LocalAudioSort.TITLE, descending = false,
                    expectedRevision = store.read().revision)
            }.error,
        )
    }

    @Test fun sortFolderByDurationAndProgressKeepsUnknownLast() {
        val folder = store.createFolder("组")
        val long = store.importAudio(folder.id, metadata("l.mp3", title = "长", durationMs = 900_000L), copySource("l"))
        val unknown = store.importAudio(folder.id, metadata("u.mp3", title = "未知", durationMs = null), copySource("u"))
        val short = store.importAudio(folder.id, metadata("s.mp3", title = "短", durationMs = 1_000L), copySource("s"))
        val revision = store.read().revision

        assertTrue(store.sortFolder(folder.id, LocalAudioSort.DURATION, descending = false,
            expectedRevision = revision).changed)
        assertEquals(
            listOf(short.id, long.id, unknown.id),
            store.read().members.filter { it.folderId == folder.id }.sortedBy { it.position }.map { it.audioId },
        )

        store.saveProgress(folder.id, progress(unknown.id, 30_000L))
        val beforeProgress = store.read().revision
        assertTrue(store.sortFolder(folder.id, LocalAudioSort.LAST_PROGRESS, descending = true,
            expectedRevision = beforeProgress).changed)
        // Known progress leads; unknown tracks retain the current folder order.
        assertEquals(
            listOf(unknown.id, short.id, long.id),
            store.read().members.filter { it.folderId == folder.id }.sortedBy { it.position }.map { it.audioId },
        )
        assertEquals(30_000L, store.read().progress.single().positionMs)
        assertEquals(3, store.read().items.size)
    }

    private fun memberCount(): Long = raw { db ->
        db.rawQuery("SELECT COUNT(*) FROM members", null).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
    }

    private fun revision(): Long = raw { db ->
        db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf("revision")).use { c ->
            if (c.moveToFirst()) c.getString(0).toLong() else -1L
        }
    }

    @Test fun removingADirectoryMemberPreservesItsOriginAndManualDecisionOnRefresh() {
        val folder = store.createFolder("目录")
        val source = referenceSource("doc-1")
        val link = store.linkDirectory(folder.id, source.grantUri!!, "来源", LocalImportMode.REFERENCE, false)
        val origin = LocalAudioDirectoryImport(link.id, "doc-1", 1_024L, null)
        val item = store.importAudio(folder.id, metadata("ref.mp3"), source, origin)
        val before = store.read()
        val entry = store.directoryEntry(link.id, "doc-1")!!
        val grants = store.retainedGrantUris()

        store.organize(listOf(item.id), LocalAudioBatchAction.REMOVE, folder.id, expectedRevision = before.revision)

        val retained = store.directoryEntry(link.id, "doc-1")!!
        assertEquals(entry.audioId, retained.audioId)
        assertEquals(entry.origin.documentIdentity, retained.origin.documentIdentity)
        assertEquals(entry.origin.sizeBytes, retained.origin.sizeBytes)
        assertEquals(entry.origin.modifiedAtMs, retained.origin.modifiedAtMs)
        assertEquals(grants, store.retainedGrantUris())
        assertEquals(0, store.finishDirectoryRefresh(link.id, setOf("doc-1"), 10))
        assertEquals(item.id, store.importAudio(folder.id, metadata("ref.mp3"), source, origin).id)
        assertTrue(store.read().members.isEmpty())
        assertEquals(before.items, store.read().items)
    }

    @Test fun cancellationAfterSortingWritesRollsBackPositionsAndRevision() {
        val folder = store.createFolder("顺序")
        val ids = listOf("c", "b", "a").map {
            store.importAudio(folder.id, metadata("$it.mp3", title = it), copySource(it)).id
        }
        var checks = 0
        store.sortFolder(folder.id, LocalAudioSort.TITLE, false, store.read().revision) { checks++ }
        store.reorder(folder.id, ids)
        val before = store.read()
        var calls = 0
        assertThrows(CancellationException::class.java) {
            store.sortFolder(folder.id, LocalAudioSort.TITLE, false, before.revision) {
                if (++calls == checks) throw CancellationException("cancel after writes")
            }
        }
        assertEquals(checks, calls)
        assertEquals(before, store.read())
    }

    /**
     * Fills several folders through recursive CTE statements. Writing this many rows through
     * [SqliteLocalAudioStore] would cost thousands of transactions, so the capacity boundary is
     * prepared directly in the database instead.
     */
    private fun bulkFill(revision: Long, folderId: String, members: Int) {
        val file = context.getDatabasePath(dbName)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            val sharedAudio = LocalAudioLimits.MAX_ITEMS - 2 // two real audio rows already exist
            db.execSQL(
                "WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < ?) " +
                    "INSERT INTO audio (id, mode, identity, location, file_name, availability, added_at_ms) " +
                    "SELECT printf('%08x-0000-4000-8000-%012x', n, n), 'COPY', printf('bulk-%d', n), " +
                    "printf('%064x', n), 'bulk.mp3', 'AVAILABLE', n FROM seq",
                arrayOf(sharedAudio),
            )
            repeat(5) { group ->
                db.execSQL("INSERT INTO folders (id, name, continue_playback, created_at_ms) VALUES (?, '共享', 1, 0)",
                    arrayOf("eeeeeeee-0000-4000-8000-${(group + 1).toString().padStart(12, '0')}"))
            }
            db.execSQL(
                "WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < ?) " +
                    "INSERT INTO members (folder_id, audio_id, position) " +
                    "SELECT CASE WHEN n <= ? THEN ? ELSE printf('eeeeeeee-0000-4000-8000-%012d', (n - 1) / ?) END, " +
                    "printf('%08x-0000-4000-8000-%012x', (n - 1) % ? + 1, (n - 1) % ? + 1), (n - 1) % ? FROM seq",
                arrayOf<Any>(members, sharedAudio, folderId, sharedAudio, sharedAudio, sharedAudio, sharedAudio),
            )
            // The filler is a raw insert, so restore the revision the caller last observed.
            db.execSQL("UPDATE meta SET value = ? WHERE key = 'revision'", arrayOf(revision.toString()))
        }
    }

    /** Reads the live database through a second connection while the store keeps its own open. */
    private fun <T> raw(block: (SQLiteDatabase) -> T): T {
        val file = context.getDatabasePath(dbName)
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use(block)
    }

    private fun metadata(
        fileName: String,
        title: String? = "标题",
        durationMs: Long? = 60_000L,
    ) = LocalAudioMetadata(fileName, title, null, durationMs, 1_024L)

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
