package tech.musxi.core.localaudio

import java.io.IOException
import java.net.URI
import java.util.UUID

object LocalAudioLimits {
    private val idPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val artworkKeyPattern = Regex("[0-9a-f]{64}")
    private val playbackSpeeds = setOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    const val MAX_ITEMS = 20_000
    const val MAX_FOLDERS = 500
    const val MAX_DIRECTORIES = 500
    const val MAX_BATCH = 500
    const val MAX_MEMBERS = 100_000
    const val MAX_BOOKMARKS = 50_000
    const val MAX_NAME = 1024
    const val MAX_FILE_BYTES = 64L * 1024 * 1024 * 1024
    // A local recording may exceed the platform-song limit of 24 hours.
    const val MAX_POSITION_MS = 366L * 24 * 60 * 60 * 1000
    fun newId(): String = UUID.randomUUID().toString()
    fun requireId(value: String) {
        require(idPattern.matches(value))
    }
    fun requireName(value: String) {
        require(value.isNotBlank() && value.length <= MAX_NAME && value.none { it.isISOControl() })
    }
    fun isContentUri(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "content" && !uri.rawAuthority.isNullOrEmpty() && value.length <= 8192
    }.getOrDefault(false)
    /**
     * Only the seven pitch-preserving speeds are storable. A NaN, an infinity or any other value
     * would be silently rounded by the player, so it is rejected instead of being persisted.
     */
    fun requirePlaybackSpeed(value: Float) {
        require(value.isFinite() && value in playbackSpeeds)
    }
    /** Artwork keys are content hashes in the app-private artwork cache, never paths or URIs. */
    fun requireArtworkKey(value: String?) {
        if (value != null) require(artworkKeyPattern.matches(value))
    }
}

enum class LocalImportMode { COPY, REFERENCE }
/** A video file is only ever played as sound in this build; the marker reserves the UI affordance. */
enum class LocalMediaKind { AUDIO, VIDEO }
enum class LocalAudioAvailability { AVAILABLE, MISSING, PERMISSION_REQUIRED, UNREADABLE }
enum class LocalAudioError {
    INVALID_NAME, NOT_FOUND, INVALID_ORDER, LIMIT_REACHED, READ_FAILED, STORAGE_FAILED,
    PERMISSION_REQUIRED, UNSUPPORTED_AUDIO, TOO_LARGE, NOT_ENOUGH_SPACE, SOURCE_CHANGED,
    INVALID_BACKUP, UNSUPPORTED_BACKUP, STALE_STATE, INVALID_TARGET,
}
class LocalAudioFailure(val error: LocalAudioError) : IOException("Local audio: ${error.name}")

data class LocalAudioMetadata(
    val fileName: String,
    val title: String? = null,
    val creator: String? = null,
    val durationMs: Long? = null,
    val sizeBytes: Long? = null,
    val modifiedAtMs: Long? = null,
    val kind: LocalMediaKind = LocalMediaKind.AUDIO,
    val artworkKey: String? = null,
) {
    init {
        LocalAudioLimits.requireName(fileName)
        title?.let(LocalAudioLimits::requireName)
        creator?.let(LocalAudioLimits::requireName)
        require(durationMs == null || durationMs in 0..LocalAudioLimits.MAX_POSITION_MS)
        require(sizeBytes == null || sizeBytes in 1..LocalAudioLimits.MAX_FILE_BYTES)
        require(modifiedAtMs == null || modifiedAtMs >= 0)
        LocalAudioLimits.requireArtworkKey(artworkKey)
    }
}

/** Controller/UI metadata deliberately contains no file paths or document URIs. */
data class LocalAudioItem(
    val id: String,
    val metadata: LocalAudioMetadata,
    val mode: LocalImportMode,
    val displayName: String? = null,
    val availability: LocalAudioAvailability = LocalAudioAvailability.AVAILABLE,
    val addedAtMs: Long,
    /** Per-audio playback rate; 1 is the stored default and the only value platform songs use. */
    val playbackSpeed: Float = 1f,
    /** The user's own cover; [metadata] only ever holds the automatically extracted one. */
    val customArtworkKey: String? = null,
) {
    init {
        LocalAudioLimits.requireId(id)
        displayName?.let(LocalAudioLimits::requireName)
        require(addedAtMs >= 0)
        LocalAudioLimits.requirePlaybackSpeed(playbackSpeed)
        LocalAudioLimits.requireArtworkKey(customArtworkKey)
    }
    /** Derived from [metadata] on purpose: one stored copy, no second source of truth. */
    val kind: LocalMediaKind get() = metadata.kind
    /** A chosen cover outranks the automatic one; clearing it falls back to the automatic cover. */
    val artworkKey: String? get() = customArtworkKey ?: metadata.artworkKey
    val artworkCustom: Boolean get() = customArtworkKey != null
    val title: String get() = displayName ?: metadata.title
        ?: metadata.fileName.substringBeforeLast('.', metadata.fileName).ifBlank { metadata.fileName }
}

/** Backend capability; never publish this object to a UI state or log. */
class LocalAudioSource(
    val mode: LocalImportMode,
    val location: String,
    val identity: String,
    val grantUri: String? = null,
) {
    init {
        require(identity.isNotBlank() && identity.length <= 8192)
        if (mode == LocalImportMode.COPY) {
            require(Regex("[0-9a-f]{64}").matches(location) && grantUri == null)
        } else {
            require(LocalAudioLimits.isContentUri(location))
            require(grantUri != null && LocalAudioLimits.isContentUri(grantUri))
        }
    }
    override fun toString(): String = "LocalAudioSource(${mode.name}, redacted)"
}

data class StoredLocalAudio(val item: LocalAudioItem, val source: LocalAudioSource)
data class LocalAudioFolder(val id: String, val name: String, val continuePlayback: Boolean = true) {
    init { LocalAudioLimits.requireId(id); LocalAudioLimits.requireName(name) }
}
data class LocalAudioMember(val folderId: String, val audioId: String, val position: Int)
data class LocalAudioProgress(val audioId: String, val positionMs: Long, val completed: Boolean, val updatedAtMs: Long) {
    init {
        LocalAudioLimits.requireId(audioId)
        require(positionMs in 0..LocalAudioLimits.MAX_POSITION_MS && updatedAtMs >= 0)
    }
}
data class LocalAudioBookmark(val id: String, val audioId: String, val positionMs: Long, val name: String) {
    init {
        LocalAudioLimits.requireId(id); LocalAudioLimits.requireId(audioId); LocalAudioLimits.requireName(name)
        require(positionMs in 0..LocalAudioLimits.MAX_POSITION_MS)
    }
}
data class LocalAudioFolderCursor(val folderId: String, val audioId: String)
data class LocalAudioLibraryState(
    val ready: Boolean = false,
    val revision: Long = 0,
    val folders: List<LocalAudioFolder> = emptyList(),
    val items: List<LocalAudioItem> = emptyList(),
    val members: List<LocalAudioMember> = emptyList(),
    val progress: List<LocalAudioProgress> = emptyList(),
    val cursors: List<LocalAudioFolderCursor> = emptyList(),
    val bookmarks: List<LocalAudioBookmark> = emptyList(),
    val error: LocalAudioError? = null,
    val directories: List<LocalAudioDirectoryLink> = emptyList(),
)

data class LocalAudioImportResult(val fileName: String, val audioId: String? = null, val error: LocalAudioError? = null)
data class LocalAudioImportState(
    val running: Boolean = false,
    val total: Int = 0,
    val processed: Int = 0,
    val currentName: String? = null,
    val copiedBytes: Long = 0,
    val expectedBytes: Long? = null,
    val results: List<LocalAudioImportResult> = emptyList(),
    val cancelled: Boolean = false,
)

/** Blocking transactional storage. The application facade dispatches every call to IO. */
interface LocalAudioStore : AutoCloseable {
    fun read(): LocalAudioLibraryState
    fun createFolder(name: String): LocalAudioFolder
    fun renameFolder(id: String, name: String)
    fun setContinuePlayback(id: String, enabled: Boolean)
    /** Deletes membership/cursor only; imported audio remains in the library. */
    fun deleteFolder(id: String)
    /** Deduplicate by (mode, source.identity); preserve user names/progress/bookmarks. */
    fun importAudio(folderId: String, metadata: LocalAudioMetadata, source: LocalAudioSource,
        directory: LocalAudioDirectoryImport? = null): LocalAudioItem
    fun source(audioId: String): StoredLocalAudio?
    fun renameAudio(audioId: String, name: String?)
    fun addToFolder(folderId: String, audioId: String)
    fun removeFromFolder(folderId: String, audioId: String)
    /** All membership edits are atomic and never remove audio or user progress. */
    fun organize(audioIds: List<String>, action: LocalAudioBatchAction, folderId: String,
        destinationFolderId: String? = null, expectedRevision: Long, checkCancelled: () -> Unit = {}): LocalAudioBatchResult
    /** Sort the entire folder, not a filtered search page. */
    fun sortFolder(folderId: String, sort: LocalAudioSort, descending: Boolean,
        expectedRevision: Long, checkCancelled: () -> Unit = {}): LocalAudioSortResult
    /** Must contain exactly the current members, each once. */
    fun reorder(folderId: String, audioIds: List<String>)
    /** Explicit whole-library removal; caller may collect the returned owned resource. */
    fun removeAudio(audioId: String): LocalAudioSource
    /** Reference files only. Keep audio ID and all user data; reject an already-linked source. */
    fun relink(audioId: String, metadata: LocalAudioMetadata, source: LocalAudioSource): LocalAudioSource
    fun setAvailability(audioId: String, availability: LocalAudioAvailability)
    /**
     * The automatically extracted cover only: advances the ordinary revision but never touches the
     * source version fields, and never replaces the user's own cover.
     */
    fun setArtwork(audioId: String, artworkKey: String?)
    /** Rejects anything but [LocalAudioLimits.requirePlaybackSpeed] values; same transaction rules. */
    fun setPlaybackSpeed(audioId: String, speed: Float)
    /** The user's cover; null clears it and restores the automatic one instead of deleting it. */
    fun setCustomArtwork(audioId: String, artworkKey: String?)
    /** Keys the caller must keep in the artwork cache; the default reads the snapshot. */
    fun retainedArtworkKeys(): Set<String> = read().items.flatMap {
        listOfNotNull(it.customArtworkKey, it.metadata.artworkKey)
    }.toSet()
    fun saveProgress(folderId: String?, value: LocalAudioProgress)
    fun addBookmark(audioId: String, positionMs: Long, name: String): LocalAudioBookmark
    fun removeBookmark(id: String)
    fun renameBookmark(id: String, name: String)
    fun retainedGrantUris(): Set<String>
    fun retainedCopyNames(): Set<String>
    fun previewOrganization(backup: LocalAudioBackup): LocalAudioRestorePreview
    /** Merge organization onto matching existing audio; never grants access or deletes current rows. */
    fun restoreOrganization(backup: LocalAudioBackup, expectedRevision: Long,
        checkCancelled: () -> Unit = {}): LocalAudioRestoreResult
    fun linkDirectory(folderId: String, treeUri: String, name: String, mode: LocalImportMode,
        includeSubdirectories: Boolean): LocalAudioDirectoryLink
    fun directory(directoryId: String): StoredLocalAudioDirectory?
    fun directoryEntry(directoryId: String, documentIdentity: String): LocalAudioDirectoryEntry?
    /** Removes association/provenance only, leaving imported audio and memberships intact. */
    fun unlinkDirectory(directoryId: String)
    fun setDirectoryError(directoryId: String, error: LocalAudioError?)
    /** Call only after a complete successful listing; a failed/cancelled listing never marks missing. */
    fun finishDirectoryRefresh(directoryId: String, seenIdentities: Set<String>, refreshedAtMs: Long,
        error: LocalAudioError? = null): Int
}
