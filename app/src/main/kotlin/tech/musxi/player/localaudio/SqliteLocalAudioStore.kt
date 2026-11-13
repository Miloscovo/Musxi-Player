package tech.musxi.player.localaudio

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import tech.musxi.core.localaudio.LocalAudioAvailability
import tech.musxi.core.localaudio.LocalAudioBackup
import tech.musxi.core.localaudio.LocalAudioBackupKeys
import tech.musxi.core.localaudio.LocalAudioBatchAction
import tech.musxi.core.localaudio.LocalAudioBatchResult
import tech.musxi.core.localaudio.LocalAudioBookmark
import tech.musxi.core.localaudio.LocalAudioDirectoryEntry
import tech.musxi.core.localaudio.LocalAudioDirectoryImport
import tech.musxi.core.localaudio.LocalAudioDirectoryLink
import tech.musxi.core.localaudio.LocalAudioError
import tech.musxi.core.localaudio.LocalAudioFailure
import tech.musxi.core.localaudio.LocalAudioFolder
import tech.musxi.core.localaudio.LocalAudioFolderCursor
import tech.musxi.core.localaudio.LocalAudioItem
import tech.musxi.core.localaudio.LocalAudioLibraryState
import tech.musxi.core.localaudio.LocalAudioLimits
import tech.musxi.core.localaudio.LocalAudioMember
import tech.musxi.core.localaudio.LocalAudioMetadata
import tech.musxi.core.localaudio.LocalAudioProgress
import tech.musxi.core.localaudio.LocalAudioRestorePreview
import tech.musxi.core.localaudio.LocalAudioRestoreResult
import tech.musxi.core.localaudio.LocalAudioSearch
import tech.musxi.core.localaudio.LocalAudioSort
import tech.musxi.core.localaudio.LocalAudioSortResult
import tech.musxi.core.localaudio.LocalAudioSource
import tech.musxi.core.localaudio.LocalAudioStore
import tech.musxi.core.localaudio.LocalImportMode
import tech.musxi.core.localaudio.LocalMediaKind
import tech.musxi.core.localaudio.StoredLocalAudioDirectory
import tech.musxi.core.localaudio.StoredLocalAudio

/**
 * SQLite implementation of [LocalAudioStore].
 *
 * Every mutation runs inside one transaction, so membership, progress, cursors and bookmarks can
 * never be left half-written. Foreign keys use `ON DELETE CASCADE`: deleting a folder therefore
 * keeps its audio (it only drops members/cursor), while [removeAudio] drops the dependent rows.
 *
 * The table layout deliberately keeps the document URI out of [read]; only [source] exposes the
 * backend capability.
 */
class SqliteLocalAudioStore(
    context: Context,
    dbName: String? = DB_NAME,
) : LocalAudioStore {

    private val lock = Any()

    private val helper: SQLiteOpenHelper = object : SQLiteOpenHelper(
        context.applicationContext,
        dbName,
        null,
        SCHEMA_VERSION,
        // An empty handler never deletes the file: a damaged library must surface as a read
        // failure instead of being silently recreated as an empty database.
        DatabaseErrorHandler { },
    ) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            SCHEMA.forEach { db.execSQL(it) }
            db.insertOrThrow("meta", null, ContentValues().apply {
                put("key", REVISION_KEY)
                put("value", "0")
            })
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Version 1 is the initial schema, version 2 adds directory associations plus the
            // stored modification time, version 3 adds the media kind and the artwork key, and
            // version 4 adds the per-audio playback speed and the user's own cover.
            // Every statement is additive, so an upgraded library keeps all existing rows; the
            // steps always run in order, so a version-1 library reaches the current schema too.
            // Any other on-disk version must fail loudly instead of serving a library this build
            // cannot read, and must never be recreated.
            if (newVersion != SCHEMA_VERSION || oldVersion !in 1 until SCHEMA_VERSION) {
                throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
            }
            if (oldVersion < 2) MIGRATION_2.forEach { db.execSQL(it) }
            if (oldVersion < 3) MIGRATION_3.forEach { db.execSQL(it) }
            if (oldVersion < 4) MIGRATION_4.forEach { db.execSQL(it) }
        }
    }

    /** A single write transaction. [changed] decides whether the library revision advances. */
    private inner class Tx(val db: SQLiteDatabase) {
        var changed = false

        fun requireFolder(id: String) {
            validateId(id, LocalAudioError.NOT_FOUND)
            if (!exists(db, "folders", id)) throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
        }

        fun requireAudio(id: String) {
            validateId(id, LocalAudioError.NOT_FOUND)
            if (!exists(db, "audio", id)) throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
        }

        fun addMember(folderId: String, audioId: String) {
            if (isMember(db, folderId, audioId)) return
            if (count(db, "members") >= LocalAudioLimits.MAX_MEMBERS) throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
            val position = nextPosition(db, folderId)
            db.insertOrThrow("members", null, ContentValues().apply {
                put("folder_id", folderId)
                put("audio_id", audioId)
                put("position", position)
            })
            changed = true
        }
    }

    override fun read(): LocalAudioLibraryState = query { db -> readSnapshot(db) }

    private fun readSnapshot(db: SQLiteDatabase, error: LocalAudioError = LocalAudioError.READ_FAILED): LocalAudioLibraryState =
        LocalAudioLibraryState(
            ready = true,
            revision = currentRevision(db, error),
            folders = db.rawQuery(
                "SELECT id, name, continue_playback FROM folders ORDER BY created_at_ms, rowid",
                null,
            ).use { c -> buildList { while (c.moveToNext()) add(readFolder(c)) } },
            items = db.rawQuery(
                "SELECT $AUDIO_COLUMNS FROM audio ORDER BY added_at_ms, rowid",
                null,
            ).use { c -> buildList { while (c.moveToNext()) add(readItem(c)) } },
            members = db.rawQuery(
                "SELECT folder_id, audio_id, position FROM members ORDER BY folder_id, position",
                null,
            ).use { c ->
                buildList { while (c.moveToNext()) add(LocalAudioMember(c.getString(0), c.getString(1), c.getInt(2))) }
            },
            progress = db.rawQuery(
                "SELECT audio_id, position_ms, completed, updated_at_ms FROM progress ORDER BY audio_id",
                null,
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(LocalAudioProgress(c.getString(0), c.getLong(1), c.getInt(2) != 0, c.getLong(3)))
                    }
                }
            },
            cursors = db.rawQuery(
                "SELECT folder_id, audio_id FROM cursors ORDER BY folder_id",
                null,
            ).use { c -> buildList { while (c.moveToNext()) add(LocalAudioFolderCursor(c.getString(0), c.getString(1))) } },
            bookmarks = db.rawQuery(
                "SELECT id, audio_id, position_ms, name FROM bookmarks ORDER BY created_at_ms, rowid",
                null,
            ).use { c ->
                buildList {
                    while (c.moveToNext()) add(LocalAudioBookmark(c.getString(0), c.getString(1), c.getLong(2), c.getString(3)))
                }
            },
            // Only the public link is published; tree_uri and provenance identities stay private.
            directories = db.rawQuery("SELECT $DIRECTORY_COLUMNS FROM directories ORDER BY created_at_ms, rowid", null)
                .use { c -> buildList { while (c.moveToNext()) add(readDirectoryLink(c)) } },
        )

    override fun createFolder(name: String): LocalAudioFolder = write {
        validateName(name)
        if (count(db, "folders") >= LocalAudioLimits.MAX_FOLDERS) throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
        val folder = LocalAudioFolder(LocalAudioLimits.newId(), name)
        db.insertOrThrow("folders", null, ContentValues().apply {
            put("id", folder.id)
            put("name", folder.name)
            put("continue_playback", if (folder.continuePlayback) 1 else 0)
            put("created_at_ms", now())
        })
        changed = true
        folder
    }

    override fun renameFolder(id: String, name: String) {
        write {
            validateName(name)
            requireFolder(id)
            db.update("folders", ContentValues().apply { put("name", name) }, "id = ?", arrayOf(id))
            changed = true
        }
    }

    override fun setContinuePlayback(id: String, enabled: Boolean) {
        write {
            requireFolder(id)
            db.update("folders", ContentValues().apply { put("continue_playback", if (enabled) 1 else 0) }, "id = ?", arrayOf(id))
            changed = true
        }
    }

    /** Membership and cursor are cascaded; the imported audio row is untouched. */
    override fun deleteFolder(id: String) {
        write {
            requireFolder(id)
            // The schema cascades too, but deleting the children explicitly keeps the behaviour
            // identical when foreign-key enforcement is unavailable on an older device.
            db.delete("members", "folder_id = ?", arrayOf(id))
            db.delete("cursors", "folder_id = ?", arrayOf(id))
            // Directory associations belong to the folder; their provenance goes with them.
            db.delete("directory_entries", "directory_id IN (SELECT id FROM directories WHERE folder_id = ?)", arrayOf(id))
            db.delete("directories", "folder_id = ?", arrayOf(id))
            if (db.delete("folders", "id = ?", arrayOf(id)) != 1) throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
            changed = true
        }
    }

    override fun importAudio(folderId: String, metadata: LocalAudioMetadata, source: LocalAudioSource,
        directory: LocalAudioDirectoryImport?): LocalAudioItem = write {
        requireFolder(folderId)
        // Reject an unknown, foreign or mismatched association before the import touches the
        // library, so a provenance row can never contradict its directory or the source mode.
        if (directory != null) {
            requireDirectory(db, directory.directoryId)
            val link = readDirectoryLinkById(db, directory.directoryId)
                ?: throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
            if (link.folderId != folderId || link.mode != source.mode) {
                throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
            }
        }
        // The previous provenance of this document decides whether a re-scan may re-add a
        // membership the user removed by hand. Read it inside the same transaction.
        val previousOrigin = directory?.let { savedOrigin(db, it.directoryId, it.documentIdentity) }
        val existing = findAudioId(db, source.mode, source.identity)
        val audioId = if (existing != null) {
            // A known file size that changed means the document now holds different content. The
            // old progress/bookmarks may not be valid for it, so only an explicit relink may move
            // a reference entry to a new version.
            if (source.mode == LocalImportMode.REFERENCE && contentChanged(db, existing, metadata)) {
                throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
            }
            // Re-scan of an already imported source: refresh source metadata, keep the row identity,
            // user display name, progress and bookmarks.
            val values = sourceValues(metadata, source)
            values.put("availability", LocalAudioAvailability.AVAILABLE.name)
            // An observation without a modification time is not evidence that the stored one is
            // gone, so a re-scan never blanks a known value.
            if (metadata.modifiedAtMs == null) values.remove("modified_at_ms")
            // The same source may already own a cover; a scan that extracted none keeps the old
            // artwork instead of dropping it. Only an explicit relink may replace or clear it.
            if (metadata.artworkKey == null) values.remove("artwork_key")
            db.update("audio", values, "id = ?", arrayOf(existing))
            existing
        } else {
            if (count(db, "audio") >= LocalAudioLimits.MAX_ITEMS) throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
            val newId = LocalAudioLimits.newId()
            val values = sourceValues(metadata, source)
            values.put("id", newId)
            values.put("availability", LocalAudioAvailability.AVAILABLE.name)
            values.put("added_at_ms", now())
            db.insertOrThrow("audio", null, values)
            newId
        }
        if (directory == null) {
            addMember(folderId, audioId)
        } else {
            putOrigin(db, directory.directoryId, directory, audioId)
            // A document already known to this directory keeps the user's membership decisions: a
            // COPY whose content changed gets a new audio ID, so the old membership alone decides.
            val keepMembership = previousOrigin == null || isMember(db, folderId, previousOrigin)
            if (keepMembership) addMember(folderId, audioId)
        }
        changed = true
        readItemById(db, audioId) ?: throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
    }

    override fun source(audioId: String): StoredLocalAudio? = query { db ->
        if (!isValidId(audioId)) return@query null
        db.rawQuery("SELECT $AUDIO_COLUMNS FROM audio WHERE id = ?", arrayOf(audioId)).use { c ->
            if (!c.moveToFirst()) return@use null
            StoredLocalAudio(readItem(c), readSource(c))
        }
    }

    override fun renameAudio(audioId: String, name: String?) {
        write {
            requireAudio(audioId)
            if (name != null) validateName(name)
            db.update("audio", ContentValues().apply {
                if (name == null) putNull("display_name") else put("display_name", name)
            }, "id = ?", arrayOf(audioId))
            changed = true
        }
    }

    override fun addToFolder(folderId: String, audioId: String) {
        write {
            requireFolder(folderId)
            requireAudio(audioId)
            addMember(folderId, audioId)
        }
    }

    override fun removeFromFolder(folderId: String, audioId: String) {
        write {
            requireFolder(folderId)
            requireAudio(audioId)
            if (!isMember(db, folderId, audioId)) throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
            db.delete("members", "folder_id = ? AND audio_id = ?", arrayOf(folderId, audioId))
            db.delete("cursors", "folder_id = ? AND audio_id = ?", arrayOf(folderId, audioId))
            changed = true
        }
    }

    override fun reorder(folderId: String, audioIds: List<String>) {
        write {
            requireFolder(folderId)
            val current = memberIds(db, folderId)
            val unique = audioIds.toSet()
            if (unique.size != audioIds.size || unique != current) throw LocalAudioFailure(LocalAudioError.INVALID_ORDER)
            audioIds.forEachIndexed { index, audioId ->
                db.update("members", ContentValues().apply { put("position", index) },
                    "folder_id = ? AND audio_id = ?", arrayOf(folderId, audioId))
            }
            changed = true
        }
    }

    /**
     * Membership-only batch edit. Audio rows, progress, bookmarks, the other memberships, the
     * source identity and the directory provenance all survive, and no stored grant is released:
     * a batch can never lose a file or a listening position.
     */
    override fun organize(audioIds: List<String>, action: LocalAudioBatchAction, folderId: String,
        destinationFolderId: String?, expectedRevision: Long, checkCancelled: () -> Unit): LocalAudioBatchResult {
        checkCancelled()
        // Freeze the caller's list once so a mutable argument cannot change between validation and
        // the write, and reject an impossible batch before it opens a transaction.
        val selected = audioIds.toList()
        if (selected.isEmpty() || selected.size > LocalAudioLimits.MAX_BATCH) {
            throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
        }
        if (selected.any { !isValidId(it) } || selected.toSet().size != selected.size) {
            throw LocalAudioFailure(LocalAudioError.INVALID_ORDER)
        }
        // Which folder an ID refers to is pure argument shape, so it is decided before the write.
        val target = when (action) {
            LocalAudioBatchAction.ADD -> {
                if (destinationFolderId != null) throw LocalAudioFailure(LocalAudioError.INVALID_ORDER)
                folderId
            }
            LocalAudioBatchAction.REMOVE -> {
                if (destinationFolderId != null) throw LocalAudioFailure(LocalAudioError.INVALID_ORDER)
                folderId
            }
            LocalAudioBatchAction.MOVE -> {
                val destination = destinationFolderId ?: throw LocalAudioFailure(LocalAudioError.INVALID_ORDER)
                if (!isValidId(destination) || destination == folderId) {
                    throw LocalAudioFailure(LocalAudioError.INVALID_ORDER)
                }
                destination
            }
        }

        return write {
            checkCancelled()
            if (currentRevision(db, LocalAudioError.STORAGE_FAILED) != expectedRevision) {
                throw LocalAudioFailure(LocalAudioError.STALE_STATE)
            }
            requireFolder(folderId)
            if (action == LocalAudioBatchAction.MOVE) requireFolder(target)
            // Every ID is verified before the first row changes, so a partially known batch never
            // leaves a half-applied edit behind.
            for (audioId in selected) {
                checkCancelled()
                requireAudio(audioId)
            }

            var added = 0
            var removed = 0
            when (action) {
                LocalAudioBatchAction.ADD -> {
                    val pending = pendingMembers(db, target, selected, checkCancelled)
                    requireMemberCapacity(db, 0, pending.size)
                    appendMembers(db, target, pending, checkCancelled)
                    added = pending.size
                    if (added > 0) changed = true
                }
                LocalAudioBatchAction.REMOVE -> {
                    for (audioId in selected) {
                        checkCancelled()
                        if (!isMember(db, folderId, audioId)) throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
                    }
                    for (audioId in selected) {
                        checkCancelled()
                        dropMember(db, folderId, audioId)
                    }
                    removed = selected.size
                    if (removed > 0) changed = true
                }
                LocalAudioBatchAction.MOVE -> {
                    for (audioId in selected) {
                        checkCancelled()
                        if (!isMember(db, folderId, audioId)) throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
                    }
                    // A move frees exactly the memberships it removes, so a folder that is already
                    // full still accepts members it gains from the source folder itself.
                    val pending = pendingMembers(db, target, selected, checkCancelled)
                    requireMemberCapacity(db, -selected.size, pending.size)
                    for (audioId in selected) {
                        checkCancelled()
                        dropMember(db, folderId, audioId)
                    }
                    appendMembers(db, target, pending, checkCancelled)
                    removed = selected.size
                    added = pending.size
                    if (removed > 0 || added > 0) changed = true
                }
            }
            checkCancelled()
            LocalAudioBatchResult(action, selected.size, added, removed)
        }
    }

    /**
     * Reorders one whole folder. The scope is the membership itself, never a filtered search page,
     * so sorting can never hide or re-add a row. A request that already matches the stored order
     * leaves the revision alone.
     */
    override fun sortFolder(folderId: String, sort: LocalAudioSort, descending: Boolean,
        expectedRevision: Long, checkCancelled: () -> Unit): LocalAudioSortResult {
        checkCancelled()
        // Manual order *is* the stored position: there is no separate key to sort by, and silently
        // reversing it would destroy the user's arrangement.
        if (sort == LocalAudioSort.MANUAL) throw LocalAudioFailure(LocalAudioError.INVALID_ORDER)
        return write {
            checkCancelled()
            if (currentRevision(db, LocalAudioError.STORAGE_FAILED) != expectedRevision) {
                throw LocalAudioFailure(LocalAudioError.STALE_STATE)
            }
            requireFolder(folderId)
            val current = mutableListOf<String>()
            db.rawQuery("SELECT audio_id FROM members WHERE folder_id = ? ORDER BY position, audio_id",
                arrayOf(folderId)).use { c ->
                while (c.moveToNext()) {
                    checkCancelled()
                    current.add(c.getString(0))
                }
            }
            // Ordered over the whole folder, with progress read inside the same transaction so a
            // concurrent save cannot rank a row against a stale position.
            val ordered = LocalAudioSearch.ordered(
                readFolderSnapshot(db, folderId, checkCancelled), folderId, sort, descending, checkCancelled,
            ).map { it.id }
            checkCancelled()
            if (ordered.size != current.size || ordered.toSet() != current.toSet()) {
                throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
            }
            if (ordered == current) return@write LocalAudioSortResult(folderId, current.size, false)
            ordered.forEachIndexed { index, audioId ->
                checkCancelled()
                db.update("members", ContentValues().apply { put("position", index) },
                    "folder_id = ? AND audio_id = ?", arrayOf(folderId, audioId))
            }
            changed = true
            checkCancelled()
            LocalAudioSortResult(folderId, ordered.size, true)
        }
    }

    /** Only materialize this folder's metadata; unrelated bookmarks and sources are not needed. */
    private fun readFolderSnapshot(db: SQLiteDatabase, folderId: String, checkCancelled: () -> Unit): LocalAudioLibraryState {
        val args = arrayOf(folderId)
        val folder = db.rawQuery("SELECT id, name, continue_playback FROM folders WHERE id = ?", args).use { c ->
            if (!c.moveToFirst()) throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
            readFolder(c)
        }
        val items = db.rawQuery(
            "SELECT $AUDIO_COLUMNS FROM audio WHERE id IN (SELECT audio_id FROM members WHERE folder_id = ?) " +
                "ORDER BY added_at_ms, rowid", args,
        ).use { c -> buildList {
            while (c.moveToNext()) { checkCancelled(); add(readItem(c)) }
        } }
        val members = db.rawQuery("SELECT audio_id, position FROM members WHERE folder_id = ? ORDER BY position", args)
            .use { c -> buildList {
                while (c.moveToNext()) { checkCancelled(); add(LocalAudioMember(folderId, c.getString(0), c.getInt(1))) }
            } }
        val progress = db.rawQuery(
            "SELECT audio_id, position_ms, completed, updated_at_ms FROM progress " +
                "WHERE audio_id IN (SELECT audio_id FROM members WHERE folder_id = ?)", args,
        ).use { c -> buildList {
            while (c.moveToNext()) {
                checkCancelled()
                add(LocalAudioProgress(c.getString(0), c.getLong(1), c.getInt(2) != 0, c.getLong(3)))
            }
        } }
        return LocalAudioLibraryState(ready = true, revision = currentRevision(db, LocalAudioError.STORAGE_FAILED),
            folders = listOf(folder), items = items, members = members, progress = progress)
    }

    /** IDs not yet filed in [folderId], in the caller's order. */
    private fun pendingMembers(db: SQLiteDatabase, folderId: String, audioIds: List<String>,
        checkCancelled: () -> Unit): List<String> {
        val filed = memberIds(db, folderId)
        return audioIds.filter { audioId ->
            checkCancelled()
            audioId !in filed
        }
    }

    /** Fails before any row is written when the final member count would exceed the cap. */
    private fun requireMemberCapacity(db: SQLiteDatabase, delta: Int, adding: Int) {
        if (adding <= 0) return
        val final = count(db, "members") + delta + adding
        if (final > LocalAudioLimits.MAX_MEMBERS) throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
    }

    /** Appends in the caller's order after the folder's current last position. */
    private fun appendMembers(db: SQLiteDatabase, folderId: String, audioIds: List<String>, checkCancelled: () -> Unit) {
        if (audioIds.isEmpty()) return
        var position = nextPosition(db, folderId)
        if (position.toLong() + audioIds.size - 1 > Int.MAX_VALUE) {
            throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
        }
        for (audioId in audioIds) {
            checkCancelled()
            db.insertOrThrow("members", null, ContentValues().apply {
                put("folder_id", folderId)
                put("audio_id", audioId)
                put("position", position)
            })
            position++
        }
    }

    /** Drops one membership plus the source folder's cursor, and nothing else. */
    private fun dropMember(db: SQLiteDatabase, folderId: String, audioId: String) {
        db.delete("members", "folder_id = ? AND audio_id = ?", arrayOf(folderId, audioId))
        db.delete("cursors", "folder_id = ? AND audio_id = ?", arrayOf(folderId, audioId))
    }

    override fun removeAudio(audioId: String): LocalAudioSource = write {
        requireAudio(audioId)
        val source = sourceOf(db, audioId) ?: throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
        db.delete("members", "audio_id = ?", arrayOf(audioId))
        db.delete("cursors", "audio_id = ?", arrayOf(audioId))
        db.delete("progress", "audio_id = ?", arrayOf(audioId))
        db.delete("bookmarks", "audio_id = ?", arrayOf(audioId))
        if (db.delete("audio", "id = ?", arrayOf(audioId)) != 1) throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
        changed = true
        source
    }

    /**
     * Reference files only; a copy is owned by the library and has no document URI to relink.
     * The audio ID, display name, progress and bookmarks are untouched.
     */
    override fun relink(audioId: String, metadata: LocalAudioMetadata, source: LocalAudioSource): LocalAudioSource = write {
        requireAudio(audioId)
        val current = sourceOf(db, audioId) ?: throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
        if (current.mode != LocalImportMode.REFERENCE || source.mode != LocalImportMode.REFERENCE) {
            throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
        }
        val owner = findAudioId(db, source.mode, source.identity)
        if (owner != null && owner != audioId) throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
        val values = sourceValues(metadata, source)
        values.put("availability", LocalAudioAvailability.AVAILABLE.name)
        db.update("audio", values, "id = ?", arrayOf(audioId))
        changed = true
        source
    }

    override fun setAvailability(audioId: String, availability: LocalAudioAvailability) {
        write {
            requireAudio(audioId)
            db.update("audio", ContentValues().apply { put("availability", availability.name) }, "id = ?", arrayOf(audioId))
            changed = true
        }
    }

    /**
     * Cover only. The source version columns (size, modification time, identity, location) are
     * deliberately untouched, so a refreshed cover can never look like different content.
     */
    override fun setArtwork(audioId: String, artworkKey: String?) {
        write {
            requireAudio(audioId)
            validateArtworkKey(artworkKey)
            db.update("audio", ContentValues().apply {
                if (artworkKey == null) putNull("artwork_key") else put("artwork_key", artworkKey)
            }, "id = ?", arrayOf(audioId))
            changed = true
        }
    }

    /**
     * Speed only. The source version columns stay untouched, so a changed rate can never look
     * like different content, and a re-scan or relink keeps the user's choice.
     */
    override fun setPlaybackSpeed(audioId: String, speed: Float) {
        write {
            requireAudio(audioId)
            try {
                LocalAudioLimits.requirePlaybackSpeed(speed)
            } catch (e: IllegalArgumentException) {
                throw LocalAudioFailure(LocalAudioError.INVALID_TARGET)
            }
            db.update("audio", ContentValues().apply { put("playback_speed", speed) }, "id = ?", arrayOf(audioId))
            changed = true
        }
    }

    /**
     * The user's own cover only. The automatic column is deliberately left alone, so clearing
     * this value falls back to the extracted cover instead of destroying it.
     */
    override fun setCustomArtwork(audioId: String, artworkKey: String?) {
        write {
            requireAudio(audioId)
            validateArtworkKey(artworkKey)
            db.update("audio", ContentValues().apply {
                if (artworkKey == null) putNull("custom_artwork_key") else put("custom_artwork_key", artworkKey)
            }, "id = ?", arrayOf(audioId))
            changed = true
        }
    }

    /**
     * Artwork keys still referenced by the library; everything else may be pruned from the cache.
     * Both kinds are reported: a chosen cover is kept even while the automatic cover is hidden,
     * because another setting must be able to bring it back without re-extracting it.
     */
    override fun retainedArtworkKeys(): Set<String> = query { db ->
        val keys = LinkedHashSet<String>()
        db.rawQuery("SELECT DISTINCT artwork_key FROM audio WHERE artwork_key IS NOT NULL", null)
            .use { c -> while (c.moveToNext()) keys.add(c.getString(0)) }
        db.rawQuery("SELECT DISTINCT custom_artwork_key FROM audio WHERE custom_artwork_key IS NOT NULL", null)
            .use { c -> while (c.moveToNext()) keys.add(c.getString(0)) }
        keys
    }

    /** Saves the per-audio position and, for a folder, moves that folder's cursor to [value]. */
    override fun saveProgress(folderId: String?, value: LocalAudioProgress) {
        write {
            requireAudio(value.audioId)
            db.insertWithOnConflict("progress", null, ContentValues().apply {
                put("audio_id", value.audioId)
                put("position_ms", value.positionMs)
                put("completed", if (value.completed) 1 else 0)
                put("updated_at_ms", value.updatedAtMs)
            }, SQLiteDatabase.CONFLICT_REPLACE).also { row ->
                if (row == -1L) throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
            }
            if (folderId != null) {
                requireFolder(folderId)
                // The cursor always points at a current member; a stale caller cannot break it.
                if (isMember(db, folderId, value.audioId)) {
                    db.insertWithOnConflict("cursors", null, ContentValues().apply {
                        put("folder_id", folderId)
                        put("audio_id", value.audioId)
                    }, SQLiteDatabase.CONFLICT_REPLACE).also { row ->
                        if (row == -1L) throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
                    }
                }
            }
            changed = true
        }
    }

    override fun addBookmark(audioId: String, positionMs: Long, name: String): LocalAudioBookmark {
        return write {
            requireAudio(audioId)
            validatePosition(positionMs)
            validateName(name)
            if (count(db, "bookmarks") >= LocalAudioLimits.MAX_BOOKMARKS) throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
            val bookmark = LocalAudioBookmark(LocalAudioLimits.newId(), audioId, positionMs, name)
            db.insertOrThrow("bookmarks", null, ContentValues().apply {
                put("id", bookmark.id)
                put("audio_id", audioId)
                put("position_ms", positionMs)
                put("name", name)
                put("created_at_ms", now())
            })
            changed = true
            bookmark
        }
    }

    override fun removeBookmark(id: String) {
        write {
            validateId(id, LocalAudioError.NOT_FOUND)
            if (db.delete("bookmarks", "id = ?", arrayOf(id)) != 1) throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
            changed = true
        }
    }

    /**
     * Name only: the position and the stored order key are untouched, so a rename never moves a
     * marker. The name rule is the library-wide one; trimming is the caller's job.
     */
    override fun renameBookmark(id: String, name: String) {
        write {
            validateId(id, LocalAudioError.NOT_FOUND)
            if (!exists(db, "bookmarks", id)) throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
            // Checked after the target so a stale ID reports NOT_FOUND, matching renameAudio.
            validateName(name)
            db.update("bookmarks", ContentValues().apply { put("name", name) }, "id = ?", arrayOf(id))
            changed = true
        }
    }

    /** COPY [LocalAudioSource.location] is the managed file name, so it is the cleanup handle. */
    override fun retainedCopyNames(): Set<String> = query { db ->
        val names = LinkedHashSet<String>()
        db.rawQuery("SELECT DISTINCT location FROM audio WHERE mode = ?", arrayOf(LocalImportMode.COPY.name))
            .use { c -> while (c.moveToNext()) names.add(c.getString(0)) }
        names
    }

    /**
     * A directory keeps its grant for later refreshes even in COPY mode, where the imported rows
     * carry no document URI of their own.
     */
    override fun retainedGrantUris(): Set<String> = query { db ->
        val grants = LinkedHashSet<String>()
        db.rawQuery("SELECT DISTINCT grant_uri FROM audio WHERE mode = ? AND grant_uri IS NOT NULL",
            arrayOf(LocalImportMode.REFERENCE.name))
            .use { c -> while (c.moveToNext()) grants.add(c.getString(0)) }
        db.rawQuery("SELECT tree_uri FROM directories", null)
            .use { c -> while (c.moveToNext()) grants.add(c.getString(0)) }
        grants
    }

    /**
     * Read-only overview of what a merge would add. Matching is the same conservative rule the
     * restore uses: identical mode and source key, and exactly one candidate in the library.
     */
    override fun previewOrganization(backup: LocalAudioBackup): LocalAudioRestorePreview {
        validateBackup(backup)
        return query { db ->
            val matched = matchedAudio(db, backup)
            LocalAudioRestorePreview(
                backupId = backup.id,
                expectedRevision = currentRevision(db, LocalAudioError.READ_FAILED),
                matchedItems = matched.size,
                unmatchedItems = backup.items.size - matched.size,
                folders = backup.folders.size,
                bookmarks = backup.bookmarks.count { it.audioId in matched },
            )
        }
    }

    /**
     * Merges organization only: no audio bytes, source identity, grant or directory link is
     * imported and nothing current is overwritten or deleted. Unmatched backup items are skipped
     * along with their memberships, progress and bookmarks.
     */
    override fun restoreOrganization(backup: LocalAudioBackup, expectedRevision: Long,
        checkCancelled: () -> Unit): LocalAudioRestoreResult {
        checkCancelled()
        validateBackup(backup)
        return write {
            checkCancelled()
            if (currentRevision(db, LocalAudioError.STORAGE_FAILED) != expectedRevision) {
                throw LocalAudioFailure(LocalAudioError.STALE_STATE)
            }
            val matched = matchedAudio(db, backup)
            var addedFolders = 0
            var addedMembers = 0
            var addedBookmarks = 0

            for (folder in backup.folders) {
                checkCancelled()
                if (exists(db, "folders", folder.id)) continue
                if (count(db, "folders") >= LocalAudioLimits.MAX_FOLDERS) {
                    throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
                }
                db.insertOrThrow("folders", null, ContentValues().apply {
                    put("id", folder.id)
                    put("name", folder.name)
                    put("continue_playback", if (folder.continuePlayback) 1 else 0)
                    put("created_at_ms", now())
                })
                addedFolders++
                changed = true
            }

            // Memberships follow the backup order inside each folder; existing rows are never
            // reordered, so added entries simply continue after the current last position.
            for (member in backup.members.sortedWith(compareBy({ it.folderId }, { it.position }))) {
                checkCancelled()
                val audioId = matched[member.audioId] ?: continue
                if (isMember(db, member.folderId, audioId)) continue
                if (count(db, "members") >= LocalAudioLimits.MAX_MEMBERS) {
                    throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
                }
                db.insertOrThrow("members", null, ContentValues().apply {
                    put("folder_id", member.folderId)
                    put("audio_id", audioId)
                    put("position", nextPosition(db, member.folderId))
                })
                addedMembers++
                changed = true
            }

            for (entry in backup.items) {
                checkCancelled()
                val audioId = matched[entry.item.id] ?: continue
                // A user name already set on this device always wins over the backup alias.
                val alias = entry.item.displayName
                if (alias != null && db.update("audio", ContentValues().apply { put("display_name", alias) },
                        "id = ? AND display_name IS NULL", arrayOf(audioId)) > 0) {
                    changed = true
                }
            }

            val savedProgress = backup.progress.associateBy { it.audioId }
            for (entry in backup.items) {
                checkCancelled()
                val audioId = matched[entry.item.id] ?: continue
                val value = savedProgress[entry.item.id] ?: continue
                if (db.rawQuery("SELECT 1 FROM progress WHERE audio_id = ?", arrayOf(audioId)).use { it.moveToFirst() }) {
                    continue
                }
                db.insertOrThrow("progress", null, ContentValues().apply {
                    put("audio_id", audioId)
                    put("position_ms", value.positionMs)
                    put("completed", if (value.completed) 1 else 0)
                    put("updated_at_ms", value.updatedAtMs)
                })
                changed = true
            }

            val existingBookmarks = mutableSetOf<String>()
            db.rawQuery("SELECT id, audio_id, position_ms, name FROM bookmarks", null).use { c ->
                while (c.moveToNext()) {
                    existingBookmarks.add(c.getString(0))
                    existingBookmarks.add(bookmarkIdentity(c.getString(1), c.getLong(2), c.getString(3)))
                }
            }
            for (bookmark in backup.bookmarks) {
                checkCancelled()
                val audioId = matched[bookmark.audioId] ?: continue
                val identity = bookmarkIdentity(audioId, bookmark.positionMs, bookmark.name)
                // Several backup sources may map to one current audio: skip the obvious duplicate
                // instead of adding a second marker at the same place.
                if (bookmark.id in existingBookmarks || identity in existingBookmarks) continue
                if (count(db, "bookmarks") >= LocalAudioLimits.MAX_BOOKMARKS) {
                    throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
                }
                val id = if (exists(db, "bookmarks", bookmark.id)) LocalAudioLimits.newId() else bookmark.id
                db.insertOrThrow("bookmarks", null, ContentValues().apply {
                    put("id", id)
                    put("audio_id", audioId)
                    put("position_ms", bookmark.positionMs)
                    put("name", bookmark.name)
                    put("created_at_ms", now())
                })
                existingBookmarks.add(id)
                existingBookmarks.add(identity)
                addedBookmarks++
                changed = true
            }

            // A cursor is only a resume hint; the current one always stays authoritative.
            for (cursor in backup.cursors) {
                checkCancelled()
                val audioId = matched[cursor.audioId] ?: continue
                if (!isMember(db, cursor.folderId, audioId)) continue
                val row = db.insertWithOnConflict("cursors", null, ContentValues().apply {
                    put("folder_id", cursor.folderId)
                    put("audio_id", audioId)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                if (row != -1L) changed = true
            }

            checkCancelled()
            LocalAudioRestoreResult(
                backupId = backup.id,
                matchedItems = matched.size,
                unmatchedItems = backup.items.size - matched.size,
                addedFolders = addedFolders,
                addedMemberships = addedMembers,
                addedBookmarks = addedBookmarks,
            )
        }
    }

    /**
     * Repeating the same (folder, tree) pair returns the existing link untouched, so a re-scan never
     * overwrites the user's name, refresh state or error. A conflicting mode/recursion is a caller
     * error rather than a silent reconfiguration.
     */
    override fun linkDirectory(folderId: String, treeUri: String, name: String, mode: LocalImportMode,
        includeSubdirectories: Boolean): LocalAudioDirectoryLink = write {
        requireFolder(folderId)
        if (!LocalAudioLimits.isContentUri(treeUri)) throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
        validateName(name)
        val existing = db.rawQuery(
            "SELECT id, mode, recursive FROM directories WHERE folder_id = ? AND tree_uri = ?",
            arrayOf(folderId, treeUri),
        ).use { c ->
            if (!c.moveToFirst()) null else Triple(c.getString(0), c.getString(1), c.getInt(2) != 0)
        }
        if (existing != null) {
            if (existing.second != mode.name || existing.third != includeSubdirectories) {
                throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
            }
            return@write readDirectoryLinkById(db, existing.first)
                ?: throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
        }
        if (count(db, "directories") >= LocalAudioLimits.MAX_DIRECTORIES) {
            throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
        }
        val id = LocalAudioLimits.newId()
        db.insertOrThrow("directories", null, ContentValues().apply {
            put("id", id)
            put("folder_id", folderId)
            put("tree_uri", treeUri)
            put("name", name)
            put("mode", mode.name)
            put("recursive", if (includeSubdirectories) 1 else 0)
            put("created_at_ms", now())
        })
        changed = true
        readDirectoryLinkById(db, id) ?: throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
    }

    override fun directory(directoryId: String): StoredLocalAudioDirectory? = query { db ->
        if (!isValidId(directoryId)) return@query null
        db.rawQuery("SELECT $DIRECTORY_COLUMNS, tree_uri FROM directories WHERE id = ?", arrayOf(directoryId))
            .use { c ->
                if (!c.moveToFirst()) null
                else StoredLocalAudioDirectory(readDirectoryLink(c), c.getString(c.getColumnIndexOrThrow("tree_uri")))
            }
    }

    override fun directoryEntry(directoryId: String, documentIdentity: String): LocalAudioDirectoryEntry? = query { db ->
        if (!isValidId(directoryId) || documentIdentity.isBlank()) return@query null
        db.rawQuery(
            "SELECT identity, audio_id, size_bytes, modified_at_ms FROM directory_entries " +
                "WHERE directory_id = ? AND identity = ?",
            arrayOf(directoryId, documentIdentity),
        ).use { c ->
            if (!c.moveToFirst()) null
            else LocalAudioDirectoryEntry(
                LocalAudioDirectoryImport(directoryId, c.getString(0), longOrNull(c, "size_bytes"),
                    longOrNull(c, "modified_at_ms")),
                c.getString(1),
            )
        }
    }

    /** Removes the association and its provenance; imports, memberships and user data stay. */
    override fun unlinkDirectory(directoryId: String) {
        write {
            requireDirectory(db, directoryId)
            db.delete("directory_entries", "directory_id = ?", arrayOf(directoryId))
            if (db.delete("directories", "id = ?", arrayOf(directoryId)) != 1) {
                throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
            }
            changed = true
        }
    }

    /** Only the link's own state changes; availability and provenance are separate facts. */
    override fun setDirectoryError(directoryId: String, error: LocalAudioError?) {
        write {
            requireDirectory(db, directoryId)
            db.update("directories", ContentValues().apply {
                if (error == null) putNull("error") else put("error", error.name)
            }, "id = ?", arrayOf(directoryId))
            changed = true
        }
    }

    /**
     * Recomputes the missing count from the provenance of a complete listing. The count covers both
     * modes; only a REFERENCE entry has its availability changed, and only while its stored source
     * still matches the recorded identity and directory tree, so an entry relinked to a new version
     * is never marked bad by an older refresh. A per-entry failure is not a listing failure and
     * never suppresses the count.
     */
    override fun finishDirectoryRefresh(directoryId: String, seenIdentities: Set<String>, refreshedAtMs: Long,
        error: LocalAudioError?): Int = write {
        requireDirectory(db, directoryId)
        if (refreshedAtMs < 0) throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
        // A caller that exceeds the limit or passes an unusable identity has a broken listing; that
        // must fail loudly instead of silently shrinking the "seen" set and marking audio missing.
        if (seenIdentities.size > LocalAudioLimits.MAX_ITEMS) {
            throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
        }
        if (seenIdentities.any { it.isBlank() || it.length > 8192 }) {
            throw LocalAudioFailure(LocalAudioError.READ_FAILED)
        }
        val stored = readDirectory(db, directoryId) ?: throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
        val seen = LinkedHashSet(seenIdentities)
        var missing = 0
        db.rawQuery(
            "SELECT e.identity, e.audio_id, a.identity, a.mode, a.grant_uri FROM directory_entries e " +
                "JOIN audio a ON a.id = e.audio_id WHERE e.directory_id = ?",
            arrayOf(directoryId),
        ).use { c ->
            while (c.moveToNext()) {
                val identity = c.getString(0)
                if (identity in seen) continue
                val audioId = c.getString(1)
                // The audio row decides what the entry currently points at; a relink to a new
                // version or another tree no longer represents this origin at all.
                when (c.getString(3)) {
                    LocalImportMode.COPY.name -> {
                        if (stored.link.mode != LocalImportMode.COPY) continue
                        // A copy is owned by the library: only the count reflects the missing
                        // origin, the audio itself stays available and playable.
                        missing++
                    }
                    LocalImportMode.REFERENCE.name -> {
                        if (stored.link.mode != LocalImportMode.REFERENCE) continue
                        if (c.getString(2) != identity || c.getString(4) != stored.treeUri) continue
                        missing++
                        setMissing(db, audioId)
                    }
                }
            }
        }
        db.update("directories", ContentValues().apply {
            put("last_refreshed_at_ms", refreshedAtMs)
            put("missing_count", missing)
            if (error == null) putNull("error") else put("error", error.name)
        }, "id = ?", arrayOf(directoryId))
        changed = true
        missing
    }

    override fun close() {
        synchronized(lock) { helper.close() }
    }

    private fun <T> write(block: Tx.() -> T): T = synchronized(lock) {
        val db = writableDatabase()
        try {
            db.beginTransaction()
        } catch (e: SQLiteException) {
            throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
        } catch (e: IllegalStateException) {
            throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
        }
        var pending: Throwable? = null
        try {
            val tx = Tx(db)
            val value = tx.block()
            if (tx.changed) bumpRevision(db)
            db.setTransactionSuccessful()
            value
        } catch (e: Throwable) {
            pending = e
            throw when (e) {
                is java.util.concurrent.CancellationException -> e
                is LocalAudioFailure, is Error -> e
                is SQLiteException, is IllegalStateException, is IllegalArgumentException ->
                    LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
                else -> e
            }
        } finally {
            endTransaction(db, pending)
        }
    }

    private fun <T> query(block: (SQLiteDatabase) -> T): T = synchronized(lock) {
        val db = try {
            helper.readableDatabase
        } catch (e: SQLiteException) {
            throw LocalAudioFailure(LocalAudioError.READ_FAILED)
        } catch (e: LocalAudioFailure) {
            // e.g. an on-disk schema version this build cannot migrate: report it, never reset it.
            throw LocalAudioFailure(LocalAudioError.READ_FAILED)
        } catch (e: IllegalStateException) {
            throw LocalAudioFailure(LocalAudioError.READ_FAILED)
        }
        try {
            block(db)
        } catch (e: SQLiteException) {
            throw LocalAudioFailure(LocalAudioError.READ_FAILED)
        } catch (e: LocalAudioFailure) {
            throw LocalAudioFailure(LocalAudioError.READ_FAILED)
        } catch (e: IllegalArgumentException) {
            throw LocalAudioFailure(LocalAudioError.READ_FAILED)
        }
    }

    private fun writableDatabase(): SQLiteDatabase = try {
        helper.writableDatabase
    } catch (e: SQLiteException) {
        throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
    } catch (e: LocalAudioFailure) {
        throw e
    } catch (e: IllegalStateException) {
        throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
    }

    /** A rollback failure must not mask the error that caused it. */
    private fun endTransaction(db: SQLiteDatabase, pending: Throwable?) {
        try {
            db.endTransaction()
        } catch (e: SQLiteException) {
            if (pending == null) throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
        }
    }

    private fun bumpRevision(db: SQLiteDatabase) {
        val current = currentRevision(db, LocalAudioError.STORAGE_FAILED)
        val next = if (current == Long.MAX_VALUE) 1L else current + 1
        val row = db.insertWithOnConflict("meta", null, ContentValues().apply {
            put("key", REVISION_KEY)
            put("value", next.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (row == -1L) throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
    }

    /**
     * The revision is the caller's staleness token, so a missing, non-numeric or negative value is
     * a damaged library, not revision zero.
     */
    private fun currentRevision(db: SQLiteDatabase, error: LocalAudioError): Long {
        val raw = db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(REVISION_KEY)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        return raw?.toLongOrNull()?.takeIf { it >= 0 } ?: throw LocalAudioFailure(error)
    }

    private fun sourceValues(metadata: LocalAudioMetadata, source: LocalAudioSource): ContentValues = ContentValues().apply {
        put("mode", source.mode.name)
        put("identity", source.identity)
        put("location", source.location)
        if (source.grantUri == null) putNull("grant_uri") else put("grant_uri", source.grantUri)
        put("file_name", metadata.fileName)
        if (metadata.title == null) putNull("title") else put("title", metadata.title)
        if (metadata.creator == null) putNull("creator") else put("creator", metadata.creator)
        if (metadata.durationMs == null) putNull("duration_ms") else put("duration_ms", metadata.durationMs)
        if (metadata.sizeBytes == null) putNull("size_bytes") else put("size_bytes", metadata.sizeBytes)
        if (metadata.modifiedAtMs == null) putNull("modified_at_ms") else put("modified_at_ms", metadata.modifiedAtMs)
        put("kind", metadata.kind.name)
        if (metadata.artworkKey == null) putNull("artwork_key") else put("artwork_key", metadata.artworkKey)
    }

    private fun readFolder(c: Cursor): LocalAudioFolder =
        LocalAudioFolder(c.getString(0), c.getString(1), c.getInt(2) != 0)

    private fun readItem(c: Cursor): LocalAudioItem = LocalAudioItem(
        id = c.getString(c.getColumnIndexOrThrow("id")),
        metadata = LocalAudioMetadata(
            fileName = c.getString(c.getColumnIndexOrThrow("file_name")),
            title = stringOrNull(c, "title"),
            creator = stringOrNull(c, "creator"),
            durationMs = longOrNull(c, "duration_ms"),
            sizeBytes = longOrNull(c, "size_bytes"),
            modifiedAtMs = longOrNull(c, "modified_at_ms"),
            kind = LocalMediaKind.valueOf(c.getString(c.getColumnIndexOrThrow("kind"))),
            artworkKey = stringOrNull(c, "artwork_key"),
        ),
        mode = LocalImportMode.valueOf(c.getString(c.getColumnIndexOrThrow("mode"))),
        displayName = stringOrNull(c, "display_name"),
        availability = LocalAudioAvailability.valueOf(c.getString(c.getColumnIndexOrThrow("availability"))),
        addedAtMs = c.getLong(c.getColumnIndexOrThrow("added_at_ms")),
        playbackSpeed = c.getFloat(c.getColumnIndexOrThrow("playback_speed")),
        customArtworkKey = stringOrNull(c, "custom_artwork_key"),
    )

    private fun readSource(c: Cursor): LocalAudioSource = LocalAudioSource(
        mode = LocalImportMode.valueOf(c.getString(c.getColumnIndexOrThrow("mode"))),
        location = c.getString(c.getColumnIndexOrThrow("location")),
        identity = c.getString(c.getColumnIndexOrThrow("identity")),
        grantUri = stringOrNull(c, "grant_uri"),
    )

    private fun stringOrNull(c: Cursor, column: String): String? {
        val index = c.getColumnIndexOrThrow(column)
        return if (c.isNull(index)) null else c.getString(index)
    }

    private fun longOrNull(c: Cursor, column: String): Long? {
        val index = c.getColumnIndexOrThrow(column)
        return if (c.isNull(index)) null else c.getLong(index)
    }

    private fun readItemById(db: SQLiteDatabase, audioId: String): LocalAudioItem? =
        db.rawQuery("SELECT $AUDIO_COLUMNS FROM audio WHERE id = ?", arrayOf(audioId)).use { c ->
            if (c.moveToFirst()) readItem(c) else null
        }

    private fun sourceOf(db: SQLiteDatabase, audioId: String): LocalAudioSource? =
        db.rawQuery("SELECT $AUDIO_COLUMNS FROM audio WHERE id = ?", arrayOf(audioId)).use { c ->
            if (c.moveToFirst()) readSource(c) else null
        }

    private fun findAudioId(db: SQLiteDatabase, mode: LocalImportMode, identity: String): String? =
        db.rawQuery("SELECT id FROM audio WHERE mode = ? AND identity = ?", arrayOf(mode.name, identity)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /**
     * Only two known, different values prove that the document now holds other content; an unknown
     * value is never treated as a change. Size is checked first, then modification time, so a
     * caller that knows one dimension alone still gets the conservative answer.
     */
    private fun contentChanged(db: SQLiteDatabase, audioId: String, metadata: LocalAudioMetadata): Boolean =
        db.rawQuery("SELECT size_bytes, modified_at_ms FROM audio WHERE id = ?", arrayOf(audioId)).use { c ->
            if (!c.moveToFirst()) return@use false
            val storedSize = if (c.isNull(0)) null else c.getLong(0)
            val storedModified = if (c.isNull(1)) null else c.getLong(1)
            (storedSize != null && metadata.sizeBytes != null && storedSize != metadata.sizeBytes) ||
                (storedModified != null && metadata.modifiedAtMs != null && storedModified != metadata.modifiedAtMs)
        }

    private fun requireDirectory(db: SQLiteDatabase, id: String) {
        validateId(id, LocalAudioError.NOT_FOUND)
        if (!exists(db, "directories", id)) throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
    }

    private fun readDirectory(db: SQLiteDatabase, id: String): StoredLocalAudioDirectory? =
        db.rawQuery("SELECT $DIRECTORY_COLUMNS, tree_uri FROM directories WHERE id = ?", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) null
            else StoredLocalAudioDirectory(readDirectoryLink(c), c.getString(c.getColumnIndexOrThrow("tree_uri")))
        }

    private fun readDirectoryLinkById(db: SQLiteDatabase, id: String): LocalAudioDirectoryLink? =
        db.rawQuery("SELECT $DIRECTORY_COLUMNS FROM directories WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) readDirectoryLink(c) else null
        }

    private fun readDirectoryLink(c: Cursor): LocalAudioDirectoryLink = LocalAudioDirectoryLink(
        id = c.getString(c.getColumnIndexOrThrow("id")),
        folderId = c.getString(c.getColumnIndexOrThrow("folder_id")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        mode = LocalImportMode.valueOf(c.getString(c.getColumnIndexOrThrow("mode"))),
        includeSubdirectories = c.getInt(c.getColumnIndexOrThrow("recursive")) != 0,
        lastRefreshedAtMs = longOrNull(c, "last_refreshed_at_ms"),
        missingCount = c.getInt(c.getColumnIndexOrThrow("missing_count")),
        error = stringOrNull(c, "error")?.let(LocalAudioError::valueOf),
    )

    /** The audio ID a document was last imported as, or null when this directory never saw it. */
    private fun savedOrigin(db: SQLiteDatabase, directoryId: String, identity: String): String? =
        db.rawQuery("SELECT audio_id FROM directory_entries WHERE directory_id = ? AND identity = ?",
            arrayOf(directoryId, identity)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /** Provenance is replaced in place: one origin per (directory, document) pair. */
    private fun putOrigin(db: SQLiteDatabase, directoryId: String, origin: LocalAudioDirectoryImport, audioId: String) {
        db.insertWithOnConflict("directory_entries", null, ContentValues().apply {
            put("directory_id", directoryId)
            put("identity", origin.documentIdentity)
            put("audio_id", audioId)
            if (origin.sizeBytes == null) putNull("size_bytes") else put("size_bytes", origin.sizeBytes)
            if (origin.modifiedAtMs == null) putNull("modified_at_ms") else put("modified_at_ms", origin.modifiedAtMs)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { row ->
            if (row == -1L) throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
        }
    }

    private fun setMissing(db: SQLiteDatabase, audioId: String) {
        db.update("audio", ContentValues().apply { put("availability", LocalAudioAvailability.MISSING.name) },
            "id = ?", arrayOf(audioId))
    }

    /** Re-validate first: a backup is a caller-supplied object, so its invariants are re-checked. */
    private fun validateBackup(backup: LocalAudioBackup) {
        try {
            backup.validate()
        } catch (e: IllegalArgumentException) {
            throw LocalAudioFailure(LocalAudioError.INVALID_BACKUP)
        }
    }

    /**
     * Maps backup audio IDs to current audio IDs by exact mode and source key only. A key with more
     * than one current candidate is ambiguous and therefore unmatched; names, sizes and IDs are
     * never used as a guess.
     */
    private fun matchedAudio(db: SQLiteDatabase, backup: LocalAudioBackup): Map<String, String> {
        val current = HashMap<String, MutableList<String>>()
        db.rawQuery("SELECT $AUDIO_COLUMNS FROM audio", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(c.getColumnIndexOrThrow("id"))
                val key = LocalAudioBackupKeys.source(StoredLocalAudio(readItem(c), readSource(c)))
                current.getOrPut("${c.getString(c.getColumnIndexOrThrow("mode"))}|$key") { mutableListOf() }.add(id)
            }
        }
        val matched = LinkedHashMap<String, String>()
        for (entry in backup.items) {
            val candidates = current["${entry.item.mode.name}|${entry.sourceKey}"]
            if (candidates != null && candidates.size == 1) matched[entry.item.id] = candidates.single()
        }
        return matched
    }

    private fun bookmarkIdentity(audioId: String, positionMs: Long, name: String): String =
        "$audioId|$positionMs|$name"

    private fun exists(db: SQLiteDatabase, table: String, id: String): Boolean =
        db.rawQuery("SELECT 1 FROM $table WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }

    private fun count(db: SQLiteDatabase, table: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun isMember(db: SQLiteDatabase, folderId: String, audioId: String): Boolean =
        db.rawQuery("SELECT 1 FROM members WHERE folder_id = ? AND audio_id = ?", arrayOf(folderId, audioId))
            .use { it.moveToFirst() }

    private fun memberIds(db: SQLiteDatabase, folderId: String): Set<String> {
        val ids = LinkedHashSet<String>()
        db.rawQuery("SELECT audio_id FROM members WHERE folder_id = ?", arrayOf(folderId))
            .use { c -> while (c.moveToNext()) ids.add(c.getString(0)) }
        return ids
    }

    private fun nextPosition(db: SQLiteDatabase, folderId: String): Int =
        db.rawQuery("SELECT COALESCE(MAX(position), -1) + 1 FROM members WHERE folder_id = ?", arrayOf(folderId))
            .use {
                val next = if (it.moveToFirst()) it.getLong(0) else 0L
                if (next < 0 || next > Int.MAX_VALUE) throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
                next.toInt()
            }

    private fun now(): Long = System.currentTimeMillis()

    private fun validateName(name: String) {
        try {
            LocalAudioLimits.requireName(name)
        } catch (e: IllegalArgumentException) {
            throw LocalAudioFailure(LocalAudioError.INVALID_NAME)
        }
    }

    /**
     * [LocalAudioMetadata] already rejects a malformed key, but [setArtwork] takes a raw string, so
     * the store maps it to a library error instead of a generic write failure.
     */
    private fun validateArtworkKey(key: String?) {
        try {
            LocalAudioLimits.requireArtworkKey(key)
        } catch (e: IllegalArgumentException) {
            throw LocalAudioFailure(LocalAudioError.INVALID_TARGET)
        }
    }

    private fun validatePosition(positionMs: Long) {
        if (positionMs !in 0..LocalAudioLimits.MAX_POSITION_MS) throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
    }

    private fun isValidId(id: String): Boolean = try {
        LocalAudioLimits.requireId(id)
        true
    } catch (e: IllegalArgumentException) {
        false
    }

    private fun validateId(id: String, error: LocalAudioError) {
        if (!isValidId(id)) throw LocalAudioFailure(error)
    }

    companion object {
        const val DB_NAME = "local-audio-v1.db"
        private const val SCHEMA_VERSION = 4
        private const val REVISION_KEY = "revision"
        private const val AUDIO_COLUMNS =
            "id, mode, identity, location, grant_uri, file_name, title, creator, duration_ms, size_bytes, modified_at_ms, " +
                "display_name, availability, added_at_ms, kind, artwork_key, playback_speed, custom_artwork_key"
        private const val DIRECTORY_COLUMNS =
            "id, folder_id, name, mode, recursive, last_refreshed_at_ms, missing_count, error, created_at_ms"

        private const val FOLDERS_TABLE =
            "CREATE TABLE folders (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "name TEXT NOT NULL, " +
                "continue_playback INTEGER NOT NULL, " +
                "created_at_ms INTEGER NOT NULL)"
        private const val AUDIO_TABLE =
            "CREATE TABLE audio (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "mode TEXT NOT NULL, " +
                "identity TEXT NOT NULL, " +
                "location TEXT NOT NULL, " +
                "grant_uri TEXT, " +
                "file_name TEXT NOT NULL, " +
                "title TEXT, " +
                "creator TEXT, " +
                "duration_ms INTEGER, " +
                "size_bytes INTEGER, " +
                "modified_at_ms INTEGER, " +
                "display_name TEXT, " +
                "availability TEXT NOT NULL, " +
                "added_at_ms INTEGER NOT NULL, " +
                // Existing libraries migrate to AUDIO; artwork stays unknown until it is captured.
                "kind TEXT NOT NULL DEFAULT 'AUDIO', " +
                "artwork_key TEXT, " +
                // Existing libraries start at normal speed and without a chosen cover.
                "playback_speed REAL NOT NULL DEFAULT 1.0, " +
                "custom_artwork_key TEXT, " +
                "UNIQUE (mode, identity))"
        private val OTHER_TABLES = listOf(
            "CREATE TABLE members (" +
                "folder_id TEXT NOT NULL REFERENCES folders (id) ON DELETE CASCADE, " +
                "audio_id TEXT NOT NULL REFERENCES audio (id) ON DELETE CASCADE, " +
                "position INTEGER NOT NULL, " +
                "PRIMARY KEY (folder_id, audio_id))",
            "CREATE TABLE progress (" +
                "audio_id TEXT NOT NULL PRIMARY KEY REFERENCES audio (id) ON DELETE CASCADE, " +
                "position_ms INTEGER NOT NULL, " +
                "completed INTEGER NOT NULL, " +
                "updated_at_ms INTEGER NOT NULL)",
            "CREATE TABLE cursors (" +
                "folder_id TEXT NOT NULL PRIMARY KEY REFERENCES folders (id) ON DELETE CASCADE, " +
                "audio_id TEXT NOT NULL REFERENCES audio (id) ON DELETE CASCADE)",
            "CREATE TABLE bookmarks (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "audio_id TEXT NOT NULL REFERENCES audio (id) ON DELETE CASCADE, " +
                "position_ms INTEGER NOT NULL, " +
                "name TEXT NOT NULL, " +
                "created_at_ms INTEGER NOT NULL)",
            "CREATE TABLE meta (" +
                "key TEXT NOT NULL PRIMARY KEY, " +
                "value TEXT NOT NULL)",
            "CREATE INDEX members_audio ON members(audio_id)",
            "CREATE INDEX members_order ON members(folder_id, position)",
            "CREATE INDEX bookmarks_audio ON bookmarks(audio_id)",
        )

        /** Schema-2 directory tables; created by both [onCreate] and the version-1 upgrade. */
        private val DIRECTORY_TABLES = listOf(
            "CREATE TABLE directories (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "folder_id TEXT NOT NULL REFERENCES folders (id) ON DELETE CASCADE, " +
                "tree_uri TEXT NOT NULL, " +
                "name TEXT NOT NULL, " +
                "mode TEXT NOT NULL, " +
                "recursive INTEGER NOT NULL, " +
                "last_refreshed_at_ms INTEGER, " +
                "missing_count INTEGER NOT NULL DEFAULT 0, " +
                "error TEXT, " +
                "created_at_ms INTEGER NOT NULL, " +
                "UNIQUE (folder_id, tree_uri))",
            "CREATE TABLE directory_entries (" +
                "directory_id TEXT NOT NULL REFERENCES directories (id) ON DELETE CASCADE, " +
                "identity TEXT NOT NULL, " +
                "audio_id TEXT NOT NULL REFERENCES audio (id) ON DELETE CASCADE, " +
                "size_bytes INTEGER, " +
                "modified_at_ms INTEGER, " +
                "PRIMARY KEY (directory_id, identity))",
            "CREATE INDEX directories_folder ON directories(folder_id)",
            "CREATE INDEX directory_entries_audio ON directory_entries(audio_id)",
        )

        private val MIGRATION_2 = listOf<String>(
            // Unknown legacy modification times stay NULL, which is never read as a content change.
            "ALTER TABLE audio ADD COLUMN modified_at_ms INTEGER",
        ) + DIRECTORY_TABLES

        /**
         * Schema 3. A SQLite column default is required so every legacy row reads as [AUDIO];
         * the artwork key is nullable because a missing cover is a normal state, not an error.
         */
        private val MIGRATION_3 = listOf(
            "ALTER TABLE audio ADD COLUMN kind TEXT NOT NULL DEFAULT 'AUDIO'",
            "ALTER TABLE audio ADD COLUMN artwork_key TEXT",
        )

        /**
         * Schema 4. The speed default is a real column default so every legacy row reads as 1x;
         * the chosen cover stays NULL because none was ever stored, and the automatic
         * [LocalAudioMetadata.artworkKey] column is kept separate instead of being reused.
         */
        private val MIGRATION_4 = listOf(
            "ALTER TABLE audio ADD COLUMN playback_speed REAL NOT NULL DEFAULT 1.0",
            "ALTER TABLE audio ADD COLUMN custom_artwork_key TEXT",
        )

        /** A new database is created directly at the current version, never upgraded afterwards. */
        private val SCHEMA = listOf(FOLDERS_TABLE, AUDIO_TABLE) + OTHER_TABLES + DIRECTORY_TABLES
    }
}
