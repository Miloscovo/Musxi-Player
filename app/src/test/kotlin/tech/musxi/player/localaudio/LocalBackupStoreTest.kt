package tech.musxi.player.localaudio

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest
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
import tech.musxi.core.localaudio.LocalAudioBookmark
import tech.musxi.core.localaudio.LocalAudioError
import tech.musxi.core.localaudio.LocalAudioFailure
import tech.musxi.core.localaudio.LocalAudioFolder
import tech.musxi.core.localaudio.LocalAudioFolderCursor
import tech.musxi.core.localaudio.LocalAudioItem
import tech.musxi.core.localaudio.LocalAudioLimits
import tech.musxi.core.localaudio.LocalAudioMember
import tech.musxi.core.localaudio.LocalAudioMetadata
import tech.musxi.core.localaudio.LocalAudioProgress
import tech.musxi.core.localaudio.LocalAudioSource
import tech.musxi.core.localaudio.LocalImportMode
import tech.musxi.core.localaudio.StoredLocalAudio

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalBackupStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var dbName: String
    private lateinit var store: SqliteLocalAudioStore
    private lateinit var folder: LocalAudioFolder

    @Before fun setUp() {
        dbName = "bk-${LocalAudioLimits.newId().take(8)}.db"
        store = SqliteLocalAudioStore(context, dbName)
        folder = store.createFolder("当前组")
    }

    @After fun tearDown() {
        store.close()
        context.deleteDatabase(dbName)
    }

    @Test fun cancellationInsideRestoreRollsBackEarlierInserts() {
        val backup = backup(items = emptyList(), folders = listOf(
            LocalAudioFolder(newId(), "恢复一"), LocalAudioFolder(newId(), "恢复二")),
            members = emptyList(), progress = emptyList(), cursors = emptyList(), bookmarks = emptyList())
        val before = store.read()
        var checks = 0
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            store.restoreOrganization(backup, before.revision) {
                if (++checks == 4) throw java.util.concurrent.CancellationException()
            }
        }
        assertEquals(before, store.read())
    }

    @Test fun mergeFillsOnlyMissingPartsAndKeepsCurrentOrganization() {
        val current = store.importAudio(folder.id, metadata("a.mp3"), copy("content-a"))
        store.renameAudio(current.id, "本机改名")
        store.saveProgress(folder.id, progress(current.id, 42_000L))
        store.addBookmark(current.id, 1_000L, "本机书签")
        val backup = backup(
            items = listOf(item(BACKUP_AUDIO, LocalImportMode.COPY, contentKey("content-a"), "备份名")),
            folders = listOf(LocalAudioFolder(folder.id, "备份分组名")),
            members = listOf(LocalAudioMember(folder.id, BACKUP_AUDIO, 0)),
            progress = listOf(progress(BACKUP_AUDIO, 90_000L)),
            cursors = listOf(LocalAudioFolderCursor(folder.id, BACKUP_AUDIO)),
            bookmarks = listOf(LocalAudioBookmark(newId(), BACKUP_AUDIO, 5_000L, "备份书签")),
        )
        val before = store.read()

        val preview = store.previewOrganization(backup)

        assertEquals(backup.id, preview.backupId)
        assertEquals(before.revision, preview.expectedRevision)
        assertEquals(1, preview.matchedItems)
        assertEquals(0, preview.unmatchedItems)
        assertEquals(1, preview.folders)
        assertEquals(1, preview.bookmarks)
        // Preview is read-only.
        assertEquals(before, store.read())

        val result = store.restoreOrganization(backup, before.revision)

        assertEquals(0, result.addedFolders)
        assertEquals(0, result.addedMemberships)
        assertEquals(1, result.addedBookmarks)
        val state = store.read()
        assertEquals("当前组", state.folders.single().name)
        assertEquals("本机改名", state.items.single().displayName)
        assertEquals(42_000L, state.progress.single().positionMs)
        assertEquals(setOf("本机书签", "备份书签"), state.bookmarks.map { it.name }.toSet())
        // The folder already had a cursor, so the backed-up one never replaces it.
        assertEquals(1, state.members.size)
        assertEquals(1, state.cursors.size)
        assertEquals(current.id, state.cursors.single().audioId)
    }

    @Test fun unmatchedAudioIsSkippedWhileItsEmptyFolderSurvives() {
        val current = store.importAudio(folder.id, metadata("a.mp3"), copy("content-a"))
        val linked = LocalAudioFolder(newId(), "有源分组")
        val empty = LocalAudioFolder(newId(), "备份空分组")
        val backup = backup(
            items = listOf(
                item(BACKUP_AUDIO, LocalImportMode.COPY, contentKey("content-a"), null),
                item(GONE_AUDIO, LocalImportMode.COPY, contentKey("content-gone"), null),
            ),
            folders = listOf(linked, empty),
            members = listOf(
                LocalAudioMember(linked.id, BACKUP_AUDIO, 0),
                LocalAudioMember(linked.id, GONE_AUDIO, 1),
            ),
            progress = listOf(progress(GONE_AUDIO, 5_000L), progress(BACKUP_AUDIO, 6_000L)),
            cursors = emptyList(),
            bookmarks = listOf(
                LocalAudioBookmark(newId(), GONE_AUDIO, 1_000L, "无源书签"),
                LocalAudioBookmark(newId(), BACKUP_AUDIO, 2_000L, "有源书签"),
            ),
        )

        val result = store.restoreOrganization(backup, store.read().revision)

        assertEquals(1, result.matchedItems)
        assertEquals(1, result.unmatchedItems)
        assertEquals(2, result.addedFolders)
        assertEquals(1, result.addedMemberships)
        assertEquals(1, result.addedBookmarks)
        val state = store.read()
        assertEquals(3, state.folders.size) // the current group plus both backup folders
        assertEquals(listOf(current.id), state.members.filter { it.folderId == linked.id }.map { it.audioId })
        assertTrue(state.members.none { it.folderId == empty.id })
        assertEquals(6_000L, state.progress.single().positionMs)
        assertEquals(listOf("有源书签"), state.bookmarks.map { it.name })
    }

    @Test fun copyMatchesByContentKeyInsteadOfTheBackupAudioId() {
        val current = store.importAudio(folder.id, metadata("a.mp3"), copy("content-a"))
        assertNull(current.displayName)
        val target = LocalAudioFolder(newId(), "恢复分组")
        val backup = backup(
            items = listOf(item(BACKUP_AUDIO, LocalImportMode.COPY, contentKey("content-a"), "备份名")),
            folders = listOf(target),
            members = listOf(LocalAudioMember(target.id, BACKUP_AUDIO, 0)),
            progress = listOf(progress(BACKUP_AUDIO, 12_000L)),
            cursors = listOf(LocalAudioFolderCursor(target.id, BACKUP_AUDIO)),
            bookmarks = emptyList(),
        )

        val preview = store.previewOrganization(backup)
        val result = store.restoreOrganization(backup, store.read().revision)

        assertEquals(1, preview.matchedItems)
        assertEquals(1, result.addedFolders)
        assertEquals(1, result.addedMemberships)
        val state = store.read()
        // One audio row, matched through the source key, now carrying the backup alias.
        assertEquals(1, state.items.size)
        assertEquals(current.id, state.items.single().id)
        assertEquals("备份名", state.items.single().displayName)
        assertEquals(listOf(current.id), state.members.filter { it.folderId == target.id }.map { it.audioId })
        assertEquals(12_000L, state.progress.single().positionMs)
        assertEquals(current.id, state.cursors.single { it.folderId == target.id }.audioId)
    }

    @Test fun repeatingTheSameRestoreIsIdempotentAndKeepsTheRevision() {
        val current = store.importAudio(folder.id, metadata("a.mp3"), copy("content-a"))
        val target = LocalAudioFolder(newId(), "恢复分组")
        val backup = backup(
            items = listOf(item(BACKUP_AUDIO, LocalImportMode.COPY, contentKey("content-a"), "备份名")),
            folders = listOf(target),
            members = listOf(LocalAudioMember(target.id, BACKUP_AUDIO, 0)),
            progress = listOf(progress(BACKUP_AUDIO, 7_000L)),
            cursors = listOf(LocalAudioFolderCursor(target.id, BACKUP_AUDIO)),
            bookmarks = listOf(LocalAudioBookmark(newId(), BACKUP_AUDIO, 3_000L, "书签")),
        )

        val first = store.restoreOrganization(backup, store.read().revision)
        val afterFirst = store.read()
        val second = store.restoreOrganization(backup, afterFirst.revision)
        val afterSecond = store.read()

        assertEquals(1, first.addedFolders)
        assertEquals(1, first.addedMemberships)
        assertEquals(1, first.addedBookmarks)
        assertEquals(0, second.addedFolders)
        assertEquals(0, second.addedMemberships)
        assertEquals(0, second.addedBookmarks)
        assertEquals(afterFirst.revision, afterSecond.revision)
        assertEquals(afterFirst.copy(revision = 0), afterSecond.copy(revision = 0))
        assertEquals(1, afterSecond.progress.size)
        assertEquals(1, afterSecond.bookmarks.size)
        assertEquals("备份名", afterSecond.items.single { it.id == current.id }.displayName)
    }

    @Test fun staleExpectedRevisionIsRejectedWithoutWriting() {
        val before = store.read()
        val backup = backup(
            items = emptyList(),
            folders = listOf(LocalAudioFolder(newId(), "未写入分组")),
            members = emptyList(), progress = emptyList(), cursors = emptyList(), bookmarks = emptyList(),
        )

        val failure = assertThrows(LocalAudioFailure::class.java) {
            store.restoreOrganization(backup, before.revision - 1)
        }

        assertEquals(LocalAudioError.STALE_STATE, failure.error)
        assertEquals(before, store.read())
    }

    @Test fun exceedingTheFolderLimitRollsBackEveryChange() {
        // Pre-fill one below the limit through the schema itself, then ask for two more folders.
        store.close()
        val file = context.getDatabasePath(dbName)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransaction()
            repeat(LocalAudioLimits.MAX_FOLDERS - 2) {
                db.insertOrThrow("folders", null, ContentValues().apply {
                    put("id", newId()); put("name", "占位"); put("continue_playback", 1); put("created_at_ms", 1L)
                })
            }
            db.setTransactionSuccessful()
            db.endTransaction()
        }
        store = SqliteLocalAudioStore(context, dbName)
        assertEquals(LocalAudioLimits.MAX_FOLDERS - 1, store.read().folders.size)
        val revisionBefore = store.read().revision
        val backup = backup(
            items = emptyList(),
            folders = listOf(LocalAudioFolder(newId(), "甲"), LocalAudioFolder(newId(), "乙")),
            members = emptyList(), progress = emptyList(), cursors = emptyList(), bookmarks = emptyList(),
        )

        val failure = assertThrows(LocalAudioFailure::class.java) {
            store.restoreOrganization(backup, revisionBefore)
        }

        assertEquals(LocalAudioError.LIMIT_REACHED, failure.error)
        // The first insert is rolled back with the rest of the transaction.
        assertEquals(LocalAudioLimits.MAX_FOLDERS - 1, store.read().folders.size)
        assertEquals(revisionBefore, store.read().revision)
    }

    @Test fun referenceMatchesOnlyWhenIdentitySizeAndModifiedTimeAllAgree() {
        store.importAudio(folder.id, metadata("a.mp3", sizeBytes = 1_024L, modifiedAtMs = 5_000L),
            reference("doc-a"))
        val sameKey = backup(
            items = listOf(item(BACKUP_AUDIO, LocalImportMode.REFERENCE,
                referenceKey("doc-a", 1_024L, 5_000L), null)),
            folders = listOf(LocalAudioFolder(folder.id, "组")),
            members = listOf(LocalAudioMember(folder.id, BACKUP_AUDIO, 0)),
            progress = emptyList(), cursors = emptyList(), bookmarks = emptyList(),
        )
        // Same document identity and size, different modification time: another version.
        val otherTime = backup(
            items = listOf(item(BACKUP_AUDIO, LocalImportMode.REFERENCE,
                referenceKey("doc-a", 1_024L, 9_000L), null)),
            folders = listOf(LocalAudioFolder(folder.id, "组")),
            members = listOf(LocalAudioMember(folder.id, BACKUP_AUDIO, 0)),
            progress = emptyList(), cursors = emptyList(), bookmarks = emptyList(),
        )

        assertEquals(1, store.previewOrganization(sameKey).matchedItems)
        assertEquals(0, store.previewOrganization(otherTime).matchedItems)
        assertEquals(1, store.previewOrganization(otherTime).unmatchedItems)

        val before = store.read()
        store.restoreOrganization(otherTime, before.revision)
        assertEquals(before.copy(revision = 0), store.read().copy(revision = 0))
        assertEquals(1, store.read().items.size)
    }

    @Test fun restoreLeavesAudioSourcesGrantsAndDirectoriesUntouched() {
        val current = store.importAudio(folder.id, metadata("a.mp3"), copy("content-a"))
        val link = store.linkDirectory(folder.id, "content://bk.test/tree", "目录", LocalImportMode.COPY, false)
        val sourceBefore = store.source(current.id)
        val grantsBefore = store.retainedGrantUris()
        val copiesBefore = store.retainedCopyNames()
        val target = LocalAudioFolder(newId(), "外部分组")
        val backup = backup(
            items = listOf(item(BACKUP_AUDIO, LocalImportMode.COPY, contentKey("content-elsewhere"), "外部")),
            folders = listOf(target),
            members = listOf(LocalAudioMember(target.id, BACKUP_AUDIO, 0)),
            progress = listOf(progress(BACKUP_AUDIO, 1_000L)),
            cursors = listOf(LocalAudioFolderCursor(target.id, BACKUP_AUDIO)),
            bookmarks = listOf(LocalAudioBookmark(newId(), BACKUP_AUDIO, 1_000L, "书签")),
        )

        val result = store.restoreOrganization(backup, store.read().revision)

        assertEquals(0, result.matchedItems)
        assertEquals(1, result.addedFolders)
        assertEquals(0, result.addedMemberships)
        assertEquals(0, result.addedBookmarks)
        val sourceAfter = store.source(current.id)!!
        assertEquals(sourceBefore!!.item, sourceAfter.item)
        assertEquals(sourceBefore.source.mode, sourceAfter.source.mode)
        assertEquals(sourceBefore.source.location, sourceAfter.source.location)
        assertEquals(sourceBefore.source.identity, sourceAfter.source.identity)
        assertEquals(sourceBefore.source.grantUri, sourceAfter.source.grantUri)
        assertEquals(grantsBefore, store.retainedGrantUris())
        assertEquals(copiesBefore, store.retainedCopyNames())
        assertEquals(link, store.directory(link.id)?.link)
        assertTrue(store.read().progress.isEmpty())
        assertTrue(store.read().bookmarks.isEmpty())
        assertTrue(store.read().cursors.isEmpty())
    }

    private fun item(audioId: String, mode: LocalImportMode, sourceKey: String, alias: String?) =
        LocalAudioBackupItem(
            LocalAudioItem(
                id = audioId,
                metadata = LocalAudioMetadata("a.mp3", null, null, 60_000L, 1_024L, null),
                mode = mode,
                displayName = alias,
                addedAtMs = 1L,
            ),
            sourceKey,
        )

    /** COPY key is the managed content file name; the backup never carries a URI. */
    private fun contentKey(identity: String): String = sha256(identity)

    private fun referenceKey(documentIdentity: String, sizeBytes: Long?, modifiedAtMs: Long?): String =
        LocalAudioBackupKeys.source(StoredLocalAudio(
            LocalAudioItem(
                id = BACKUP_AUDIO,
                metadata = LocalAudioMetadata("a.mp3", null, null, 60_000L, sizeBytes, modifiedAtMs),
                mode = LocalImportMode.REFERENCE,
                addedAtMs = 1L,
            ),
            reference(documentIdentity),
        ))

    private fun backup(
        items: List<LocalAudioBackupItem>,
        folders: List<LocalAudioFolder>,
        members: List<LocalAudioMember>,
        progress: List<LocalAudioProgress>,
        cursors: List<LocalAudioFolderCursor>,
        bookmarks: List<LocalAudioBookmark>,
    ) = LocalAudioBackup(
        id = newId(),
        createdAtMs = 1_600_000_000_000L,
        items = items,
        folders = folders,
        members = members,
        progress = progress,
        cursors = cursors,
        bookmarks = bookmarks,
    )

    private fun metadata(
        fileName: String,
        sizeBytes: Long? = 1_024L,
        modifiedAtMs: Long? = null,
    ) = LocalAudioMetadata(fileName, "标题", null, 60_000L, sizeBytes, modifiedAtMs)

    private fun copy(identity: String) =
        LocalAudioSource(LocalImportMode.COPY, sha256(identity), identity)

    private fun reference(documentId: String) =
        LocalAudioSource(LocalImportMode.REFERENCE, "content://bk.test/document/$documentId",
            documentId, "content://bk.test/tree")

    private fun progress(audioId: String, positionMs: Long) =
        LocalAudioProgress(audioId, positionMs, completed = false, updatedAtMs = 1_700_000_000_000L)

    private fun newId() = LocalAudioLimits.newId()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
            "%02x".format(it.toInt() and 0xFF)
        }

    private companion object {
        const val BACKUP_AUDIO = "11111111-1111-1111-1111-111111111111"
        const val GONE_AUDIO = "22222222-2222-2222-2222-222222222222"
    }
}
