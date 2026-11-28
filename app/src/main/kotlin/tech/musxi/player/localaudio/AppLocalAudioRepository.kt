package tech.musxi.player.localaudio

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.musxi.core.localaudio.*
import tech.musxi.core.settings.AppSettings
import tech.musxi.playback.LocalAudioPlaybackQueue
import tech.musxi.playback.LocalAudioQueueEntry
import tech.musxi.playback.QueueSources

/** Durable, account-independent local library. All disk/document access is serialized on IO. */
class AppLocalAudioRepository internal constructor(
    private val store: LocalAudioStore,
    private val files: LocalAudioFiles,
    private val documents: LocalAudioDocuments,
    private val scope: CoroutineScope,
    private val transferDocuments: LocalAudioTransferDocuments? = null,
    private val artwork: LocalAudioArtwork? = null,
    private val settings: StateFlow<AppSettings> = MutableStateFlow(AppSettings()),
) {
    constructor(context: Context, settings: StateFlow<AppSettings> = MutableStateFlow(AppSettings())) : this(
        SqliteLocalAudioStore(context.applicationContext),
        LocalAudioFiles(File(context.applicationContext.filesDir, "local-audio-v1")),
        AndroidLocalAudioDocuments(context.applicationContext),
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        AndroidLocalAudioTransfers(context.applicationContext),
        AndroidLocalAudioArtwork(context.applicationContext),
        settings,
    )

    private val commands = Mutex()
    /** Only one importer runs, while short library transactions remain available during copying. */
    private val importGate = Mutex()
    private val importingCopies = mutableSetOf<String>()
    private val importingGrants = mutableSetOf<String>()
    private val transferringCopies = mutableSetOf<String>()
    private val transferringGrants = mutableSetOf<String>()
    private val mutable = MutableStateFlow(LocalAudioLibraryState())
    val state = mutable.asStateFlow()
    /** Read-only CUE inspection has its own lifecycle and never mutates the library. */
    val cuePreview by lazy { CuePreviewRepository(documents, scope) }
    /** DSF recognition is read-only; decoder acceptance is a separate, pending stage. */
    val dsfPreview by lazy { DsfPreviewRepository(documents, scope) }
    /** Visibility changes never start extraction or release stored artwork references. */
    val artworkMode = settings.map { it.localArtworkMode }.distinctUntilChanged()
    private fun autoArtworkEnabled() = settings.value.localArtworkMode != "OFF"
    private val importing = MutableStateFlow(LocalAudioImportState())
    val imports = importing.asStateFlow()
    private var importJob: Job? = null
    private var importRunId = 0L
    private var transferJob: Job? = null
    private val transferring = MutableStateFlow(LocalAudioTransferState())
    val transfers = transferring.asStateFlow()
    private var searchJob: Job? = null
    private var searchEpoch = 0L
    private val searching = MutableStateFlow(LocalAudioSearchState())
    val searchState = searching.asStateFlow()
    private val sourceProbe = LocalAudioSourceProbe(files, documents)
    private var sourceCheckJob: Job? = null
    private var sourceCheckRunId = 0L
    private val checkingSources = MutableStateFlow(LocalAudioSourceCheckState())
    val sourceChecks = checkingSources.asStateFlow()
    private var artworkJob: Job? = null
    private var customArtworkEpoch = 0L
    private val customArtworkEpochs = mutableMapOf<String, Long>() // guarded by commands
    private val fillingArtwork = MutableStateFlow(LocalAudioArtworkState())
    val artworkFill = fillingArtwork.asStateFlow()
    // Source observations are ordered independently of progress/name/membership revisions.
    private var sourceEpoch = 0L
    private val sourceEpochs = mutableMapOf<String, Long>() // guarded by commands
    private val sourceObservations = mutableMapOf<String, Long>() // guarded by commands
    private val authorizationEpochs = mutableMapOf<String, Long>() // directory grant reauthorization, guarded by commands
    private data class SourceInspection(val stored: StoredLocalAudio, val epoch: Long, val observation: Long, val authorization: Long)
    private data class PendingOrganization(val backup: LocalAudioBackup, val revision: Long, val version: LocalAudioVersionToken?)
    private var pendingOrganization: PendingOrganization? = null
    private val directoryEpochs = mutableMapOf<String, Long>() // guarded by commands
    private data class DirectoryRefresh(val stored: StoredLocalAudioDirectory, val epoch: Long)
    private val initialization = scope.launch {
        withContext(Dispatchers.IO) {
            commands.withLock {
                try {
                    publish()
                    files.cleanupAbandoned()
                    files.prune(store.retainedCopyNames())
                    pruneArtwork()
                    documents.releaseUnused(store.retainedGrantUris())
                } catch (error: Exception) {
                    mutable.value = mutable.value.copy(error = failure(error).error)
                }
            }
        }
    }

    fun createFolder(name: String, result: (Result<LocalAudioFolder>) -> Unit = {}): Job = submit(result) {
        store.createFolder(name.trim())
    }
    fun renameFolder(id: String, name: String, result: (Result<Unit>) -> Unit = {}): Job = submit(result) {
        store.renameFolder(id, name.trim())
    }
    fun setContinuePlayback(id: String, enabled: Boolean, result: (Result<Unit>) -> Unit = {}): Job = submit(result) {
        store.setContinuePlayback(id, enabled)
    }
    fun deleteFolder(id: String, result: (Result<Unit>) -> Unit = {}): Job = submit(result) {
        store.deleteFolder(id)
        releaseUnusedGrants()
    }
    fun renameAudio(id: String, name: String?, result: (Result<Unit>) -> Unit = {}): Job = submit(result) {
        store.renameAudio(id, name?.trim())
    }
    fun addToFolder(folderId: String, audioId: String, result: (Result<Unit>) -> Unit = {}): Job = submit(result) {
        store.addToFolder(folderId, audioId)
    }
    fun removeFromFolder(folderId: String, audioId: String, result: (Result<Unit>) -> Unit = {}): Job = submit(result) {
        store.removeFromFolder(folderId, audioId)
    }

    fun addToFolderBatch(folderId: String, audioIds: List<String>, expectedRevision: Long,
        result: (Result<LocalAudioBatchResult>) -> Unit = {}): Job =
        organizeBatch(audioIds, LocalAudioBatchAction.ADD, folderId, null, expectedRevision, result)

    fun removeFromFolderBatch(folderId: String, audioIds: List<String>, expectedRevision: Long,
        result: (Result<LocalAudioBatchResult>) -> Unit = {}): Job =
        organizeBatch(audioIds, LocalAudioBatchAction.REMOVE, folderId, null, expectedRevision, result)

    fun moveToFolderBatch(sourceFolderId: String, destinationFolderId: String, audioIds: List<String>, expectedRevision: Long,
        result: (Result<LocalAudioBatchResult>) -> Unit = {}): Job =
        organizeBatch(audioIds, LocalAudioBatchAction.MOVE, sourceFolderId, destinationFolderId, expectedRevision, result)

    private fun organizeBatch(audioIds: List<String>, action: LocalAudioBatchAction, folderId: String,
        destinationFolderId: String?, expectedRevision: Long, result: (Result<LocalAudioBatchResult>) -> Unit): Job {
        val frozen = audioIds.toList()
        return submit(result) {
            val context = currentCoroutineContext()
            store.organize(frozen, action, folderId, destinationFolderId, expectedRevision) { context.ensureActive() }
        }
    }

    fun sortFolder(folderId: String, sort: LocalAudioSort, descending: Boolean = false, expectedRevision: Long,
        result: (Result<LocalAudioSortResult>) -> Unit = {}): Job = submit(result) {
        val context = currentCoroutineContext()
        store.sortFolder(folderId, sort, descending, expectedRevision) { context.ensureActive() }
    }

    fun addToFolderBatch(folderId: String, audioIds: List<String>, expectedVersion: LocalAudioVersionToken,
        result: (Result<LocalAudioBatchResult>) -> Unit = {}): Job =
        organizeVersioned(audioIds, LocalAudioBatchAction.ADD, folderId, null, expectedVersion, result)
    fun removeFromFolderBatch(folderId: String, audioIds: List<String>, expectedVersion: LocalAudioVersionToken,
        result: (Result<LocalAudioBatchResult>) -> Unit = {}): Job =
        organizeVersioned(audioIds, LocalAudioBatchAction.REMOVE, folderId, null, expectedVersion, result)
    fun moveToFolderBatch(sourceFolderId: String, destinationFolderId: String, audioIds: List<String>,
        expectedVersion: LocalAudioVersionToken, result: (Result<LocalAudioBatchResult>) -> Unit = {}): Job =
        organizeVersioned(audioIds, LocalAudioBatchAction.MOVE, sourceFolderId, destinationFolderId, expectedVersion, result)
    private fun organizeVersioned(audioIds: List<String>, action: LocalAudioBatchAction, folderId: String,
        destination: String?, version: LocalAudioVersionToken, result: (Result<LocalAudioBatchResult>) -> Unit): Job {
        val frozen = audioIds.toList()
        return submit(result) {
            val context = currentCoroutineContext()
            store.organize(frozen, action, folderId, destination, version) { context.ensureActive() }
        }
    }
    fun sortFolder(folderId: String, sort: LocalAudioSort, descending: Boolean = false,
        expectedVersion: LocalAudioVersionToken, result: (Result<LocalAudioSortResult>) -> Unit = {}): Job = submit(result) {
        val context = currentCoroutineContext()
        store.sortFolder(folderId, sort, descending, expectedVersion) { context.ensureActive() }
    }
    fun reorder(folderId: String, audioIds: List<String>, expectedVersion: LocalAudioVersionToken,
        result: (Result<Unit>) -> Unit = {}): Job {
        val frozen = audioIds.toList()
        return submit(result) { store.reorder(folderId, frozen, expectedVersion) }
    }

    /** A metadata-only query. A newer query cancels the previous request and owns the result. */
    fun search(request: LocalAudioQuery, result: (Result<LocalAudioSearchPage>) -> Unit = {}): Job {
        val epoch = ++searchEpoch
        searchJob?.cancel()
        return scope.launch {
            initialization.join()
            searching.value = LocalAudioSearchState(loading = true)
            try {
                val page = withContext(Dispatchers.IO) {
                    val snapshot = commands.withLock { requireReady(); store.read() }
                    val context = currentCoroutineContext()
                    val found = LocalAudioSearch.query(snapshot, request) { context.ensureActive() }
                    commands.withLock {
                        context.ensureActive()
                        if (epoch != searchEpoch || !request.isCurrent(state.value, found)) {
                            throw LocalAudioFailure(LocalAudioError.STALE_STATE)
                        }
                    }
                    found
                }
                currentCoroutineContext().ensureActive()
                val published = commands.withLock {
                    currentCoroutineContext().ensureActive()
                    if (epoch != searchEpoch) false else {
                        if (!request.isCurrent(state.value, page)) throw LocalAudioFailure(LocalAudioError.STALE_STATE)
                        searching.value = LocalAudioSearchState(page = page)
                        true
                    }
                }
                if (published) result(Result.success(page))
            } catch (error: CancellationException) {
                if (epoch == searchEpoch) searching.value = LocalAudioSearchState()
                throw error
            } catch (error: Exception) {
                val reason = failure(error)
                if (epoch == searchEpoch) {
                    searching.value = LocalAudioSearchState(error = reason.error)
                    result(Result.failure(reason))
                }
            }
        }.also { searchJob = it }
    }

    fun clearSearch() {
        searchEpoch++
        searchJob?.cancel()
        searching.value = LocalAudioSearchState()
    }

    /** Explicit selected-source check; already committed observations survive cancellation. */
    fun checkSources(audioIds: List<String>, expectedRevision: Long,
        result: (Result<List<LocalAudioSourceCheckResult>>) -> Unit = {}): Job =
        checkSourcesInternal(audioIds, expectedRevision, null, result)
    fun checkSources(audioIds: List<String>, expectedVersion: LocalAudioVersionToken,
        result: (Result<List<LocalAudioSourceCheckResult>>) -> Unit = {}): Job =
        checkSourcesInternal(audioIds, null, expectedVersion, result)
    private fun checkSourcesInternal(audioIds: List<String>, expectedRevision: Long?, expectedVersion: LocalAudioVersionToken?,
        result: (Result<List<LocalAudioSourceCheckResult>>) -> Unit): Job {
        val frozen = audioIds.toList()
        if (sourceCheckJob?.isCompleted == false) return scope.launch {
            result(Result.failure(LocalAudioFailure(LocalAudioError.LIMIT_REACHED)))
        }
        val runId = ++sourceCheckRunId
        checkingSources.value = LocalAudioSourceCheckState(running = true, runId = runId)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                initialization.join()
                val results = withContext(Dispatchers.IO) {
                    val context = currentCoroutineContext()
                    val selected = commands.withLock {
                        context.ensureActive()
                        requireReady()
                        if (frozen.size !in 1..LocalAudioLimits.MAX_BATCH) throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
                        if (frozen.distinct().size != frozen.size || frozen.any { id ->
                                runCatching { LocalAudioLimits.requireId(id) }.isFailure }) {
                            throw LocalAudioFailure(LocalAudioError.INVALID_ORDER)
                        }
                        if (expectedVersion?.matches(state.value) == false ||
                            (expectedVersion == null && state.value.revision != expectedRevision))
                            throw LocalAudioFailure(LocalAudioError.STALE_STATE)
                        val sources = frozen.map { store.source(it) ?: throw LocalAudioFailure(LocalAudioError.NOT_FOUND) }
                        sources.map(::captureInspection)
                    }
                    updateSourceCheck(runId) { it.copy(total = selected.size) }
                    val observations = mutableListOf<LocalAudioSourceCheckResult>()
                    for (inspection in selected) {
                        context.ensureActive()
                        updateSourceCheck(runId) { it.copy(currentAudioId = inspection.stored.item.id) }
                        // Avoid opening sources already superseded since the selection was frozen.
                        val outdated = commands.withLock { obsoleteInspection(inspection) }
                        val observation = if (outdated != null) outdated else {
                            var recoveryAction: LocalAudioRecoveryAction? = null
                            val error = try {
                                sourceProbe.read(inspection.stored) { context.ensureActive() }
                                null
                            } catch (error: CancellationException) { throw error }
                            catch (error: LocalAudioFailure) { recoveryAction = error.recoveryAction; error.error }
                            commands.withLock {
                                context.ensureActive()
                                recordInspection(inspection, error).let {
                                    it.copy(recoveryAction = if (it.applied && it.error == error) recoveryAction else null)
                                }
                            }
                        }
                        observations += observation
                        updateSourceCheck(runId) { it.copy(processed = observations.size, results = observations.toList()) }
                    }
                    observations.toList()
                }
                updateSourceCheck(runId) { it.copy(running = false, currentAudioId = null, completed = true) }
                if (checkingSources.value.runId == runId) {
                    sourceCheckJob = null // Work is finished; callbacks may start the next run.
                    result(Result.success(results))
                }
            } catch (error: CancellationException) {
                updateSourceCheck(runId) { it.copy(running = false, currentAudioId = null, cancelled = true) }
                throw error
            } catch (error: Exception) {
                val reason = failure(error)
                updateSourceCheck(runId) { it.copy(running = false, currentAudioId = null, error = reason.error) }
                if (checkingSources.value.runId == runId) {
                    sourceCheckJob = null
                    result(Result.failure(reason))
                }
            }
        }
        sourceCheckJob = job
        job.invokeOnCompletion { if (job.isCancelled) updateSourceCheck(runId) {
            it.copy(running = false, currentAudioId = null, cancelled = true)
        } }
        job.start()
        return job
    }

    fun cancelSourceCheck() { sourceCheckJob?.cancel() }
    /** Dismiss results and invalidate late publications; cleanup still owns the busy slot. */
    fun clearSourceCheck() {
        sourceCheckJob?.cancel()
        checkingSources.value = LocalAudioSourceCheckState(runId = ++sourceCheckRunId)
    }
    private inline fun updateSourceCheck(runId: Long, change: (LocalAudioSourceCheckState) -> LocalAudioSourceCheckState) {
        checkingSources.update { if (it.runId == runId) change(it) else it }
    }

    /** Immutable derived image only. Coil can use this File; no source capability is exposed. */
    fun artworkFile(key: String): File? = artwork?.resolve(key)

    /** Coil model respecting OFF; user-selected images always win. Collect artworkMode too. */
    fun displayArtworkFile(item: LocalAudioItem): File? = displayArtworkKey(item)?.let(::artworkFile)
    fun displayArtworkKey(item: LocalAudioItem): String? =
        if (item.artworkCustom || autoArtworkEnabled()) item.artworkKey else null

    fun setPlaybackSpeed(audioId: String, speed: Float, result: (Result<Unit>) -> Unit = {}): Job = submit(result) {
        if (runCatching { LocalAudioLimits.requirePlaybackSpeed(speed) }.isFailure) throw LocalAudioFailure(LocalAudioError.INVALID_TARGET)
        store.setPlaybackSpeed(audioId, speed)
    }

    /** The image grant is used only during this call; it is never persisted. */
    fun setCustomArtwork(audioId: String, image: Uri, grantedFlags: Int,
        result: (Result<Unit>) -> Unit = {}): Job = setCustomArtwork(audioId, image, grantedFlags, null, result)

    /** Crop coordinates refer to the EXIF-upright image. Keep the old positional callback overload. */
    fun setCustomArtwork(audioId: String, image: Uri, grantedFlags: Int, crop: LocalArtworkCrop?,
        result: (Result<Unit>) -> Unit = {}): Job = scope.launch {
        initialization.join()
        var lease: LocalArtworkLease? = null
        val response = try {
            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                crop?.validate()
                val intent = commands.withLock {
                    requireReady(); store.source(audioId) ?: throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
                    (++customArtworkEpoch).also { customArtworkEpochs[audioId] = it }
                }
                if (!LocalAudioLimits.isContentUri(image.toString())) throw LocalAudioFailure(LocalAudioError.INVALID_TARGET)
                // Photo pickers may grant temporary read access without FLAG_GRANT_READ.
                // The resolver open is authoritative; never invent or retain a grant.
                @Suppress("UNUSED_VARIABLE") val returnedFlags = grantedFlags
                lease = artwork?.prepareImage(image, crop) { context.ensureActive() }
                    ?: throw LocalAudioFailure(LocalAudioError.READ_FAILED)
                commands.withLock {
                    context.ensureActive()
                    if (customArtworkEpochs[audioId] != intent) throw LocalAudioFailure(LocalAudioError.STALE_STATE)
                    store.setCustomArtwork(audioId, lease!!.key)
                    publish()
                }
            }
            Result.success(Unit)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(failure(error)) }
        finally { releaseArtwork(lease) }
        result(response)
    }

    fun clearCustomArtwork(audioId: String, result: (Result<Unit>) -> Unit = {}): Job = submit(result) {
        store.setCustomArtwork(audioId, null)
        customArtworkEpochs[audioId] = ++customArtworkEpoch
        pruneArtwork()
    }

    /** Explicit, bounded repair for old/missing covers; source checks stay lightweight. */
    fun fillArtwork(audioIds: List<String>, expectedRevision: Long,
        result: (Result<List<LocalAudioArtworkResult>>) -> Unit = {}): Job =
        fillArtworkInternal(audioIds, expectedRevision, null, result)
    fun fillArtwork(audioIds: List<String>, expectedVersion: LocalAudioVersionToken,
        result: (Result<List<LocalAudioArtworkResult>>) -> Unit = {}): Job =
        fillArtworkInternal(audioIds, null, expectedVersion, result)
    private fun fillArtworkInternal(audioIds: List<String>, expectedRevision: Long?, expectedVersion: LocalAudioVersionToken?,
        result: (Result<List<LocalAudioArtworkResult>>) -> Unit): Job {
        val frozen = audioIds.toList()
        if (artworkJob?.isCompleted == false) return scope.launch {
            result(Result.failure(LocalAudioFailure(LocalAudioError.LIMIT_REACHED)))
        }
        return scope.launch {
            fillingArtwork.value = LocalAudioArtworkState(running = true)
            try {
                initialization.join()
                val results = withContext(Dispatchers.IO) {
                    val context = currentCoroutineContext()
                    val selected = commands.withLock {
                        context.ensureActive(); requireReady()
                        if (!autoArtworkEnabled()) throw LocalAudioFailure(LocalAudioError.INVALID_TARGET)
                        if (frozen.size !in 1..LocalAudioLimits.MAX_BATCH) throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
                        if (frozen.distinct().size != frozen.size || frozen.any { runCatching { LocalAudioLimits.requireId(it) }.isFailure }) {
                            throw LocalAudioFailure(LocalAudioError.INVALID_ORDER)
                        }
                        if (expectedVersion?.matches(state.value) == false ||
                            (expectedVersion == null && state.value.revision != expectedRevision))
                            throw LocalAudioFailure(LocalAudioError.STALE_STATE)
                        frozen.map { store.source(it) ?: throw LocalAudioFailure(LocalAudioError.NOT_FOUND) }.map(::captureInspection)
                    }
                    fillingArtwork.value = fillingArtwork.value.copy(total = selected.size)
                    val observations = mutableListOf<LocalAudioArtworkResult>()
                    for (inspection in selected) {
                        context.ensureActive()
                        val item = inspection.stored.item
                        fillingArtwork.value = fillingArtwork.value.copy(currentAudioId = item.id)
                        var lease: LocalArtworkLease? = null
                        val observation = try {
                            commands.withLock { obsoleteInspection(inspection) }?.error?.let { throw LocalAudioFailure(it) }
                            if (!autoArtworkEnabled()) throw LocalAudioFailure(LocalAudioError.INVALID_TARGET)
                            // A selected image is already the user's answer; repair must not replace it.
                            if (item.artworkCustom) {
                                observations += LocalAudioArtworkResult(item.id, item.artworkKey)
                                fillingArtwork.value = fillingArtwork.value.copy(processed = observations.size, results = observations.toList())
                                continue
                            }
                            val existing = item.metadata.artworkKey?.takeIf { artwork?.resolve(it) != null }
                            val key = existing ?: run {
                                val uri = sourceProbe.read(inspection.stored) { context.ensureActive() }
                                lease = artwork?.prepare(uri, item.metadata) { context.ensureActive() }
                                lease?.key
                            }
                            commands.withLock {
                                context.ensureActive()
                                obsoleteInspection(inspection)?.error?.let { throw LocalAudioFailure(it) }
                                if (!autoArtworkEnabled()) throw LocalAudioFailure(LocalAudioError.INVALID_TARGET)
                                if (key != item.metadata.artworkKey) {
                                    store.setArtwork(item.id, key)
                                    publish()
                                }
                                LocalAudioArtworkResult(item.id, key)
                            }
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) { LocalAudioArtworkResult(item.id, error = failure(error).error) }
                        finally { releaseArtwork(lease) }
                        observations += observation
                        fillingArtwork.value = fillingArtwork.value.copy(processed = observations.size, results = observations.toList())
                    }
                    observations.toList()
                }
                fillingArtwork.value = fillingArtwork.value.copy(running = false, currentAudioId = null, completed = true)
                result(Result.success(results))
            } catch (error: CancellationException) {
                fillingArtwork.value = fillingArtwork.value.copy(running = false, currentAudioId = null, cancelled = true)
                throw error
            } catch (error: Exception) {
                val reason = failure(error)
                fillingArtwork.value = fillingArtwork.value.copy(running = false, currentAudioId = null, error = reason.error)
                result(Result.failure(reason))
            }
        }.also { artworkJob = it }
    }

    fun cancelArtworkFill() { artworkJob?.cancel() }
    fun reorder(folderId: String, audioIds: List<String>, expectedRevision: Long, result: (Result<Unit>) -> Unit = {}): Job {
        val frozen = audioIds.toList()
        return submit(result) {
            if (state.value.revision != expectedRevision) throw LocalAudioFailure(LocalAudioError.STALE_STATE)
            store.reorder(folderId, frozen)
        }
    }

    /** Whole-library deletion also collects its Musxi-owned copy; never deletes a source document. */
    fun deleteAudio(audioId: String, result: (Result<Unit>) -> Unit = {}): Job = submit(result) {
        val source = store.removeAudio(audioId)
        sourceEpochs.remove(audioId)
        sourceObservations.remove(audioId)
        customArtworkEpochs.remove(audioId)
        // Commit index removal before collecting a capability or an owned file.
        publish()
        if (source.mode == LocalImportMode.COPY && source.location !in store.retainedCopyNames() && source.location !in importingCopies && source.location !in transferringCopies) files.discard(source.location)
        pruneArtwork()
        releaseUnusedGrants()
    }

    fun saveProgress(folderId: String?, progress: LocalAudioProgress, result: (Result<Unit>) -> Unit = {}): Job = submit(result) {
        val currentFolder = folderId?.takeIf { id -> store.read().folders.any { it.id == id } }
        store.saveProgress(currentFolder, progress)
    }
    fun addBookmark(audioId: String, positionMs: Long, name: String, result: (Result<LocalAudioBookmark>) -> Unit = {}): Job = submit(result) {
        store.addBookmark(audioId, positionMs, name.trim())
    }
    fun removeBookmark(id: String, result: (Result<Unit>) -> Unit = {}): Job = submit(result) { store.removeBookmark(id) }
    fun renameBookmark(id: String, name: String, result: (Result<Unit>) -> Unit = {}): Job = submit(result) {
        store.renameBookmark(id, name.trim())
    }

    /** Commit exactly an accepted preview. Re-reads all inputs, and stores every slice atomically. */
    fun importCue(folderId: String, cue: Uri, audioUris: List<Uri>,
        expectedPreviewRevision: Long, expectedRevision: Long,
        bindings: Map<Int, Int> = emptyMap(), mode: LocalImportMode = LocalImportMode.COPY,
        grantedFlags: Int = 0, result: (Result<List<LocalAudioItem>>) -> Unit = {}): Job =
        importCueInternal(folderId, cue, audioUris, expectedPreviewRevision, expectedRevision, null,
            bindings, mode, grantedFlags, result)
    fun importCue(folderId: String, cue: Uri, audioUris: List<Uri>,
        expectedPreviewRevision: Long, expectedVersion: LocalAudioVersionToken,
        bindings: Map<Int, Int> = emptyMap(), mode: LocalImportMode = LocalImportMode.COPY,
        grantedFlags: Int = 0, result: (Result<List<LocalAudioItem>>) -> Unit = {}): Job =
        importCueInternal(folderId, cue, audioUris, expectedPreviewRevision, null, expectedVersion,
            bindings, mode, grantedFlags, result)
    private fun importCueInternal(folderId: String, cue: Uri, audioUris: List<Uri>,
        expectedPreviewRevision: Long, expectedRevision: Long?, expectedVersion: LocalAudioVersionToken?,
        bindings: Map<Int, Int>, mode: LocalImportMode, grantedFlags: Int,
        result: (Result<List<LocalAudioItem>>) -> Unit): Job {
        val uris = audioUris.toList()
        val mapping = bindings.toMap()
        val accepted = cuePreview.stampFor(expectedPreviewRevision)
        if (importJob?.isCompleted == false || transferJob?.isCompleted == false) return scope.launch {
            result(Result.failure(LocalAudioFailure(LocalAudioError.LIMIT_REACHED)))
        }
        val runId = ++importRunId
        importing.value = LocalAudioImportState(running = true, runId = runId)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            initialization.join()
            val response = try {
                val items = withContext(Dispatchers.IO) {
                    importGate.withLock {
                        val context = currentCoroutineContext()
                        var copied: CopiedLocalAudio? = null
                        var lease: LocalArtworkLease? = null
                        try {
                            commands.withLock {
                                requireReady()
                                if ((expectedVersion?.matches(state.value) ?: (state.value.revision == expectedRevision)).not() || accepted == null ||
                                    cuePreview.stampFor(expectedPreviewRevision) != accepted)
                                    throw LocalAudioFailure(LocalAudioError.STALE_STATE)
                                if (state.value.folders.none { it.id == folderId }) throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
                            }
                            val selection = cuePreview.inspectSelection(cue, uris, mapping)
                            if (selection.stamp != accepted) throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
                            val preview = selection.preview
                            if (!preview.canProceed || preview.files.size != 1) throw LocalAudioFailure(LocalAudioError.UNSUPPORTED_AUDIO)
                            val selected = preview.files.single().candidateIndex ?: throw LocalAudioFailure(LocalAudioError.INVALID_TARGET)
                            val uri = uris[selected]
                            val candidate = preview.candidates.first { it.index == selected }
                            val before = documents.describe(uri)
                            before.readError?.let { throw LocalAudioFailure(it) }
                            updateImport(runId) { it.copy(total = preview.tracks.size, currentName = candidate.name,
                                expectedBytes = before.size) }
                            if (mode == LocalImportMode.REFERENCE) commands.withLock {
                                acquireSourceRead(uri, grantedFlags)
                                importingGrants.add(uri.toString())
                            } else {
                                copied = files.copy({ documents.open(uri) }, before.size, { context.ensureActive() }) { count ->
                                    updateImport(runId) { it.copy(copiedBytes = count) }
                                }
                                withContext(NonCancellable) { commands.withLock { importingCopies.add(copied.name) } }
                            }
                            context.ensureActive()
                            val after = documents.describe(uri)
                            after.readError?.let { throw LocalAudioFailure(it) }
                            if ((before.size != null && after.size != before.size) ||
                                (before.modifiedAtMs != null && after.modifiedAtMs != before.modifiedAtMs))
                                throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
                            // Also catch changed CUE/headers while a large COPY was in flight.
                            if (cuePreview.inspectSelection(cue, uris, mapping).stamp != accepted)
                                throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
                            val size = copied?.bytes ?: before.size
                            val wholeMetadata = LocalAudioMetadata(candidate.name, preview.title, preview.performer,
                                candidate.durationUs!! / 1_000, size, before.modifiedAtMs)
                            val readUri = copied?.let { Uri.fromFile(files.resolve(it.name)) } ?: uri
                            lease = if (autoArtworkEnabled()) artwork?.prepare(readUri, wholeMetadata) { context.ensureActive() } else null
                            val base = copied?.name ?: AndroidLocalAudioDocuments.identity(uri)
                            val sourceFacts = listOf(mode.name, base, selection.cueDigest, candidate.sampleRate,
                                candidate.totalSamples, candidate.channels, candidate.bitsPerSample, size,
                                if (mode == LocalImportMode.REFERENCE) before.modifiedAtMs else null).joinToString("\n")
                            val tracks = preview.tracks.map { track ->
                                val start = track.startFrame!! * 1_000_000 / 75
                                val end = track.endFrameExclusive?.let { it * 1_000_000 / 75 } ?: candidate.durationUs!!
                                val segment = LocalAudioSegment(start, end, track.number)
                                val identityBytes = "$sourceFacts\n${track.number}\n$start\n$end".toByteArray(Charsets.UTF_8)
                                val identity = "cue-v1:" + MessageDigest.getInstance("SHA-256").digest(identityBytes)
                                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                                val source = LocalAudioSource(mode, copied?.name ?: uri.toString(), identity,
                                    if (mode == LocalImportMode.REFERENCE) uri.toString() else null)
                                LocalCueImportTrack(wholeMetadata.copy(title = track.title, creator = track.performer,
                                    durationMs = segment.durationMs, artworkKey = lease?.key), source, segment)
                            }
                            commands.withLock {
                                context.ensureActive()
                                if (cuePreview.stampFor(expectedPreviewRevision) != accepted) throw LocalAudioFailure(LocalAudioError.STALE_STATE)
                                val stored = if (expectedVersion != null) {
                                    store.importCue(folderId, tracks, expectedVersion) { context.ensureActive() }
                                } else store.importCue(folderId, tracks, requireNotNull(expectedRevision)) { context.ensureActive() }
                                stored.forEach { nextSourceObservation(it.id) }
                                updateImport(runId) { it.copy(processed = stored.size,
                                    results = stored.map { LocalAudioImportResult(it.title, it.id) }) }
                                publish()
                                stored
                            }
                        } finally {
                            withContext(NonCancellable) { commands.withLock {
                                lease?.close()
                                copied?.let { copy ->
                                    importingCopies.remove(copy.name)
                                    if (copy.created && runCatching { copy.name !in store.retainedCopyNames() }.getOrDefault(false)) files.discard(copy.name)
                                }
                                importingGrants.clear()
                                pruneArtwork()
                                releaseUnusedGrants()
                                updateImport(runId) { it.copy(running = false, currentName = null) }
                            } }
                        }
                    }
                }
                Result.success(items)
            } catch (error: CancellationException) {
                updateImport(runId) { it.copy(running = false, currentName = null, cancelled = true) }
                throw error
            } catch (error: Exception) {
                val mapped = if (error is PreviewFailure) LocalAudioFailure(when (error.reason) {
                    CuePreviewError.PERMISSION_REQUIRED -> LocalAudioError.PERMISSION_REQUIRED
                    CuePreviewError.NOT_FOUND -> LocalAudioError.NOT_FOUND
                    CuePreviewError.TOO_LARGE -> LocalAudioError.TOO_LARGE
                    else -> LocalAudioError.READ_FAILED
                }) else failure(error)
                Result.failure(mapped)
            }
            updateImport(runId) { it.copy(running = false, currentName = null,
                completed = response.isSuccess, error = (response.exceptionOrNull() as? LocalAudioFailure)?.error) }
            if (importing.value.runId == runId) {
                importJob = null // File/grant cleanup above is complete before releasing the slot.
                result(response)
            }
        }
        importJob = job
        job.invokeOnCompletion { if (job.isCancelled) updateImport(runId) {
            it.copy(running = false, currentName = null, cancelled = true)
        } }
        job.start()
        return job
    }
    /** Pass the flags returned by ACTION_OPEN_DOCUMENT, not invented permission flags. */
    fun importFiles(
        folderId: String,
        uris: List<Uri>,
        mode: LocalImportMode = LocalImportMode.COPY,
        grantedFlags: Int = 0,
        result: (Result<List<LocalAudioImportResult>>) -> Unit = {},
    ): Job {
        val frozen = uris.toList()
        return startImport(folderId, mode, grantedFlags, result) {
            if (frozen.size !in 1..LocalAudioLimits.MAX_BATCH) throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
            frozen.map { uri ->
                try { documents.describe(uri) }
                catch (error: LocalAudioFailure) {
                    // Retain a per-file failure without publishing its URI.
                    LocalAudioDocument(uri, "未命名音频", null, null, error.error)
                }
            }.sortedWith { a, b -> LocalAudioNames.compare(a.name, b.name) }.map { it to it.uri }
        }
    }

    /** One-shot directory import. Repeating it discovers additions without resetting user edits. */
    fun importDirectory(
        folderId: String,
        tree: Uri,
        mode: LocalImportMode = LocalImportMode.COPY,
        grantedFlags: Int = 0,
        includeSubdirectories: Boolean = false,
        result: (Result<List<LocalAudioImportResult>>) -> Unit = {},
    ): Job = startImport(folderId, mode, grantedFlags, result) {
        val context = currentCoroutineContext()
        if (mode == LocalImportMode.REFERENCE) commands.withLock {
            acquireSourceRead(tree, grantedFlags)
            importingGrants.add(tree.toString())
        }
        documents.children(tree, includeSubdirectories) { context.ensureActive() }.map { it to tree }
    }

    /** Cancelling keeps already committed imports and removes the current partial copy. */
    fun cancelImport() { importJob?.cancel() }
    fun clearImport() {
        importJob?.cancel()
        importing.value = LocalAudioImportState(runId = ++importRunId)
    }
    private inline fun updateImport(runId: Long, change: (LocalAudioImportState) -> LocalAudioImportState) {
        importing.update { if (it.runId == runId) change(it) else it }
    }

    fun cancelTransfer() { transferJob?.cancel() }

    /** Writes original bytes, including COPY content verification, to a new empty SAF document. */
    fun exportAudio(audioId: String, target: Uri, grantedFlags: Int,
        result: (Result<LocalAudioExportResult>) -> Unit = {}): Job =
        startTransfer(LocalAudioTransferKind.AUDIO_EXPORT, result) { run ->
            val context = currentCoroutineContext()
            val stored = commands.withLock {
                val value = store.source(audioId) ?: throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
                // Exporting a shared source as if it were one slice would produce the wrong song.
                if (value.item.segment != null) throw LocalAudioFailure(LocalAudioError.INVALID_TARGET)
                if (value.source.mode == LocalImportMode.COPY) transferringCopies.add(value.source.location)
                else value.source.grantUri?.let(transferringGrants::add)
                value
            }
            transferring.value = transferring.value.copy(expectedBytes = stored.item.metadata.sizeBytes)
            val source = if (stored.source.mode == LocalImportMode.COPY) {
                try { files.resolve(stored.source.location).inputStream() }
                catch (error: LocalAudioFailure) { throw error }
                catch (_: java.io.IOException) { throw LocalAudioFailure(LocalAudioError.READ_FAILED) }
            } else {
                val uri = Uri.parse(stored.source.location)
                val actual = documents.describe(uri)
                if ((actual.size != null && stored.item.metadata.sizeBytes != null && actual.size != stored.item.metadata.sizeBytes) ||
                    (actual.modifiedAtMs != null && stored.item.metadata.modifiedAtMs != null && actual.modifiedAtMs != stored.item.metadata.modifiedAtMs)) {
                    throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
                }
                documents.open(uri)
            }
            val bytes = source.use { input ->
                val protected = if (stored.source.mode == LocalImportMode.REFERENCE)
                    setOfNotNull(stored.source.location, stored.source.grantUri) else emptySet()
                writeTransferDocument(run, target, grantedFlags, protected) { output ->
                    var lastPublished = 0L
                    LocalAudioExport.copy(input, output, stored.item.metadata.sizeBytes,
                        stored.source.location.takeIf { stored.source.mode == LocalImportMode.COPY }, { context.ensureActive() }) { count ->
                        val now = System.nanoTime()
                        if (now - lastPublished >= 200_000_000L) {
                            transferring.value = transferring.value.copy(completedBytes = count)
                            lastPublished = now
                        }
                    }
                }
            }
            commands.withLock {
                context.ensureActive()
                if (store.source(audioId)?.let(::sourceVersion) != sourceVersion(stored)) throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
            }
            transferring.value = transferring.value.copy(completedBytes = bytes)
            LocalAudioExportResult(audioId, bytes)
        }

    /** Organization backup contains no audio bytes, system URIs, grants or playback queue. */
    fun backupOrganization(target: Uri, grantedFlags: Int,
        result: (Result<LocalAudioBackupResult>) -> Unit = {}): Job =
        startTransfer(LocalAudioTransferKind.ORGANIZATION_BACKUP, result) { run ->
            val context = currentCoroutineContext()
            val backup = commands.withLock {
                val state = store.read()
                LocalAudioBackup(LocalAudioLimits.newId(), System.currentTimeMillis().coerceAtLeast(0),
                    state.items.map { item ->
                        context.ensureActive()
                        val source = store.source(item.id) ?: throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
                        // Version-1 organization backups carry no derived image resources or media-kind fields.
                        // Restore merges organization into existing imports and retains their current metadata.
                        LocalAudioBackupItem(item.copy(metadata = item.metadata.copy(kind = LocalMediaKind.AUDIO, artworkKey = null),
                            customArtworkKey = null, playbackSpeed = 1f),
                            LocalAudioBackupKeys.source(source))
                    }, state.folders, state.members, state.progress, state.cursors, state.bookmarks)
            }
            writeTransferDocument(run, target, grantedFlags) { output ->
                LocalAudioBackupCodec.write(backup, output) { context.ensureActive() }
            }
            LocalAudioBackupResult(backup.id, backup.items.size, backup.folders.size)
        }

    /** Reads once, validates and stages a private immutable backup; confirmation never reopens it. */
    fun previewOrganization(source: Uri, result: (Result<LocalAudioRestorePreview>) -> Unit = {}): Job =
        startTransfer(LocalAudioTransferKind.ORGANIZATION_PREVIEW, result) {
            pendingOrganization = null
            val context = currentCoroutineContext()
            val backup = requireTransferDocuments().openInput(source).use { input ->
                LocalAudioBackupCodec.read(input) { context.ensureActive() }
            }
            commands.withLock {
                context.ensureActive()
                store.previewOrganization(backup).also { pendingOrganization = PendingOrganization(backup, it.expectedRevision, it.expectedVersion) }
            }
        }

    fun restoreOrganization(backupId: String, expectedRevision: Long,
        result: (Result<LocalAudioRestoreResult>) -> Unit = {}): Job =
        startTransfer(LocalAudioTransferKind.ORGANIZATION_RESTORE, result) {
            val context = currentCoroutineContext()
            val pending = pendingOrganization?.takeIf { it.backup.id == backupId }
                ?: throw LocalAudioFailure(LocalAudioError.INVALID_BACKUP)
            if (pending.revision != expectedRevision) throw LocalAudioFailure(LocalAudioError.STALE_STATE)
            commands.withLock {
                currentCoroutineContext().ensureActive()
                store.restoreOrganization(pending.backup, expectedRevision) { context.ensureActive() }
                    .also { publish(); pendingOrganization = null }
            }
        }

    fun restoreOrganization(backupId: String, expectedVersion: LocalAudioVersionToken,
        result: (Result<LocalAudioRestoreResult>) -> Unit = {}): Job =
        startTransfer(LocalAudioTransferKind.ORGANIZATION_RESTORE, result) {
            val context = currentCoroutineContext()
            val pending = pendingOrganization?.takeIf { it.backup.id == backupId }
                ?: throw LocalAudioFailure(LocalAudioError.INVALID_BACKUP)
            if (pending.version != expectedVersion) throw LocalAudioFailure(LocalAudioError.STALE_STATE)
            commands.withLock {
                context.ensureActive()
                store.restoreOrganization(pending.backup, expectedVersion) { context.ensureActive() }
                    .also { publish(); pendingOrganization = null }
            }
        }

    private class TransferRun(var incompleteTarget: Uri? = null)

    private suspend fun <T> writeTransferDocument(run: TransferRun, target: Uri, flags: Int, additionalProtected: Set<String> = emptySet(),
        write: (java.io.OutputStream) -> T): T {
        val transfers = requireTransferDocuments()
        commands.withLock {
            val protected = store.read().items.mapNotNull { store.source(it.id)?.source }
                .filter { it.mode == LocalImportMode.REFERENCE }.flatMap { listOfNotNull(it.location, it.grantUri) }.toSet()
            transfers.validateDestination(target, flags, protected + additionalProtected)
        }
        currentCoroutineContext().ensureActive()
        run.incompleteTarget = target
        return transfers.openOutput(target).use(write)
    }

    private fun requireTransferDocuments() = transferDocuments ?: throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)

    private fun <T> startTransfer(kind: LocalAudioTransferKind, result: (Result<T>) -> Unit,
        action: suspend (TransferRun) -> T): Job {
        if (transferJob?.isCompleted == false || importJob?.isCompleted == false) return scope.launch {
            result(Result.failure(LocalAudioFailure(LocalAudioError.LIMIT_REACHED)))
        }
        return scope.launch {
            initialization.join()
            val run = TransferRun()
            val response = try {
                transferring.value = LocalAudioTransferState(running = true, kind = kind)
                val value = withContext(Dispatchers.IO) {
                    commands.withLock { requireReady() }
                    action(run).also { currentCoroutineContext().ensureActive() }
                }
                transferring.value = transferring.value.copy(completed = true)
                Result.success(value)
            } catch (error: CancellationException) {
                transferring.value = transferring.value.copy(cancelled = true)
                throw error
            } catch (error: Exception) {
                val reason = failure(error)
                transferring.value = transferring.value.copy(error = reason.error)
                Result.failure(reason)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (!transferring.value.completed) run.incompleteTarget?.let {
                        val cleaned = runCatching { requireTransferDocuments().discardIncomplete(it) }.getOrDefault(false)
                        transferring.value = transferring.value.copy(cleanupFailed = !cleaned)
                    }
                    commands.withLock {
                        try {
                            val retained = store.retainedCopyNames()
                            transferringCopies.filter { it !in retained && it !in importingCopies }.forEach { files.discard(it) }
                        } catch (_: Exception) {
                            transferring.value = transferring.value.copy(cleanupFailed = true)
                        } finally {
                            transferringCopies.clear(); transferringGrants.clear()
                        }
                        try { releaseUnusedGrants() } catch (_: Exception) {
                            transferring.value = transferring.value.copy(cleanupFailed = true)
                        }
                    }
                    transferring.value = transferring.value.copy(running = false)
                }
            }
            result(response)
        }.also { transferJob = it }
    }

    /** Save an explicit SAF tree association. Even COPY needs a persistent grant for later refresh. */
    fun linkDirectory(folderId: String, tree: Uri, name: String? = null, mode: LocalImportMode = LocalImportMode.COPY,
        grantedFlags: Int, includeSubdirectories: Boolean = false,
        result: (Result<LocalAudioDirectoryLink>) -> Unit = {}): Job = submit(result) {
        if (!android.provider.DocumentsContract.isTreeUri(tree)) throw LocalAudioFailure(LocalAudioError.READ_FAILED)
        try {
            acquireSourceRead(tree, grantedFlags, explicit = true)
            store.linkDirectory(folderId, tree.toString(), name?.trim() ?: documents.directoryName(tree), mode, includeSubdirectories)
        } finally { releaseUnusedGrants() }
    }

    /** Re-authorize the same tree only; selecting a different directory is a new association. */
    fun reauthorizeDirectory(id: String, tree: Uri, grantedFlags: Int, result: (Result<Unit>) -> Unit = {}): Job = submit(result) {
        val link = store.directory(id) ?: throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
        if (link.treeUri != tree.toString()) throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
        directoryEpochs[id] = (directoryEpochs[id] ?: 0) + 1
        try {
            acquireSourceRead(tree, grantedFlags, explicit = true)
            store.setDirectoryError(id, null)
        } finally { releaseUnusedGrants() }
    }

    fun unlinkDirectory(id: String, result: (Result<Unit>) -> Unit = {}): Job = submit(result) {
        store.unlinkDirectory(id)
        directoryEpochs.remove(id)
        releaseUnusedGrants()
    }

    fun refreshDirectory(id: String, result: (Result<LocalAudioDirectoryRefreshResult>) -> Unit = {}): Job =
        startImport(null, LocalImportMode.COPY, 0, { response ->
            result(response.map { LocalAudioDirectoryRefreshResult(id, it,
                state.value.directories.firstOrNull { link -> link.id == id }?.missingCount ?: 0) })
        }, directoryId = id) { emptyList() }

    fun relink(
        audioId: String,
        uri: Uri,
        grantedFlags: Int,
        result: (Result<Unit>) -> Unit = {},
    ): Job = submit(result) {
        val previous = store.source(audioId) ?: throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
        if (previous.item.segment != null) throw LocalAudioFailure(LocalAudioError.INVALID_TARGET)
        if (previous.source.mode != LocalImportMode.REFERENCE) throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
        try {
            acquireSourceRead(uri, grantedFlags, explicit = true)
            val context = currentCoroutineContext()
            val document = documents.describe(uri)
            val metadata = documents.metadataForImport(document, LocalImportMode.REFERENCE) { context.ensureActive() }
            documents.open(uri).use { if (it.read() < 0) throw LocalAudioFailure(LocalAudioError.READ_FAILED) }
            val lease = if (autoArtworkEnabled() && !DsfPlaybackSupport.isCandidate(document.name, document.mime))
                artwork?.prepare(uri, metadata) { context.ensureActive() } else null
            try {
                context.ensureActive()
                store.relink(audioId, metadata.copy(artworkKey = lease?.key), LocalAudioSource(LocalImportMode.REFERENCE, uri.toString(),
                    AndroidLocalAudioDocuments.identity(uri), uri.toString()))
                nextSourceEpoch(audioId)
            } finally {
                lease?.close()
                pruneArtwork()
            }
        } finally { releaseUnusedGrants() }
    }

    /** Explicit bounded read; does not acquire grants, update the library or retain URIs in state. */
    fun inspectFormat(audioId: String, result: (Result<LocalAudioFormatInfo>) -> Unit = {}): Job = scope.launch {
        initialization.join()
        val response = try {
            val value = withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                val stored = commands.withLock {
                    requireReady()
                    store.source(audioId) ?: throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
                }
                val uri = sourceProbe.read(stored) { context.ensureActive() }
                context.ensureActive()
                val facts = LocalAudioFormatInspector.inspect {
                    if (stored.source.mode == LocalImportMode.COPY) files.resolve(stored.source.location).inputStream()
                    else documents.open(uri)
                }
                commands.withLock {
                    context.ensureActive()
                    val latest = store.source(audioId) ?: throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
                    if (sourceVersion(latest) != sourceVersion(stored)) throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
                }
                LocalAudioFormatInfo(audioId, facts?.codec, facts?.sampleRateHz, facts?.bitsPerSample, facts?.channelCount)
            }
            Result.success(value)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            Result.failure(when (error) {
                is LocalAudioFailure -> error
                is SecurityException -> LocalAudioFailure(LocalAudioError.PERMISSION_REQUIRED)
                is java.io.FileNotFoundException -> LocalAudioFailure(LocalAudioError.NOT_FOUND)
                else -> LocalAudioFailure(LocalAudioError.READ_FAILED)
            })
        }
        currentCoroutineContext().ensureActive()
        result(response)
    }
    /** Metadata-only queue; UI/controller never receives document references or managed paths. */
    internal suspend fun preparePlayback(folderId: String?, audioId: String? = null, fromStart: Boolean = false): LocalAudioPlaybackQueue =
        withContext(Dispatchers.IO) {
            initialization.join()
            commands.withLock {
                requireReady()
                val snapshot = store.read()
                val folder = folderId?.let { id -> snapshot.folders.firstOrNull { it.id == id }
                    ?: throw LocalAudioFailure(LocalAudioError.NOT_FOUND) }
                val ids = if (folder != null) snapshot.members.filter { it.folderId == folder.id }.sortedBy { it.position }.map { it.audioId }
                    else listOf(audioId ?: throw LocalAudioFailure(LocalAudioError.NOT_FOUND))
                if (ids.isEmpty()) throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
                if (ids.size > QueueSources.MAX_QUEUE_SIZE) throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
                val resume = (folder?.continuePlayback ?: true) && !fromStart
                val progress = snapshot.progress.associateBy { it.audioId }
                val entries = ids.map { id ->
                    val stored = store.source(id) ?: throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
                    val offset = (progress[id]?.takeUnless { it.completed }?.positionMs?.takeIf { resume } ?: 0)
                        .coerceAtMost(stored.item.segment?.durationMs ?: LocalAudioLimits.MAX_POSITION_MS)
                    LocalAudioQueueEntry(stored.item, sourceVersion(stored), offset)
                }
                val chosen = audioId ?: if (fromStart) ids.first() else snapshot.cursors.firstOrNull { it.folderId == folderId }?.audioId ?: ids.first()
                val index = ids.indexOf(chosen).takeIf { it >= 0 } ?: throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
                LocalAudioPlaybackQueue(folderId, entries, index, if (fromStart) 0 else entries[index].resumePositionMs, resume)
            }
        }

    /** Restore an edited queue by stable IDs, without reopening documents or resetting user data. */
    internal suspend fun prepareSavedPlayback(saved: SavedLocalAudioQueue): LocalAudioPlaybackQueue = withContext(Dispatchers.IO) {
        initialization.join()
        commands.withLock {
            requireReady()
            val snapshot = store.read()
            val progress = snapshot.progress.associateBy { it.audioId }
            // Library deletion removes only those slots; relinking is a different recording version.
            val surviving = saved.entries.mapIndexedNotNull { index, entry ->
                val stored = store.source(entry.audioId) ?: return@mapIndexedNotNull null
                if (sourceVersion(stored) != entry.sourceVersion) throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
                val previous = progress[entry.audioId]
                val position = (if (saved.continuePlayback) previous?.takeUnless { it.completed }?.positionMs ?: 0 else 0)
                    .coerceAtMost(stored.item.segment?.durationMs ?: LocalAudioLimits.MAX_POSITION_MS)
                index to LocalAudioQueueEntry(stored.item, entry.sourceVersion, position)
            }
            if (surviving.isEmpty()) throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
            val current = surviving.indexOfFirst { it.first == saved.index }.takeIf { it >= 0 }
                ?: surviving.indexOfFirst { it.first > saved.index }.takeIf { it >= 0 } ?: surviving.lastIndex
            val oldIndex = surviving[current].first
            val savedEntry = saved.entries[oldIndex]
            val latest = progress[savedEntry.audioId]
            val position = if (oldIndex == saved.index) {
                if (saved.useStoredProgress && latest != null) { if (latest.completed) 0 else latest.positionMs }
                else saved.positionMs
            } else surviving[current].second.resumePositionMs
            val remap = surviving.mapIndexed { new, pair -> pair.first to new }.toMap()
            val order = saved.shuffleOrder.mapNotNull(remap::get)
            LocalAudioPlaybackQueue(saved.folderId?.takeIf { id -> snapshot.folders.any { it.id == id } },
                surviving.map { it.second }, current,
                position.coerceAtMost(surviving[current].second.audio.segment?.durationMs ?: LocalAudioLimits.MAX_POSITION_MS), saved.continuePlayback,
                saved.repeatMode, saved.shuffleEnabled, order, saved.useStoredProgress)
        }
    }

    /** Resolve on a loader/IO thread. The returned capability is never controller/UI state. */
    internal suspend fun resolveForPlayback(audioId: String, expectedVersion: String? = null): LocalAudioPlaybackSource = withContext(Dispatchers.IO) {
        initialization.join()
        val context = currentCoroutineContext()
        val inspection = commands.withLock {
            context.ensureActive()
            requireReady()
            val stored = store.source(audioId) ?: throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
            if (expectedVersion != null && sourceVersion(stored) != expectedVersion) throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
            captureInspection(stored)
        }
        var uri: Uri? = null
        var recoveryAction: LocalAudioRecoveryAction? = null
        val error = try {
            uri = sourceProbe.read(inspection.stored) { context.ensureActive() }
            null
        } catch (error: CancellationException) { throw error }
        catch (error: LocalAudioFailure) { recoveryAction = error.recoveryAction; error.error }
        commands.withLock {
            context.ensureActive()
            val observation = recordInspection(inspection, error, playback = true)
            observation.error?.let { throw LocalAudioFailure(it, if (it == error) recoveryAction else null) }
            LocalAudioPlaybackSource(store.source(audioId)!!.item, uri!!)
        }
    }

    private fun nextSourceEpoch(audioId: String): Long = (++sourceEpoch).also { sourceEpochs[audioId] = it }

    private fun nextSourceObservation(audioId: String): Long = (++sourceEpoch).also { sourceObservations[audioId] = it }

    /** Called under commands. Explicit selections can restore a grant used by an old read. */
    private fun acquireSourceRead(grant: Uri, flags: Int, explicit: Boolean = false) {
        documents.acquireRead(grant, flags)
        if (explicit || flags != 0) authorizationEpochs[grant.toString()] = ++sourceEpoch
    }

    private fun captureInspection(stored: StoredLocalAudio): SourceInspection {
        val id = stored.item.id
        val observation = nextSourceObservation(id)
        return SourceInspection(stored, sourceEpochs[id] ?: 0L, observation, authorizationEpochs[stored.source.grantUri] ?: 0L)
    }

    private fun obsoleteInspection(inspection: SourceInspection, requireLatest: Boolean = true): LocalAudioSourceCheckResult? {
        val id = inspection.stored.item.id
        val current = store.source(id) ?: return LocalAudioSourceCheckResult(id, error = LocalAudioError.NOT_FOUND)
        if (sourceVersion(current) != sourceVersion(inspection.stored)) {
            return LocalAudioSourceCheckResult(id, error = LocalAudioError.SOURCE_CHANGED)
        }
        if ((sourceEpochs[id] ?: 0L) != inspection.epoch) return LocalAudioSourceCheckResult(id, error = LocalAudioError.SOURCE_CHANGED)
        if (requireLatest && inspection.authorization != (authorizationEpochs[inspection.stored.source.grantUri] ?: 0L)) {
            return LocalAudioSourceCheckResult(id, error = LocalAudioError.STALE_STATE)
        }
        if (requireLatest && sourceObservations[id] != inspection.observation) {
            return LocalAudioSourceCheckResult(id, error = LocalAudioError.STALE_STATE)
        }
        return null
    }

    /** Called under commands only, after the provider stream has closed. */
    private fun recordInspection(inspection: SourceInspection, error: LocalAudioError?, playback: Boolean = false): LocalAudioSourceCheckResult {
        obsoleteInspection(inspection, requireLatest = !playback)?.let { return it }
        val id = inspection.stored.item.id
        val availability = when (error) {
            null -> LocalAudioAvailability.AVAILABLE
            LocalAudioError.PERMISSION_REQUIRED -> LocalAudioAvailability.PERMISSION_REQUIRED
            LocalAudioError.NOT_FOUND -> LocalAudioAvailability.MISSING
            else -> LocalAudioAvailability.UNREADABLE
        }
        // A newer check can own availability while an older valid playback read still completes.
        if (playback && (sourceObservations[id] != inspection.observation ||
                inspection.authorization != (authorizationEpochs[inspection.stored.source.grantUri] ?: 0L))) {
            return LocalAudioSourceCheckResult(id, availability, error)
        }
        if (store.source(id)!!.item.availability != availability) {
            store.setAvailability(id, availability)
            publish()
        }
        return LocalAudioSourceCheckResult(id, availability, error, applied = true)
    }

    private fun startImport(
        folderId: String?,
        mode: LocalImportMode,
        flags: Int,
        result: (Result<List<LocalAudioImportResult>>) -> Unit,
        directoryId: String? = null,
        inputs: suspend () -> List<Pair<LocalAudioDocument, Uri>>,
    ): Job {
        // This facade is a main-thread command API, like the existing wallpaper/playback facades.
        if (importJob?.isCompleted == false || transferJob?.isCompleted == false) return scope.launch {
            result(Result.failure(LocalAudioFailure(LocalAudioError.LIMIT_REACHED)))
        }
        val runId = ++importRunId
        importing.value = LocalAudioImportState(running = true, runId = runId)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            initialization.join()
            var refreshing: DirectoryRefresh? = null
            val response = try {
                withContext(Dispatchers.IO) {
                    importGate.withLock {
                        val targetFolder = commands.withLock {
                            requireReady()
                            refreshing = directoryId?.let { id ->
                                DirectoryRefresh(store.directory(id) ?: throw LocalAudioFailure(LocalAudioError.NOT_FOUND), directoryEpochs[id] ?: 0)
                            }
                            val target = refreshing?.stored?.link?.folderId ?: folderId ?: throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
                            if (state.value.folders.none { it.id == target }) throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
                            refreshing?.let { importingGrants.add(it.stored.treeUri) }
                            target
                        }
                        try {
                            val chosen = refreshing?.let { refresh ->
                                val tree = Uri.parse(refresh.stored.treeUri)
                                // Flags zero means an existing persisted grant is required; never invent authorization.
                                documents.acquireRead(tree, 0)
                                val context = currentCoroutineContext()
                                documents.children(tree, refresh.stored.link.includeSubdirectories) { context.ensureActive() }
                                    .distinctBy { AndroidLocalAudioDocuments.identity(it.uri) }.map { it to tree }
                            } ?: inputs()
                            if (chosen.size > LocalAudioLimits.MAX_BATCH) throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
                            updateImport(runId) { it.copy(total = chosen.size) }
                            val results = mutableListOf<LocalAudioImportResult>()
                            for ((document, grant) in chosen) {
                                currentCoroutineContext().ensureActive()
                                updateImport(runId) { it.copy(currentName = document.name, copiedBytes = 0, expectedBytes = document.size) }
                                val item = try {
                                    LocalAudioImportResult(document.name, importOne(targetFolder, document, grant,
                                        refreshing?.stored?.link?.mode ?: mode, flags, refreshing, runId).id)
                                } catch (error: CancellationException) { throw error }
                                catch (error: Exception) {
                                    val failed = failure(error)
                                    LocalAudioImportResult(document.name, error = failed.error, recoveryAction = failed.recoveryAction)
                                }
                                results += item
                                updateImport(runId) { it.copy(processed = results.size, results = results.toList()) }
                                commands.withLock { publish() }
                            }
                            refreshing?.let { refresh -> commands.withLock {
                                currentCoroutineContext().ensureActive()
                                ensureDirectoryCurrent(refresh)
                                store.finishDirectoryRefresh(refresh.stored.link.id,
                                    chosen.map { AndroidLocalAudioDocuments.identity(it.first.uri) }.toSet(),
                                    System.currentTimeMillis().coerceAtLeast(0), failedItems = results.count { it.error != null })
                                publish()
                            } }
                            results.toList()
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) {
                            refreshing?.let { refresh -> commands.withLock {
                                if (directoryCurrent(refresh)) store.setDirectoryError(refresh.stored.link.id, failure(error).error)
                            } }
                            throw error
                        } finally {
                            withContext(NonCancellable) {
                                updateImport(runId) { it.copy(running = false, currentName = null) }
                                commands.withLock {
                                    importingGrants.clear(); importingCopies.clear()
                                    publish()
                                    releaseUnusedGrants()
                                }
                            }
                        }
                    }
                }.let { Result.success(it) }
            } catch (error: CancellationException) {
                updateImport(runId) { it.copy(running = false, currentName = null, cancelled = true) }
                throw error
            } catch (error: Exception) {
                val reason = failure(error)
                Result.failure(reason)
            }
            updateImport(runId) { it.copy(running = false, currentName = null,
                completed = response.isSuccess, error = (response.exceptionOrNull() as? LocalAudioFailure)?.error) }
            if (importing.value.runId == runId) {
                importJob = null // File/grant cleanup above is complete before releasing the slot.
                result(response)
            }
        }
        importJob = job
        job.invokeOnCompletion { if (job.isCancelled) updateImport(runId) {
            it.copy(running = false, currentName = null, cancelled = true)
        } }
        job.start()
        return job
    }

    private suspend fun importOne(folderId: String, document: LocalAudioDocument, grant: Uri, mode: LocalImportMode, flags: Int,
        refreshing: DirectoryRefresh? = null, runId: Long): LocalAudioItem {
        val context = currentCoroutineContext()
        document.readError?.let { throw LocalAudioFailure(it) }
        val identity = AndroidLocalAudioDocuments.identity(document.uri)
        val origin = refreshing?.let { LocalAudioDirectoryImport(it.stored.link.id, identity,
            document.size?.takeIf { size -> size in 1..LocalAudioLimits.MAX_FILE_BYTES }, document.modifiedAtMs) }
        if (refreshing != null) {
            val reused = commands.withLock {
                context.ensureActive()
                ensureDirectoryCurrent(refreshing)
                val previous = store.directoryEntry(refreshing.stored.link.id, identity)
                val stored = previous?.let { store.source(it.audioId) }
                if (mode == LocalImportMode.COPY && previous != null && stored?.source?.mode == LocalImportMode.COPY &&
                    document.size != null && document.modifiedAtMs != null && previous.origin.sizeBytes == document.size &&
                    previous.origin.modifiedAtMs == document.modifiedAtMs &&
                    runCatching { files.resolve(stored.source.location).length() == document.size }.getOrDefault(false)) {
                    store.importAudio(folderId, stored.item.metadata.copy(fileName = document.name,
                        sizeBytes = document.size, modifiedAtMs = document.modifiedAtMs), stored.source, origin)
                        .also { nextSourceObservation(it.id) }
                } else null
            }
            if (reused != null) return reused
        }
        if (mode == LocalImportMode.REFERENCE) commands.withLock {
            acquireSourceRead(grant, flags)
            importingGrants.add(grant.toString())
        }
        val metadata = documents.metadataForImport(document, mode) { context.ensureActive() }
        val dsfInfo = if (DsfPlaybackSupport.isCandidate(document.name, document.mime)) {
            val header = documents.readRange(document.uri, 0, DsfFileParser.HEADER_SIZE) { context.ensureActive() }
                ?: throw LocalAudioFailure(LocalAudioError.READ_FAILED)
            try { DsfFileParser.parse(header, document.size).also {
                if (!DsfPlaybackSupport.supports(it) || it.durationMs != metadata.durationMs)
                    throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
            } }
            catch (_: DsfParseFailure) { throw LocalAudioFailure(LocalAudioError.UNSUPPORTED_AUDIO) }
        } else null
        context.ensureActive()
        if (mode == LocalImportMode.REFERENCE) {
            documents.open(document.uri).use { if (it.read() < 0) throw LocalAudioFailure(LocalAudioError.READ_FAILED) }
            val lease = if (autoArtworkEnabled() && dsfInfo == null) artwork?.prepare(document.uri, metadata) { context.ensureActive() } else null
            try {
                return commands.withLock {
                    context.ensureActive()
                    refreshing?.let(::ensureDirectoryCurrent)
                    store.importAudio(folderId, metadata.copy(artworkKey = lease?.key), LocalAudioSource(mode, document.uri.toString(), identity, grant.toString()), origin)
                        .also { nextSourceObservation(it.id) }
                }
            } finally { releaseArtwork(lease) }
        }
        var lastPublished = 0L
        val copied = files.copy({ documents.open(document.uri) }, document.size, { context.ensureActive() }) { bytes ->
            val now = System.nanoTime()
            if (now - lastPublished >= 200_000_000L) {
                updateImport(runId) { it.copy(copiedBytes = bytes) }
                lastPublished = now
            }
        }
        return withContext(NonCancellable) {
            commands.withLock { importingCopies.add(copied.name) }
            var lease: LocalArtworkLease? = null
            try {
                context.ensureActive()
                var copyMetadata = metadata
                if (dsfInfo != null) {
                    val tags = validateDsfCopy(files.resolve(copied.name), dsfInfo) { context.ensureActive() }
                    copyMetadata = metadata.copy(title = LocalAudioNames.clean(tags?.title),
                        creator = LocalAudioNames.clean(tags?.artist))
                    val after = documents.describe(document.uri)
                    after.readError?.let { throw LocalAudioFailure(it) }
                    if (after.size != document.size || after.modifiedAtMs != document.modifiedAtMs)
                        throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
                }
                lease = if (autoArtworkEnabled() && dsfInfo == null) artwork?.prepare(Uri.fromFile(files.resolve(copied.name)), metadata) { context.ensureActive() } else null
                commands.withLock {
                    context.ensureActive()
                    refreshing?.let(::ensureDirectoryCurrent)
                    if (files.resolve(copied.name).length() != copied.bytes) throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
                    val item = store.importAudio(folderId, copyMetadata.copy(sizeBytes = copied.bytes, artworkKey = lease?.key), LocalAudioSource(mode, copied.name, copied.name), origin)
                    nextSourceObservation(item.id)
                    updateImport(runId) { it.copy(copiedBytes = copied.bytes) }
                    item
                }
            } finally {
                lease?.close()
                commands.withLock {
                    importingCopies.remove(copied.name)
                    // Keep any uncertain DB commit rather than risk deleting another entry's audio.
                    if (copied.created && runCatching { copied.name !in store.retainedCopyNames() }.getOrDefault(false)) files.discard(copied.name)
                    pruneArtwork()
                }
            }
        }
    }

    private fun directoryCurrent(value: DirectoryRefresh): Boolean {
        val current = store.directory(value.stored.link.id) ?: return false
        return current.treeUri == value.stored.treeUri && current.link.folderId == value.stored.link.folderId &&
            current.link.mode == value.stored.link.mode && current.link.includeSubdirectories == value.stored.link.includeSubdirectories &&
            (directoryEpochs[current.link.id] ?: 0) == value.epoch
    }
    private fun ensureDirectoryCurrent(value: DirectoryRefresh) {
        if (!directoryCurrent(value)) throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
    }

    private fun <T> submit(result: (Result<T>) -> Unit, action: suspend () -> T): Job = scope.launch {
        initialization.join()
        val response = withContext(Dispatchers.IO) {
            commands.withLock {
                try {
                    requireReady()
                    val value = action()
                    publish()
                    Result.success(value)
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    val reason = failure(error)
                    mutable.value = mutable.value.copy(error = reason.error)
                    Result.failure(reason)
                }
            }
        }
        result(response)
    }

    private fun publish() {
        mutable.value = store.read().copy(ready = true)
        searching.update { search ->
            val page = search.page
            if (page != null && !page.isCurrent(mutable.value)) search.copy(error = LocalAudioError.STALE_STATE)
            else search
        }
    }
    private fun requireReady() { if (!state.value.ready) throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED) }
    private fun releaseUnusedGrants() { documents.releaseUnused(store.retainedGrantUris() + importingGrants + transferringGrants) }
    /** Called under commands; a failed DB read must never collect possibly referenced images. */
    private fun pruneArtwork() { runCatching { artwork?.prune(store.retainedArtworkKeys()) } }
    private suspend fun releaseArtwork(lease: LocalArtworkLease?) = withContext(NonCancellable) {
        lease?.close()
        commands.withLock { pruneArtwork() }
    }
    private fun failure(error: Exception): LocalAudioFailure = when (error) {
        is CancellationException -> throw error
        is LocalAudioFailure -> error
        is SecurityException -> LocalAudioFailure(LocalAudioError.PERMISSION_REQUIRED)
        is IllegalArgumentException -> LocalAudioFailure(LocalAudioError.INVALID_NAME)
        else -> LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
    }

    companion object {
        internal fun sourceVersion(value: StoredLocalAudio): String {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { output ->
                listOf(value.source.mode.name, value.source.location, value.source.identity, value.source.grantUri.orEmpty()).forEach { field ->
                    val utf8 = field.toByteArray(Charsets.UTF_8)
                    output.writeInt(utf8.size); output.write(utf8)
                }
                output.writeLong(value.item.metadata.sizeBytes ?: -1)
                // Preserve legacy hashes until a provider supplies a reliable modification value.
                if (value.source.mode == LocalImportMode.REFERENCE) value.item.metadata.modifiedAtMs?.let { output.writeLong(it) }
                value.item.segment?.let {
                    output.writeUTF("cue-segment-v1"); output.writeLong(it.startUs); output.writeLong(it.endUs); output.writeInt(it.trackNumber)
                }
            }
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}

internal class LocalAudioPlaybackSource(val item: LocalAudioItem, private val uri: Uri) {
    fun readUri(): Uri = uri
    override fun toString(): String = "LocalAudioPlaybackSource(redacted)"
}
