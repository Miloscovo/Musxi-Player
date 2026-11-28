package tech.musxi.player.localaudio

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.musxi.core.localaudio.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalAudioVersionStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var path: String
    private lateinit var store: SqliteLocalAudioStore
    @Before fun setup() {
        path = File(temporary.newFolder("database"), "versions.db").absolutePath
        store = SqliteLocalAudioStore(context, path)
    }
    @After fun close() { if (::store.isInitialized) store.close() }

    @Test fun checkpointOnlyAdvancesProgressAndLegacyAndSurvivesReopen() {
        val folder = store.createFolder("音频")
        val item = item(folder.id, "a")
        val before = store.read()
        store.saveProgress(folder.id, LocalAudioProgress(item.id, 500, false, 1))
        val after = store.read()
        assertEquals(before.organizationRevision, after.organizationRevision)
        assertEquals(before.progressRevision + 1, after.progressRevision)
        assertEquals(before.revision + 1, after.revision)
        assertTrue(before.organizationToken.matches(after))
        assertFalse(before.fullToken.matches(after))
        store.close(); store = SqliteLocalAudioStore(context, path)
        assertEquals(after, store.read())
        store.renameAudio(item.id, "别名")
        assertFalse(after.organizationToken.matches(store.read()))
    }

    @Test fun versionedCommandsIgnoreCheckpointsButLegacyAndRealConflictsRemainStrict() {
        val folder = store.createFolder("音频")
        val other = store.createFolder("其他")
        val item = item(folder.id, "a")
        val before = store.read()
        store.saveProgress(folder.id, LocalAudioProgress(item.id, 500, false, 1))
        assertEquals(LocalAudioError.STALE_STATE, assertThrows(LocalAudioFailure::class.java) {
            store.organize(listOf(item.id), LocalAudioBatchAction.ADD, other.id, expectedRevision = before.revision)
        }.error)
        store.organize(listOf(item.id), LocalAudioBatchAction.ADD, other.id, expectedVersion = before.organizationToken)
        val after = store.read()
        assertEquals(LocalAudioError.STALE_STATE, assertThrows(LocalAudioFailure::class.java) {
            store.reorder(folder.id, listOf(item.id), before.organizationToken)
        }.error)
        assertEquals(after, store.read())
    }

    @Test fun progressSortRequiresBothVersionsAndCancellationRollsBackCounters() {
        val folder = store.createFolder("音频")
        val a = item(folder.id, "a"); val b = item(folder.id, "b")
        val before = store.read()
        assertEquals(LocalAudioError.INVALID_TARGET, assertThrows(LocalAudioFailure::class.java) {
            store.sortFolder(folder.id, LocalAudioSort.LAST_PROGRESS, false, before.organizationToken)
        }.error)
        store.saveProgress(folder.id, LocalAudioProgress(b.id, 500, false, 1))
        assertEquals(LocalAudioError.STALE_STATE, assertThrows(LocalAudioFailure::class.java) {
            store.sortFolder(folder.id, LocalAudioSort.LAST_PROGRESS, false, before.fullToken)
        }.error)
        val checkpoint = store.read()
        assertThrows(CancellationException::class.java) {
            store.sortFolder(folder.id, LocalAudioSort.TITLE, true, checkpoint.organizationToken) {
                throw CancellationException()
            }
        }
        assertEquals(checkpoint, store.read())
        store.sortFolder(folder.id, LocalAudioSort.LAST_PROGRESS, false, checkpoint.fullToken)
        assertEquals(listOf(b.id, a.id), store.read().members.sortedBy { it.position }.map { it.audioId })
    }

    @Test fun schemaFiveMigrationSeedsVersionsAndPreservesAllPublicRowsAndSources() {
        val folder = store.createFolder("音频")
        val item = item(folder.id, "a")
        store.saveProgress(folder.id, LocalAudioProgress(item.id, 900, false, 7))
        store.addBookmark(item.id, 700, "书签")
        val before = store.read(); val source = store.source(item.id)
        store.close()
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.delete("meta", "key IN (?, ?)", arrayOf("organization_revision", "progress_revision"))
            db.execSQL("ALTER TABLE directories DROP COLUMN last_refresh_failures")
            db.version = 5
        }
        store = SqliteLocalAudioStore(context, path)
        val migrated = store.read()
        assertEquals(before.copy(organizationRevision = before.revision, progressRevision = before.revision), migrated)
        assertEquals(source!!.item, store.source(item.id)!!.item)
        assertEquals(source.source.location, store.source(item.id)!!.source.location)
        store.saveProgress(folder.id, LocalAudioProgress(item.id, 1200, false, 8))
        assertTrue(migrated.organizationToken.matches(store.read()))
    }

    @Test fun schemaSixAddsDirectoryFailureCountWithoutChangingVersionsOrRows() {
        val folder = store.createFolder("目录")
        item(folder.id, "a")
        store.linkDirectory(folder.id, "content://test/tree/root", "原目录", LocalImportMode.COPY, false)
        val before = store.read()
        store.close()
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ALTER TABLE directories DROP COLUMN last_refresh_failures")
            db.version = 6
        }
        store = SqliteLocalAudioStore(context, path)
        assertEquals(before, store.read())
        assertEquals(0, store.read().directories.single().lastRefreshFailures)
    }

    @Test fun damagedCounterAndOverflowFailWithoutResetOrPartialProgressWrite() {
        val folder = store.createFolder("音频"); val item = item(folder.id, "a")
        val before = store.read()
        store.close()
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE meta SET value = ? WHERE key = 'progress_revision'", arrayOf(Long.MAX_VALUE.toString()))
        }
        store = SqliteLocalAudioStore(context, path)
        val saturated = store.read()
        assertThrows(LocalAudioFailure::class.java) {
            store.saveProgress(folder.id, LocalAudioProgress(item.id, 900, false, 1))
        }
        assertEquals(saturated, store.read())
        store.close()
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DELETE FROM meta WHERE key = 'organization_revision'")
        }
        store = SqliteLocalAudioStore(context, path)
        assertEquals(LocalAudioError.READ_FAILED, assertThrows(LocalAudioFailure::class.java) { store.read() }.error)
        assertEquals(before.items.single(), store.source(item.id)!!.item)
    }

    @Test fun backupMergeUsesFullPreviewAndOnlyAddsMissingProgressAndCursor() {
        val folder = store.createFolder("音频"); val item = item(folder.id, "a")
        val backup = LocalAudioBackup(LocalAudioLimits.newId(), 1,
            listOf(LocalAudioBackupItem(item, "a".repeat(64))), listOf(folder),
            listOf(LocalAudioMember(folder.id, item.id, 0)),
            listOf(LocalAudioProgress(item.id, 500, false, 1)),
            listOf(LocalAudioFolderCursor(folder.id, item.id)), emptyList())
        val preview = store.previewOrganization(backup)
        val before = store.read()
        store.restoreOrganization(backup, preview.expectedVersion!!)
        val after = store.read()
        assertEquals(before.organizationRevision, after.organizationRevision)
        assertEquals(before.progressRevision + 1, after.progressRevision)
        assertEquals(500L, after.progress.single().positionMs)
        store.restoreOrganization(backup, store.previewOrganization(backup).expectedVersion!!)
        assertEquals(after, store.read())
        val stale = store.previewOrganization(backup).expectedVersion!!
        store.saveProgress(folder.id, LocalAudioProgress(item.id, 900, false, 2))
        assertEquals(LocalAudioError.STALE_STATE, assertThrows(LocalAudioFailure::class.java) {
            store.restoreOrganization(backup, stale)
        }.error)
        assertEquals(900L, store.read().progress.single().positionMs)
    }

    @Test fun cueCommitUsesOrganizationVersionAndOtherStoreEditsStillInvalidateIt() {
        val folder = store.createFolder("专辑"); val whole = item(folder.id, "a")
        val version = store.read().organizationToken
        store.saveProgress(folder.id, LocalAudioProgress(whole.id, 500, false, 1))
        val slices = (1..2).map { number ->
            val segment = LocalAudioSegment((number - 1) * 5_000_000L, number * 5_000_000L, number)
            LocalCueImportTrack(LocalAudioMetadata("album.flac", durationMs = segment.durationMs),
                LocalAudioSource(LocalImportMode.COPY, "b".repeat(64), "cue-v1:$number"), segment)
        }
        val imported = store.importCue(folder.id, slices, version)
        assertEquals(2, imported.size)
        val current = store.read()
        SqliteLocalAudioStore(context, path).use { another -> another.renameFolder(folder.id, "新名称") }
        assertEquals(LocalAudioError.STALE_STATE, assertThrows(LocalAudioFailure::class.java) {
            store.reorder(folder.id, current.members.sortedBy { it.position }.map { it.audioId }, current.organizationToken)
        }.error)
        assertEquals(3, store.read().items.size)
    }

    private fun item(folderId: String, seed: String): LocalAudioItem = store.importAudio(folderId,
        LocalAudioMetadata("$seed.mp3", durationMs = 10_000),
        LocalAudioSource(LocalImportMode.COPY, seed.repeat(64), "$seed.mp3"))
}
