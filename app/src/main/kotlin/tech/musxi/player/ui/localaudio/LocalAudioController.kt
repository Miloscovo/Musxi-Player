package tech.musxi.player.ui.localaudio

import android.net.Uri
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tech.musxi.core.localaudio.LocalAudioBatchResult
import tech.musxi.core.localaudio.LocalAudioDirectoryLink
import tech.musxi.core.localaudio.LocalAudioDirectoryRefreshResult
import tech.musxi.core.localaudio.LocalAudioError
import tech.musxi.core.localaudio.LocalAudioFailure
import tech.musxi.core.localaudio.LocalAudioFolder
import tech.musxi.core.localaudio.LocalAudioImportResult
import tech.musxi.core.localaudio.LocalAudioImportState
import tech.musxi.core.localaudio.LocalAudioLibraryState
import tech.musxi.core.localaudio.LocalAudioLimits
import tech.musxi.core.localaudio.LocalAudioSourceCheckResult
import tech.musxi.core.localaudio.LocalAudioSourceCheckState
import tech.musxi.core.localaudio.LocalImportMode
import tech.musxi.player.MusxiApplication
import tech.musxi.player.localaudio.LocalArtworkCrop
import tech.musxi.player.localaudio.LocalAudioRestoreState

/**
 * What the 本地音频 pages need from the app: the library and its two running jobs as state, and the
 * commands that change them. The pages depend on this and nothing else, so a test can hand them a
 * fake and check which command a tap sends.
 *
 * Every command is called on the main thread and calls back on the main thread, as the repository
 * and the playback adapter already do. Short commands are deliberately not cancelled when a page
 * leaves: their transaction may already be committed, and cancelling only swallows the callback.
 */
interface LocalAudioController {
    val library: StateFlow<LocalAudioLibraryState>
    val imports: StateFlow<LocalAudioImportState>
    val sourceChecks: StateFlow<LocalAudioSourceCheckState>
    val restoreState: StateFlow<LocalAudioRestoreState>
    /** The playback service is bound; local play and restore wait for it. */
    val connected: StateFlow<Boolean>

    fun createFolder(name: String, result: (Result<LocalAudioFolder>) -> Unit)
    fun renameFolder(id: String, name: String, result: (Result<Unit>) -> Unit)
    fun setContinuePlayback(id: String, enabled: Boolean, result: (Result<Unit>) -> Unit)
    fun deleteFolder(id: String, result: (Result<Unit>) -> Unit)

    /** A null [name] puts the tag title or file name back. */
    fun renameAudio(id: String, name: String?, result: (Result<Unit>) -> Unit)
    fun addToFolder(folderId: String, audioId: String, result: (Result<Unit>) -> Unit)
    fun removeFromFolder(folderId: String, audioId: String, result: (Result<Unit>) -> Unit)
    fun deleteAudio(audioId: String, result: (Result<Unit>) -> Unit)

    fun addToFolderBatch(folderId: String, audioIds: List<String>, expectedRevision: Long, result: (Result<LocalAudioBatchResult>) -> Unit)
    fun removeFromFolderBatch(folderId: String, audioIds: List<String>, expectedRevision: Long, result: (Result<LocalAudioBatchResult>) -> Unit)
    fun moveToFolderBatch(sourceFolderId: String, destinationFolderId: String, audioIds: List<String>, expectedRevision: Long,
        result: (Result<LocalAudioBatchResult>) -> Unit)
    fun checkSources(audioIds: List<String>, expectedRevision: Long, result: (Result<List<LocalAudioSourceCheckResult>>) -> Unit)

    fun importFiles(folderId: String, uris: List<Uri>, mode: LocalImportMode, grantedFlags: Int,
        result: (Result<List<LocalAudioImportResult>>) -> Unit)
    fun importDirectory(folderId: String, tree: Uri, mode: LocalImportMode, grantedFlags: Int,
        result: (Result<List<LocalAudioImportResult>>) -> Unit)
    fun cancelImport()
    fun linkDirectory(folderId: String, tree: Uri, name: String, mode: LocalImportMode, grantedFlags: Int,
        result: (Result<LocalAudioDirectoryLink>) -> Unit)
    fun refreshDirectory(id: String, result: (Result<LocalAudioDirectoryRefreshResult>) -> Unit)
    fun reauthorizeDirectory(id: String, tree: Uri, grantedFlags: Int, result: (Result<Unit>) -> Unit)
    fun unlinkDirectory(id: String, result: (Result<Unit>) -> Unit)
    fun relink(audioId: String, uri: Uri, grantedFlags: Int, result: (Result<Unit>) -> Unit)

    /** `play(folder)` continues, `play(folder, id)` starts at one row, `play(null, id)` plays one recording. */
    fun play(folderId: String?, audioId: String?, fromStart: Boolean, result: (Result<Unit>) -> Unit)
    fun restoreLastQueue(result: (Result<Unit>) -> Unit)

    /** The image file behind a row's artwork key, or null when there is none (or it is gone). */
    fun artworkFile(key: String): java.io.File? = null

    /**
     * False when 我的 → 外观 → 本地封面 is 不读取: covers read from the files are hidden, a picture the
     * user chose still shows. Rows are rebuilt when it changes.
     */
    val autoArtwork: StateFlow<Boolean> get() = AlwaysAutoArtwork

    /**
     * Uses the picked image as this recording's cover; the image grant is only used during the call.
     * [crop] is the part of the EXIF-upright picture to keep, or null for the whole picture.
     */
    fun setCustomArtwork(audioId: String, image: Uri, grantedFlags: Int, crop: LocalArtworkCrop? = null,
        result: (Result<Unit>) -> Unit) {
        result(Result.failure(UnsupportedOperationException()))
    }
    /** Drops the user's picture; the cover read from the file (if any, and if shown) comes back. */
    fun clearCustomArtwork(audioId: String, result: (Result<Unit>) -> Unit) {
        result(Result.failure(UnsupportedOperationException()))
    }
}

private val AlwaysAutoArtwork: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(true)

/** The real one: the app's local library, its playback adapter and the playback connection. */
class AppLocalAudioController(private val app: MusxiApplication) : LocalAudioController {
    private val repo get() = app.localAudio
    private val playback get() = app.localAudioPlayback

    override val library: StateFlow<LocalAudioLibraryState> get() = repo.state
    override val imports: StateFlow<LocalAudioImportState> get() = repo.imports
    override val sourceChecks: StateFlow<LocalAudioSourceCheckState> get() = repo.sourceChecks
    override val restoreState: StateFlow<LocalAudioRestoreState> get() = playback.restoreState
    override val connected: StateFlow<Boolean> = MappedStateFlow(app.playback.state) { it.connected }

    override fun createFolder(name: String, result: (Result<LocalAudioFolder>) -> Unit) { repo.createFolder(name, result) }
    override fun renameFolder(id: String, name: String, result: (Result<Unit>) -> Unit) { repo.renameFolder(id, name, result) }
    override fun setContinuePlayback(id: String, enabled: Boolean, result: (Result<Unit>) -> Unit) {
        repo.setContinuePlayback(id, enabled, result)
    }
    override fun deleteFolder(id: String, result: (Result<Unit>) -> Unit) { repo.deleteFolder(id, result) }
    override fun renameAudio(id: String, name: String?, result: (Result<Unit>) -> Unit) { repo.renameAudio(id, name, result) }
    override fun addToFolder(folderId: String, audioId: String, result: (Result<Unit>) -> Unit) {
        repo.addToFolder(folderId, audioId, result)
    }
    override fun removeFromFolder(folderId: String, audioId: String, result: (Result<Unit>) -> Unit) {
        repo.removeFromFolder(folderId, audioId, result)
    }
    override fun deleteAudio(audioId: String, result: (Result<Unit>) -> Unit) { repo.deleteAudio(audioId, result) }
    override fun addToFolderBatch(folderId: String, audioIds: List<String>, expectedRevision: Long,
        result: (Result<LocalAudioBatchResult>) -> Unit) { repo.addToFolderBatch(folderId, audioIds, expectedRevision, result) }
    override fun removeFromFolderBatch(folderId: String, audioIds: List<String>, expectedRevision: Long,
        result: (Result<LocalAudioBatchResult>) -> Unit) { repo.removeFromFolderBatch(folderId, audioIds, expectedRevision, result) }
    override fun moveToFolderBatch(sourceFolderId: String, destinationFolderId: String, audioIds: List<String>,
        expectedRevision: Long, result: (Result<LocalAudioBatchResult>) -> Unit) {
        repo.moveToFolderBatch(sourceFolderId, destinationFolderId, audioIds, expectedRevision, result)
    }
    override fun checkSources(audioIds: List<String>, expectedRevision: Long,
        result: (Result<List<LocalAudioSourceCheckResult>>) -> Unit) { repo.checkSources(audioIds, expectedRevision, result) }
    override fun importFiles(folderId: String, uris: List<Uri>, mode: LocalImportMode, grantedFlags: Int,
        result: (Result<List<LocalAudioImportResult>>) -> Unit) { repo.importFiles(folderId, uris, mode, grantedFlags, result) }
    override fun importDirectory(folderId: String, tree: Uri, mode: LocalImportMode, grantedFlags: Int,
        result: (Result<List<LocalAudioImportResult>>) -> Unit) {
        repo.importDirectory(folderId, tree, mode, grantedFlags, includeSubdirectories = false, result = result)
    }
    override fun cancelImport() = repo.cancelImport()
    override fun linkDirectory(folderId: String, tree: Uri, name: String, mode: LocalImportMode, grantedFlags: Int,
        result: (Result<LocalAudioDirectoryLink>) -> Unit) {
        repo.linkDirectory(folderId, tree, name, mode, grantedFlags, includeSubdirectories = false, result = result)
    }
    override fun refreshDirectory(id: String, result: (Result<LocalAudioDirectoryRefreshResult>) -> Unit) {
        repo.refreshDirectory(id, result)
    }
    override fun reauthorizeDirectory(id: String, tree: Uri, grantedFlags: Int, result: (Result<Unit>) -> Unit) {
        repo.reauthorizeDirectory(id, tree, grantedFlags, result)
    }
    override fun unlinkDirectory(id: String, result: (Result<Unit>) -> Unit) { repo.unlinkDirectory(id, result) }
    override fun relink(audioId: String, uri: Uri, grantedFlags: Int, result: (Result<Unit>) -> Unit) {
        repo.relink(audioId, uri, grantedFlags, result)
    }
    override fun play(folderId: String?, audioId: String?, fromStart: Boolean, result: (Result<Unit>) -> Unit) {
        playback.play(folderId, audioId, fromStart, playWhenReady = true, callback = result)
    }
    override fun restoreLastQueue(result: (Result<Unit>) -> Unit) { playback.restoreLastQueue(result) }
    override fun artworkFile(key: String): java.io.File? = repo.artworkFile(key)
    override val autoArtwork: StateFlow<Boolean> = MappedStateFlow(app.settings) { it.localArtworkMode != LOCAL_ARTWORK_OFF }
    override fun setCustomArtwork(audioId: String, image: Uri, grantedFlags: Int, crop: LocalArtworkCrop?,
        result: (Result<Unit>) -> Unit) {
        repo.setCustomArtwork(audioId, image, grantedFlags, crop, result)
    }
    override fun clearCustomArtwork(audioId: String, result: (Result<Unit>) -> Unit) { repo.clearCustomArtwork(audioId, result) }
}

/** The two stored values of `AppSettings.localArtworkMode`. */
const val LOCAL_ARTWORK_AUTO = "AUTO"
const val LOCAL_ARTWORK_OFF = "OFF"

/**
 * A read-only view of one field of another StateFlow, without a coroutine scope of its own: the
 * controller is remembered by a composable, and a `stateIn` scope there would outlive it.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class MappedStateFlow<T, R>(private val source: StateFlow<T>, private val transform: (T) -> R) : StateFlow<R> {
    override val value: R get() = transform(source.value)
    override val replayCache: List<R> get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        source.map(transform).distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}

/** The backend's error for a failed result, or null for one that is not a library failure. */
internal fun Result<*>.localError(): LocalAudioError? = (exceptionOrNull() as? LocalAudioFailure)?.error

/**
 * Whether [ids] can still be acted on in [state]: every recording still exists, every one is still
 * in [memberOf] when that is given, and every folder named in [folders] still exists. Checked with
 * the latest state at the moment the user confirms, because the selection was made on an older one.
 */
internal fun selectionStillValid(
    state: LocalAudioLibraryState,
    ids: List<String>,
    memberOf: String? = null,
    folders: List<String> = emptyList(),
): Boolean {
    if (!state.ready || ids.isEmpty()) return false
    val items = state.items.mapTo(HashSet()) { it.id }
    if (ids.any { it !in items }) return false
    val folderIds = state.folders.mapTo(HashSet()) { it.id }
    if ((folders + listOfNotNull(memberOf)).any { it !in folderIds }) return false
    if (memberOf != null) {
        val members = state.members.filter { it.folderId == memberOf }.mapTo(HashSet()) { it.audioId }
        if (ids.any { it !in members }) return false
    }
    return true
}

/**
 * Runs a revision-checked organizing command the way the backend asks until it splits the
 * organizing and progress revisions: read the newest revision when the user confirms, check that the
 * picked recordings and folders still fit, and on STALE_STATE check again and retry once only.
 *
 * Playing a local recording saves progress about every 15 seconds and each save moves the single
 * revision, so a first STALE_STATE is ordinary. A second one means the library really is changing
 * under the user; looping would risk overriding a concurrent edit, so it is reported instead.
 */
internal fun <T> LocalAudioController.withFreshRevision(
    stillValid: (LocalAudioLibraryState) -> Boolean,
    command: (revision: Long, result: (Result<T>) -> Unit) -> Unit,
    /**
     * Runs the retry. The source check answers STALE_STATE from inside its own still-running job, and
     * a second check started there is refused as busy; it passes a post to the main thread instead.
     * Placed before [result] so callers can keep passing the result as a trailing lambda.
     */
    retry: (() -> Unit) -> Unit = { it() },
    result: (Result<T>) -> Unit,
) {
    val first = library.value
    if (!stillValid(first)) {
        result(Result.failure(SelectionChanged()))
        return
    }
    command(first.revision) { response ->
        if (response.localError() != LocalAudioError.STALE_STATE) {
            result(response)
            return@command
        }
        retry {
            val latest = library.value
            if (!stillValid(latest)) result(Result.failure(SelectionChanged())) else command(latest.revision, result)
        }
    }
}

/**
 * The picked recordings no longer fit when the user confirmed: some were deleted or moved out
 * meanwhile. Told apart from STALE_STATE, which only means "try again".
 */
internal class SelectionChanged : RuntimeException("selection changed")

/** Words for an organizing failure: a changed selection, a busy library, or the backend's reason. */
internal fun organizeFailureText(result: Result<*>, busy: Boolean, limitText: String? = null): String =
    if (result.exceptionOrNull() is SelectionChanged) "部分音频已不在此处，请重新选择"
    else localErrorText(result.localError(), busy, limitText)

/** Posts to the main thread: a retry that must wait for the call it answers to finish. */
internal val postToMain: (() -> Unit) -> Unit = { block -> android.os.Handler(android.os.Looper.getMainLooper()).post(block) }

/** The backend takes at most this many recordings in one organizing or checking batch. */
internal const val MAX_LOCAL_BATCH = LocalAudioLimits.MAX_BATCH
