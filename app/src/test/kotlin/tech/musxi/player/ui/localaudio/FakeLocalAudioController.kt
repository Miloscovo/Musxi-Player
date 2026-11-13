package tech.musxi.player.ui.localaudio

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import tech.musxi.core.localaudio.LocalAudioBatchAction
import tech.musxi.core.localaudio.LocalAudioBatchResult
import tech.musxi.core.localaudio.LocalAudioDirectoryLink
import tech.musxi.core.localaudio.LocalAudioDirectoryRefreshResult
import tech.musxi.core.localaudio.LocalAudioError
import tech.musxi.core.localaudio.LocalAudioFailure
import tech.musxi.core.localaudio.LocalAudioFolder
import tech.musxi.core.localaudio.LocalAudioImportResult
import tech.musxi.core.localaudio.LocalAudioImportState
import tech.musxi.core.localaudio.LocalAudioItem
import tech.musxi.core.localaudio.LocalAudioLibraryState
import tech.musxi.core.localaudio.LocalAudioMember
import tech.musxi.core.localaudio.LocalAudioMetadata
import tech.musxi.core.localaudio.LocalAudioProgress
import tech.musxi.core.localaudio.LocalAudioSourceCheckResult
import tech.musxi.core.localaudio.LocalAudioSourceCheckState
import tech.musxi.core.localaudio.LocalImportMode
import tech.musxi.player.localaudio.LocalAudioRestoreState

/** Ids the backend would accept: they must be UUID-shaped. */
internal fun localId(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

internal fun localItem(n: Int, title: String, creator: String? = null, durationMs: Long? = 3_600_000,
    mode: LocalImportMode = LocalImportMode.COPY) = LocalAudioItem(
    id = localId(n),
    metadata = LocalAudioMetadata(fileName = "$title.mp3", title = title, creator = creator, durationMs = durationMs),
    mode = mode,
    addedAtMs = n.toLong(),
)

/**
 * A library with two folders: 睡前音声 (three recordings, one half-heard) and 有声书 (empty), plus
 * one recording in no folder.
 */
internal fun sampleLocalLibrary(revision: Long = 7) = LocalAudioLibraryState(
    ready = true,
    revision = revision,
    folders = listOf(LocalAudioFolder(localId(100), "睡前音声"), LocalAudioFolder(localId(101), "有声书")),
    items = listOf(
        localItem(1, "第一夜 · 海边的风", "某某社团"),
        localItem(2, "第二夜 · 壁炉", "某某社团", mode = LocalImportMode.REFERENCE),
        localItem(3, "第三夜 · 雨声与低语", "某某社团"),
        localItem(4, "散落的录音"),
    ),
    members = listOf(
        LocalAudioMember(localId(100), localId(1), 0),
        LocalAudioMember(localId(100), localId(2), 1),
        LocalAudioMember(localId(100), localId(3), 2),
    ),
    progress = listOf(LocalAudioProgress(localId(2), 1_398_000, completed = false, updatedAtMs = 1)),
    directories = listOf(LocalAudioDirectoryLink(localId(200), localId(100), "睡前音声", LocalImportMode.COPY, lastRefreshedAtMs = 1)),
)

/** Records every command; each answers from [answer] or with success. */
internal class FakeLocalAudioController(state: LocalAudioLibraryState = sampleLocalLibrary()) : LocalAudioController {
    override val library = MutableStateFlow(state)
    override val imports = MutableStateFlow(LocalAudioImportState())
    override val sourceChecks = MutableStateFlow(LocalAudioSourceCheckState())
    override val restoreState = MutableStateFlow(LocalAudioRestoreState())
    override val connected = MutableStateFlow(true)

    val calls = mutableListOf<String>()
    data class Play(val folderId: String?, val audioId: String?, val fromStart: Boolean)
    val plays = mutableListOf<Play>()
    data class Batch(val action: String, val folderId: String, val ids: List<String>, val revision: Long)
    val batches = mutableListOf<Batch>()
    /** Errors to answer the next batch commands with, in order; empty means success. */
    val batchErrors = ArrayDeque<LocalAudioError>()
    /** Called before a batch answers, e.g. to move the revision as a progress save would. */
    var beforeBatchAnswer: () -> Unit = {}

    private fun fail(error: LocalAudioError) = Result.failure<Nothing>(LocalAudioFailure(error))

    override fun createFolder(name: String, result: (Result<LocalAudioFolder>) -> Unit) {
        calls += "createFolder:$name"
        val folder = LocalAudioFolder(localId(150 + library.value.folders.size), name)
        library.value = library.value.copy(folders = library.value.folders + folder, revision = library.value.revision + 1)
        result(Result.success(folder))
    }
    override fun renameFolder(id: String, name: String, result: (Result<Unit>) -> Unit) { calls += "renameFolder:$name"; result(Result.success(Unit)) }
    override fun setContinuePlayback(id: String, enabled: Boolean, result: (Result<Unit>) -> Unit) {
        calls += "setContinuePlayback:$enabled"; result(Result.success(Unit))
    }
    override fun deleteFolder(id: String, result: (Result<Unit>) -> Unit) { calls += "deleteFolder"; result(Result.success(Unit)) }
    override fun renameAudio(id: String, name: String?, result: (Result<Unit>) -> Unit) { calls += "renameAudio:$name"; result(Result.success(Unit)) }
    override fun addToFolder(folderId: String, audioId: String, result: (Result<Unit>) -> Unit) { calls += "addToFolder"; result(Result.success(Unit)) }
    override fun removeFromFolder(folderId: String, audioId: String, result: (Result<Unit>) -> Unit) {
        calls += "removeFromFolder"; result(Result.success(Unit))
    }
    override fun deleteAudio(audioId: String, result: (Result<Unit>) -> Unit) { calls += "deleteAudio:$audioId"; result(Result.success(Unit)) }

    private fun batch(action: String, folderId: String, ids: List<String>, revision: Long, kind: LocalAudioBatchAction,
        result: (Result<LocalAudioBatchResult>) -> Unit) {
        batches += Batch(action, folderId, ids, revision)
        beforeBatchAnswer()
        val error = batchErrors.removeFirstOrNull()
        if (error != null) result(fail(error))
        else result(Result.success(LocalAudioBatchResult(kind, ids.size, ids.size, if (kind == LocalAudioBatchAction.ADD) 0 else ids.size)))
    }
    override fun addToFolderBatch(folderId: String, audioIds: List<String>, expectedRevision: Long,
        result: (Result<LocalAudioBatchResult>) -> Unit) = batch("add", folderId, audioIds, expectedRevision, LocalAudioBatchAction.ADD, result)
    override fun removeFromFolderBatch(folderId: String, audioIds: List<String>, expectedRevision: Long,
        result: (Result<LocalAudioBatchResult>) -> Unit) = batch("remove", folderId, audioIds, expectedRevision, LocalAudioBatchAction.REMOVE, result)
    override fun moveToFolderBatch(sourceFolderId: String, destinationFolderId: String, audioIds: List<String>,
        expectedRevision: Long, result: (Result<LocalAudioBatchResult>) -> Unit) =
        batch("move", destinationFolderId, audioIds, expectedRevision, LocalAudioBatchAction.MOVE, result)
    override fun checkSources(audioIds: List<String>, expectedRevision: Long,
        result: (Result<List<LocalAudioSourceCheckResult>>) -> Unit) {
        calls += "checkSources:${audioIds.size}@$expectedRevision"
        result(Result.success(audioIds.map { LocalAudioSourceCheckResult(it, tech.musxi.core.localaudio.LocalAudioAvailability.AVAILABLE, null, true) }))
    }
    override fun importFiles(folderId: String, uris: List<Uri>, mode: LocalImportMode, grantedFlags: Int,
        result: (Result<List<LocalAudioImportResult>>) -> Unit) { calls += "importFiles:$mode" }
    override fun importDirectory(folderId: String, tree: Uri, mode: LocalImportMode, grantedFlags: Int,
        result: (Result<List<LocalAudioImportResult>>) -> Unit) { calls += "importDirectory:$mode" }
    override fun cancelImport() { calls += "cancelImport" }
    override fun linkDirectory(folderId: String, tree: Uri, name: String, mode: LocalImportMode, grantedFlags: Int,
        result: (Result<LocalAudioDirectoryLink>) -> Unit) { calls += "linkDirectory:$mode" }
    override fun refreshDirectory(id: String, result: (Result<LocalAudioDirectoryRefreshResult>) -> Unit) { calls += "refreshDirectory" }
    override fun reauthorizeDirectory(id: String, tree: Uri, grantedFlags: Int, result: (Result<Unit>) -> Unit) { calls += "reauthorize" }
    override fun unlinkDirectory(id: String, result: (Result<Unit>) -> Unit) { calls += "unlink"; result(Result.success(Unit)) }
    override fun relink(audioId: String, uri: Uri, grantedFlags: Int, result: (Result<Unit>) -> Unit) { calls += "relink" }
    override fun play(folderId: String?, audioId: String?, fromStart: Boolean, result: (Result<Unit>) -> Unit) {
        plays += Play(folderId, audioId, fromStart)
        result(Result.success(Unit))
    }
    override fun restoreLastQueue(result: (Result<Unit>) -> Unit) { calls += "restoreLastQueue"; result(Result.success(Unit)) }
}
