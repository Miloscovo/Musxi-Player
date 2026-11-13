package tech.musxi.playback

import android.app.PendingIntent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionError
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*
import tech.musxi.core.playback.*
import tech.musxi.core.library.SongSummary
import tech.musxi.core.localaudio.*

/** Owns the actual timeline. Signed URLs never enter MediaItems or controller state. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class MusxiPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var sleepTimer: ServiceSleepTimer? = null
    private lateinit var sources: QueueSources
    private lateinit var provider: PlaybackSourceProvider
    private var outputs: AndroidPlaybackOutputs? = null
    private data class QualitySwitch(val change: QueueSources.QualityChange, val original: MediaItem,
        val result: SettableFuture<SessionResult>, val platform: tech.musxi.core.account.Platform)
    private var qualitySwitch: QualitySwitch? = null
    private var qualityRequest = 0L
    private val qualityTimeout = Runnable { finishQualitySwitch(PlaybackFailure.TIMEOUT) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private var mediaCardLyricsPublisher: MediaCardLyricsPublisher? = null
    private val recovery = QueueRecoveryPolicy()
    private var activeGeneration: Long? = null
    private var activeMediaId: String? = null
    private var lastTicket: String? = null
    private var failure: PlaybackFailure? = null
    private var lastSkipped: PlaybackFailure? = null
    private var consecutiveSkips = 0
    private var skippedCount = 0
    private var refreshCount = 0
    private var automaticTransitions = 0
    private var destroyed = false
    private val host get() = application as PlaybackHost
    private val selection get() = host.sourceSelection
    private var queueSongs: List<SongSummary> = emptyList()
    private var localEntries: List<LocalAudioQueueEntry> = emptyList()
    private var localSpeedRequest = 0L
    private var localFolderId: String? = null
    private var localContinue = true
    private var localSources: LocalQueueSources? = null
    private val localPositions = mutableMapOf<String, Long>()
    private var localFailure: LocalAudioError? = null
    private var localPositionOverride = false
    private var localEndTicket: String? = null
    private var localCheckpointEnabled = false
    private var localUseStoredProgress = false
    private var localRestoreActive = false
    private val isLocal get() = localEntries.isNotEmpty()
    private var queueEpoch: String = java.util.UUID.randomUUID().toString()
    private val appliedQueueBatches = object : LinkedHashMap<String, Unit>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > QueueSources.MAX_QUEUE_SIZE
    }
    private var editingQueue = false
    private fun publishQueue(force: Boolean = false) {
        if (editingQueue) return
        val oldLocal = localQueueSnapshot.value
        val localItems = localEntries.map { it.audio }
        val localIndex = if (::player.isInitialized && isLocal) player.currentMediaItemIndex else -1
        val localUpcoming = if (::player.isInitialized && isLocal) upcomingWindowIndices(player.currentTimeline,
            localIndex, player.shuffleModeEnabled, player.repeatMode).filter { it in localEntries.indices } else emptyList()
        if (force || oldLocal.items != localItems || oldLocal.currentIndex != localIndex ||
            oldLocal.folderId != localFolderId || oldLocal.upcoming != localUpcoming) {
            localQueueSnapshot.value = LocalAudioQueueState(oldLocal.revision + 1, localFolderId,
                java.util.Collections.unmodifiableList(localItems), localIndex, java.util.Collections.unmodifiableList(localUpcoming))
        }
        val previous = queueSnapshot.value
        val currentIndex = if (::player.isInitialized && queueSongs.isNotEmpty()) player.currentMediaItemIndex else -1
        val upcoming = if (::player.isInitialized) upcomingWindowIndices(player.currentTimeline, currentIndex,
            player.shuffleModeEnabled, player.repeatMode).filter { it in queueSongs.indices } else emptyList()
        if (force || previous.songs != queueSongs || previous.queueEpoch != queueEpoch ||
            previous.currentIndex != currentIndex || previous.upcoming != upcoming) queueSnapshot.value = PlaybackQueueState(
            previous.revision + 1,
            java.util.Collections.unmodifiableList(queueSongs.map { it.copy(artists = java.util.Collections.unmodifiableList(it.artists.toList())) }),
            queueEpoch, currentIndex, java.util.Collections.unmodifiableList(upcoming))
    }
    private var searchJob: Job? = null
    private var selectionTimer: Job? = null
    private val attemptedSources = mutableSetOf<String>()
    private var offeredSources: AlternativeSources? = null
    private var selectedTicket: String? = null
    private var playingSource: SourceCandidate? = null
    private var prefetchJob: Job? = null
    private var prefetchKey: String? = null
    private var prefetchedSource: Pair<String, SourceCandidate>? = null
    private var prefetchLastStarted = -15_000L
    private var preferences = SourceSwitchPreferences()
    private val sourceFailures = mutableMapOf<tech.musxi.core.account.Platform, PlaybackFailure>()
    private val sourceFailureReasons = mutableMapOf<tech.musxi.core.account.Platform, FailureReason>()
    /** Invalidates delayed recovery work after a user pause/stop/navigation. */
    private var recoveryEpoch = 0L
    private var playbackWanted = false
    private var checkpointReady = false
    private var refreshSessionState: (() -> Unit)? = null
    private var validationHoldForeground = false
    private val foregroundGate = ForegroundPlaybackGate(
        now = { SystemClock.elapsedRealtime() },
        isForeground = { isPlaybackOngoing && !validationHoldForeground },
        schedule = { delayMs, action ->
            val runnable = Runnable(action)
            main.postDelayed(runnable, delayMs)
            val cancel: () -> Unit = { main.removeCallbacks(runnable) }
            cancel
        },
        changed = { refreshSessionState?.invoke() },
        ready = {
            trace("foreground_gate_ready foreground=$isPlaybackOngoing")
            if (sleepTimer?.synchronize() != true && playbackWanted) player.play()
        },
        denied = {
            foregroundStartDenied()
        }
    )
    private var historyTicket: String? = null
    private val checkpointTask = Runnable {
        checkpoint()
        if (!destroyed && player.isPlaying) scheduleCheckpoint(15_000)
    }

    private fun scheduleCheckpoint(delayMs: Long = 200) {
        if (!checkpointReady || destroyed) return
        main.removeCallbacks(checkpointTask)
        main.postDelayed(checkpointTask, delayMs)
    }

    private fun checkpoint(completed: ((Result<Unit>) -> Unit)? = null) {
        if (editingQueue) { completed?.invoke(Result.success(Unit)); return }
        if (checkpointReady && isLocal) {
            val index = player.currentMediaItemIndex
            val id = localEntries.getOrNull(index)?.audio?.id
            if (id == null) { completed?.invoke(Result.failure(LocalAudioFailure(LocalAudioError.NOT_FOUND))); return }
            val ended = player.playbackState == Player.STATE_ENDED || (localEndTicket != null && localEndTicket == currentTicket())
            val position = if (ended) 0 else player.currentPosition.coerceIn(0, LocalAudioLimits.MAX_POSITION_MS)
            if (localCheckpointEnabled) localPositions[id] = position
            val time = System.currentTimeMillis().coerceAtLeast(0)
            val saved = SavedLocalAudioQueue(localFolderId, localEntries.map {
                SavedLocalAudioEntry(it.audio.id, it.sourceVersion, localPositions[it.audio.id] ?: it.resumePositionMs)
            }, index, position, when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> "ONE"; Player.REPEAT_MODE_ALL -> "ALL"; else -> "OFF"
            }, player.shuffleModeEnabled, localShuffleOrder(), localContinue, time,
                useStoredProgress = localUseStoredProgress || localCheckpointEnabled)
            val progress = if (localCheckpointEnabled) LocalAudioProgress(id, position, ended, time) else null
            host.localAudioPlayback?.checkpointQueue(saved, progress, localRestoreActive) { response ->
                if (completed != null) main.post { completed(response) }
            } ?: completed?.invoke(Result.failure(LocalAudioFailure(LocalAudioError.NOT_FOUND)))
            return
        }
        if (!checkpointReady || queueSongs.isEmpty() || player.currentMediaItemIndex !in queueSongs.indices) {
            completed?.invoke(Result.success(Unit)); return
        }
        val generation = activeGeneration ?: run { completed?.invoke(Result.failure(IllegalStateException())); return }
        val index = player.currentMediaItemIndex
        val time = System.currentTimeMillis().coerceAtLeast(0)
        val position = if (player.playbackState == Player.STATE_ENDED) 0 else player.currentPosition.coerceIn(0, 86_400_000)
        val saved = SavedPlaybackQueue(queueSongs, index, position, when (player.repeatMode) {
            Player.REPEAT_MODE_ONE -> "ONE"; Player.REPEAT_MODE_ALL -> "ALL"; else -> "OFF"
        }, player.shuffleModeEnabled, time)
        val ticket = currentTicket()
        val original = queueSongs[index]
        val selected = playingSource?.song ?: original
        val metadata = player.currentMediaItem?.mediaMetadata
        val source = selected.copy(title = metadata?.title?.toString() ?: selected.title,
            album = metadata?.albumTitle?.toString() ?: selected.album,
            durationMs = player.duration.takeIf { it > 0 } ?: selected.durationMs,
            coverUrl = metadata?.artworkUri?.toString() ?: selected.coverUrl)
        val historyKey = ticket?.let { it + ":" + source.key.stableId }
        val recent = if (player.isPlaying && historyTicket != historyKey) {
            historyTicket = historyKey
            val current = ticket?.let(sources::currentSource)
            if (selectedTicket == ticket && current?.first == source.key.stableId) {
                host.onAlternativeSourcePlayed(original, generation, SourceCandidate(source, current.second))
            }
            RecentPlay(original, source, time)
        } else null
        if (completed == null) host.onPlaybackCheckpoint(generation, saved, recent)
        else host.onPlaybackCheckpointSaved(generation, saved, recent, completed)
    }

    private fun saveLocalPosition(id: String, position: Long, ended: Boolean, completed: ((Result<Unit>) -> Unit)? = null) {
        val bounded = if (ended) 0 else position.coerceIn(0, LocalAudioLimits.MAX_POSITION_MS)
        localPositions[id] = if (ended) 0 else bounded
        val localHost = host.localAudioPlayback
        if (localHost == null) { completed?.invoke(Result.failure(LocalAudioFailure(LocalAudioError.NOT_FOUND))); return }
        localHost.checkpoint(localFolderId, id, bounded, ended) { response ->
            if (completed != null) main.post { completed(response) }
        }
    }

    private fun clearLocalQueue() {
        localSources?.clear()
        localEntries = emptyList(); localFolderId = null; localPositions.clear(); localFailure = null; localEndTicket = null
        localCheckpointEnabled = false
        localUseStoredProgress = false; localRestoreActive = false
        player.playbackParameters = PlaybackParameters.DEFAULT
    }

    /** Apply preferences before the next slot starts; positions remain native media time. */
    private fun applyCurrentSpeed() {
        val entry = localEntries.getOrNull(player.currentMediaItemIndex)
        val speed = entry?.let { host.localAudioPlayback?.currentItem(it)?.playbackSpeed ?: it.audio.playbackSpeed } ?: 1f
        val next = PlaybackParameters(speed, 1f)
        if (player.playbackParameters != next) player.playbackParameters = next
    }

    private fun refreshLocalPreferences() {
        if (!isLocal || editingQueue) return
        val localHost = host.localAudioPlayback ?: return
        localEntries = localEntries.map { entry ->
            val latest = localHost.currentItem(entry)
            if (latest == entry.audio) entry else LocalAudioQueueEntry(latest, entry.sourceVersion, entry.resumePositionMs)
        }
        for (index in localEntries.indices) {
            val item = player.getMediaItemAt(index)
            val next = localMetadata(localEntries[index])
            if (item.mediaMetadata != next) player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(next).build())
        }
        applyCurrentSpeed()
        publishQueue(); publish()
    }

    /** Persist standard Controller speed commands and fence delayed completions to their slot. */
    private fun changeLocalSpeed(parameters: PlaybackParameters): ListenableFuture<*> {
        val entry = localEntries.getOrNull(player.currentMediaItemIndex)
        if (!isLocal || entry == null || parameters.pitch != 1f ||
            runCatching { LocalAudioLimits.requirePlaybackSpeed(parameters.speed) }.isFailure) {
            return Futures.immediateFailedFuture<Any>(IllegalArgumentException("Invalid local playback speed"))
        }
        val localHost = host.localAudioPlayback
            ?: return Futures.immediateFailedFuture<Any>(IllegalStateException("Local preferences unavailable"))
        val result = SettableFuture.create<Unit>()
        val request = ++localSpeedRequest
        val epoch = queueEpoch
        val ticket = currentTicket()
        fun complete(saved: Result<Unit>) {
            main.post {
                if (saved.isFailure) result.setException(saved.exceptionOrNull()!!)
                else if (destroyed || request != localSpeedRequest || queueEpoch != epoch || currentTicket() != ticket) {
                    result.setException(IllegalStateException("Local playback changed"))
                } else {
                    refreshLocalPreferences()
                    player.playbackParameters = parameters
                    publish()
                    result.set(Unit)
                }
            }
        }
        if (localHost.currentItem(entry).playbackSpeed == parameters.speed) complete(Result.success(Unit))
        else localHost.setPlaybackSpeed(entry.audio.id, parameters.speed, ::complete)
        return result
    }

    private fun localShuffleOrder(): List<Int> {
        val timeline = player.currentTimeline
        val order = mutableListOf<Int>()
        var index = timeline.getFirstWindowIndex(true)
        while (index != C.INDEX_UNSET && order.size < localEntries.size) {
            order += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        return order.takeIf { it.size == localEntries.size && it.toSet() == localEntries.indices.toSet() }
            ?: localEntries.indices.toList()
    }


    override fun onCreate() {
        super.onCreate()
        setListener(object : MediaSessionService.Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                if (foregroundGate.pending) foregroundStartDenied()
            }
        })
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            validationService = java.lang.ref.WeakReference(this)
        provider = (application as PlaybackHost).playbackSources
        sources = QueueSources(provider)
        localSources = host.localAudioPlayback?.let(::LocalQueueSources)
        scope.launch { host.sourcePreferences.collect { next ->
            if (next != preferences) { cancelPrefetch(); preferences = next; schedulePrefetch() }
        } }
        val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(15_000).setReadTimeoutMs(15_000)
        val dataSource = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { request ->
            val ticket = request.uri.toString()
            if (request.uri.scheme == "musxi-local") {
                val uri = localSources?.resolve(ticket) ?: throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
                return@Factory request.withUri(Uri.parse(uri))
            }
            val resolved = sources.resolve(ticket)
            main.post { if (!destroyed && resolved.isCurrent()) publish() }
            resolved.metadata?.let { metadata ->
                main.post {
                    if (destroyed || activeGeneration == null || !resolved.isCurrent()) return@post
                    val index = (0 until player.mediaItemCount).firstOrNull {
                        player.getMediaItemAt(it).localConfiguration?.uri?.toString() == ticket
                    } ?: return@post
                    // Keep resolved original metadata for every later restoration/search.
                    // This runs only after the ticket fence; candidates cannot enter this slot.
                    queueSongs.getOrNull(index)?.let { original ->
                        val refreshed = PlaybackMetadataSnapshot.refreshOriginal(original, metadata)
                        if (refreshed !== original) queueSongs = queueSongs.toMutableList().apply { this[index] = refreshed }
                    }
                    updateMetadata(index, metadata)
                }
            }
            request.withUri(Uri.parse(resolved.url))
        }
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(dataSource)
            .setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy(0) {
                override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long = C.TIME_UNSET
            })).setWakeMode(C.WAKE_MODE_NETWORK).build().apply {
            setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
            // Keep the default renderers for future video surfaces, but select no video decoder.
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    if (!isLocal || editingQueue || localPositionOverride) return
                    localCheckpointEnabled = true
                    val oldId = oldPosition.mediaItem?.mediaId?.removePrefix(LOCAL_MEDIA_PREFIX)
                    val nextId = newPosition.mediaItem?.mediaId?.removePrefix(LOCAL_MEDIA_PREFIX)
                    if (oldId != null && localEntries.any { it.audio.id == oldId }) {
                        val ended = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION || (localEndTicket != null &&
                            localEndTicket == oldPosition.mediaItem?.localConfiguration?.uri?.toString())
                        saveLocalPosition(oldId, oldPosition.positionMs, ended)
                    }
                    localEndTicket = null
                    if (oldId != nextId && !localPositionOverride && newPosition.positionMs == 0L && localContinue &&
                        reason in setOf(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, Player.DISCONTINUITY_REASON_SEEK)) {
                        val resume = localPositions[nextId] ?: 0
                        if (resume > 0) {
                            localPositionOverride = true
                            try { player.seekTo(newPosition.mediaItemIndex, resume) }
                            finally { localPositionOverride = false }
                        }
                    }
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (editingQueue) return
                    applyCurrentSpeed()
                    val ticket = mediaItem?.localConfiguration?.uri?.toString()
                    if (ticket != lastTicket) {
                        abandonQualitySwitch()
                        historyTicket = null
                        val previousTicket = lastTicket
                        lastTicket = ticket
                        if (selectedTicket == previousTicket) {
                            val previous = (0 until player.mediaItemCount).firstOrNull {
                                player.getMediaItemAt(it).localConfiguration?.uri?.toString() == previousTicket
                            }
                            val original = previous?.let(queueSongs::getOrNull)
                            if (original != null) {
                                updateMetadata(previous, original)
                            }
                        }
                        previousTicket?.let(sources::resetSource)
                        cancelSelection()
                        attemptedSources.clear(); offeredSources = null; selectedTicket = null; playingSource = null; sourceFailures.clear(); sourceFailureReasons.clear()
                        prefetchedSource?.takeIf { it.first == ticket }?.second?.let { candidate ->
                            if (preferences.automatic && provider.generationFor(candidate.song.key.stableId) == candidate.accountGeneration) {
                                selectedTicket = ticket; playingSource = candidate
                                attemptedSources += candidate.song.key.stableId
                                val original = queueSongs.getOrNull(player.currentMediaItemIndex)
                                if (original != null) {
                                    val token = selection.begin(original, {}, {})
                                    selection.offer(token, AlternativeSources(original, listOf(candidate)))
                                    updateMetadata(player.currentMediaItemIndex, candidate.song)
                                }
                            } else ticket?.let(sources::resetSource)
                            prefetchedSource = null
                        }
                        recovery.reset(); failure = null; localFailure = null
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) automaticTransitions++
                        publish()
                    } else if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                        // Repeating a queue slot keeps its selected source and bounded retry budget.
                        automaticTransitions++
                        historyTicket = null
                        publish()
                    }
                }
                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    if (timeline.isEmpty && !editingQueue) {
                        cancelSelection(); sources.clear(); clearLocalQueue(); queueSongs = emptyList(); activeGeneration = null; lastTicket = null
                    }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        if (isLocal) localCheckpointEnabled = true
                        playingSource?.let { selection.confirmPlayback(it.song.key) }
                        trace("playing source=${selection.state.value.selected?.song?.key?.platform?.name ?: "ORIGINAL"}"); consecutiveSkips = 0; checkpoint()
                    }
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    trace("ready=$playWhenReady reason=$reason")
                    if (isLocal && !playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                        localEndTicket = currentTicket()
                        checkpoint()
                    }
                    if (!playWhenReady && reason in setOf(Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
                            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)) {
                        if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) playbackWanted = false
                        checkpoint()
                        if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) cancelPendingSelection()
                    }
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) finishQualitySwitch()
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (qualitySwitch != null) finishQualitySwitch(classify(error)) else recover(error)
                }
                override fun onEvents(player: Player, events: Player.Events) { publishQueue(); scheduleCheckpoint(); schedulePrefetch() }
            })
        }
        sleepTimer = ServiceSleepTimer(player, main) {
            playbackWanted = false
            recoveryEpoch++
            cancelPendingSelection()
            player.pause()
            checkpoint()
            publish()
        }
        // STOP takes effect synchronously in the request callback, alongside cancellation.
        // Media3 batches the later Player.stop call. Suppress that duplicate so an old STOP
        // cannot stop a newer queue accepted between the request and its batched execution.
        val sessionPlayer = object : ForwardingSimpleBasePlayer(player) {
            fun refresh() = invalidateState()
            override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> =
                changeLocalSpeed(playbackParameters)
            override fun getState(): SimpleBasePlayer.State {
                val raw = super.getState()
                // DataSource failures can contain a private file/document URI in their cause.
                val state = if ((isLocal || player.currentMediaItem?.mediaId?.startsWith(LOCAL_MEDIA_PREFIX) == true) &&
                    raw.playerError != null) raw.buildUpon().setPlayerError(
                    PlaybackException("Local audio unavailable", null, raw.playerError!!.errorCode)).build() else raw
                val waiting = playbackWanted && failure == null &&
                    (foregroundGate.pending ||
                        (selection.state.value.phase != SourceSelectionPhase.FAILED &&
                            state.playerError?.let(::isSourceSelectionError) == true))
                return if (waiting) state.buildUpon().setPlayerError(null)
                    .setPlaybackState(Player.STATE_BUFFERING)
                    .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build() else state
            }
            override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
                if (playWhenReady) sleepTimer?.explicitPlay()
                if (isLocal && playWhenReady) localRestoreActive = true
                playbackWanted = playWhenReady
                if (!playWhenReady) cancelPendingSelection()
                else retryFromCommand()
                // A controller may send play() without prepare(), including while
                // the session presents an outstanding source selection as buffering.
                if (playWhenReady) {
                    if (isLocal && (localEndTicket == currentTicket() || player.playbackState == Player.STATE_ENDED)) {
                        localEndTicket = null
                        localPositionOverride = true
                        try { player.seekTo(player.currentMediaItemIndex, 0) } finally { localPositionOverride = false }
                    }
                    beginGatedPlayback()
                    return Futures.immediateVoidFuture()
                }
                return super.handleSetPlayWhenReady(playWhenReady)
            }
            override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
                if (isLocal && positionMs != C.TIME_UNSET && positionMs !in 0..LocalAudioLimits.MAX_POSITION_MS)
                    return Futures.immediateVoidFuture()
                val resume = playbackWanted && (foregroundGate.pending || player.playbackState == Player.STATE_IDLE)
                if (isLocal) checkpoint()
                val explicitLocalSeek = isLocal && seekCommand in setOf(Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                if (explicitLocalSeek) { localPositionOverride = true; localEndTicket = null }
                val result = try { super.handleSeek(mediaItemIndex, positionMs, seekCommand) }
                    finally { if (explicitLocalSeek) localPositionOverride = false }
                if (explicitLocalSeek) { localCheckpointEnabled = true; localRestoreActive = true; checkpoint() }
                if (resume) beginGatedPlayback()
                return result
            }
            override fun handlePrepare(): ListenableFuture<*> {
                if (playbackWanted) beginGatedPlayback()
                else { player.pause(); player.prepare() }
                return Futures.immediateVoidFuture()
            }
            override fun handleStop(): ListenableFuture<*> {
                trace("stop_batch_ignored")
                return Futures.immediateVoidFuture()
            }
        }
        refreshSessionState = sessionPlayer::refresh
        // Media3's default CacheBitmapLoader(DataSourceBitmapLoader(context)) uses its
        // own public HTTP data source, independent of the opaque audio resolver above.
        session = MediaSession.Builder(this, sessionPlayer).setCallback(object : MediaSession.Callback {
            override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo,
                intent: android.content.Intent): Boolean {
                @Suppress("DEPRECATION")
                val key = intent.getParcelableExtra<android.view.KeyEvent>(android.content.Intent.EXTRA_KEY_EVENT)
                trace("media_button code=${key?.keyCode ?: -1} action=${key?.action ?: -1} dismissed=${intent.getBooleanExtra(androidx.media3.session.MediaNotification.NOTIFICATION_DISMISSED_EVENT_KEY, false)}")
                return false
            }
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                if (!isAppController(controller) && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
                val commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                commands.remove(Player.COMMAND_SET_MEDIA_ITEM).remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                val accepted = MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailablePlayerCommands(commands.build())
                if (isAppController(controller)) accepted.setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                    .buildUpon().add(SessionCommand(COMMAND_APPLY_QUEUE, Bundle.EMPTY))
                    .add(SessionCommand(COMMAND_QUALITIES, Bundle.EMPTY))
                    .add(SessionCommand(COMMAND_SET_QUALITY, Bundle.EMPTY))
                    .add(SessionCommand(COMMAND_FIND_SOURCES, Bundle.EMPTY))
                    .add(SessionCommand(COMMAND_CHECKPOINT, Bundle.EMPTY))
                    .add(SessionCommand(COMMAND_SLEEP_TIMER, Bundle.EMPTY))
                    .add(SessionCommand(COMMAND_SELECT_OUTPUT, Bundle.EMPTY)).build())
                return accepted.build()
            }
            override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int {
                trace("command=$playerCommand app=${isAppController(controller)} notification=${session.isMediaNotificationController(controller)}")
                val retryOriginal = shouldRetryOriginal()
                if (playerCommand == Player.COMMAND_SET_MEDIA_ITEM || playerCommand == Player.COMMAND_CHANGE_MEDIA_ITEMS)
                    return SessionError.ERROR_PERMISSION_DENIED
                if (playerCommand in setOf(Player.COMMAND_STOP, Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                        Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE)) {
                    recoveryEpoch++
                    if (playerCommand != Player.COMMAND_PLAY_PAUSE &&
                        selection.state.value.phase in setOf(SourceSelectionPhase.SEARCHING, SourceSelectionPhase.CHOOSING))
                        cancelSelection()
                }
                if (playerCommand == Player.COMMAND_STOP) {
                    sleepTimer?.cancel()
                    abandonQualitySwitch()
                    checkpoint()
                    localRestoreActive = false
                    queueCommands.invalidate()
                    queueEpoch = java.util.UUID.randomUUID().toString()
                    appliedQueueBatches.clear()
                    playbackWanted = false
                    recoveryEpoch++
                    player.pause(); player.stop()
                    trace("stop_applied")
                    failure = null; lastSkipped = null; publish()
                    (application as PlaybackHost).onPlaybackStopRequested()
                }
                // PLAY_PAUSE identifies both play and pause. Only its actual play handler
                // may restore the original source; a pause must retain the selected source.
                if (playerCommand == Player.COMMAND_PREPARE) retryFromCommand(retryOriginal)
                return super.onPlayerCommandRequest(session, controller, playerCommand)
            }
            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (!isAppController(controller))
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                when (customCommand.customAction) {
                    COMMAND_SLEEP_TIMER -> {
                        val timer = sleepTimer ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                        val mode = args.getString(EXTRA_SLEEP_MODE)
                        val valid = when (mode) {
                            SleepTimerMode.OFF.name -> { timer.cancel(); true }
                            SleepTimerMode.END_OF_TRACK.name -> args.getString(EXTRA_CURRENT_TICKET)?.let(timer::finishCurrent) ?: false
                            SleepTimerMode.AFTER_DURATION.name, SleepTimerMode.AFTER_DURATION_FINISH_TRACK.name -> {
                                val duration = args.getLong(EXTRA_SLEEP_DURATION, -1)
                                if (duration !in 1..SleepTimerPolicy.MAX_DURATION_MS) false
                                else { timer.startDuration(duration, mode == SleepTimerMode.AFTER_DURATION_FINISH_TRACK.name); true }
                            }
                            else -> false
                        }
                        return Futures.immediateFuture(SessionResult(if (valid) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE))
                    }
                    COMMAND_CHECKPOINT -> {
                        val result = SettableFuture.create<SessionResult>()
                        checkpoint { saved -> result.set(SessionResult(if (saved.isSuccess)
                            SessionResult.RESULT_SUCCESS else SessionError.ERROR_IO)) }
                        return result
                    }
                    COMMAND_FIND_SOURCES -> {
                        if (isLocal) return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                        val ticket = currentTicket()
                        if (ticket == null || args.getString(EXTRA_CURRENT_TICKET) != ticket ||
                            activeGeneration != activeMediaId?.let(provider::generationFor) || qualitySwitch != null)
                            return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                        offerAlternative(ticket, PlaybackFailure.UNAVAILABLE, manual = true)
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    COMMAND_QUALITIES, COMMAND_SET_QUALITY -> return qualityCommand(customCommand.customAction, args)
                    COMMAND_SELECT_OUTPUT -> {
                        val result = SettableFuture.create<SessionResult>()
                        outputs?.selectOutput(args.getString(EXTRA_OUTPUT_ID).orEmpty()) { response ->
                            result.set(if (response.isSuccess) SessionResult(SessionResult.RESULT_SUCCESS) else
                                SessionResult(SessionError.ERROR_NOT_SUPPORTED, Bundle().apply {
                                    putString(EXTRA_OUTPUT_FAILURE, (response.exceptionOrNull() as? OutputSelectionException)?.category?.name
                                        ?: OutputFailure.REJECTED.name)
                                }))
                        } ?: result.set(SessionResult(SessionError.ERROR_INVALID_STATE))
                        return result
                    }
                    COMMAND_APPLY_QUEUE -> Unit
                    else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                }
                val token = args.getLong(EXTRA_QUEUE_REVISION, Long.MIN_VALUE)
                if (!queueCommands.isPending(token)) return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                return Futures.immediateFuture(applyQueue(token))
            }
            override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> =
                Futures.immediateFailedFuture(SecurityException("Replace the queue through its owning controller"))
            override fun onSetMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                return rejected()
            }
        }).apply {
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                setSessionActivity(PendingIntent.getActivity(this@MusxiPlaybackService, 0, it,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
        }.build()
        mediaCardLyricsPublisher = MediaCardLyricsPublisher(player,
            androidx.media3.session.PlatformSessionAccess.getPlatformSession(requireNotNull(session)), main) {
            if (isLocal) null else currentTicket()?.let(sources::currentSource)
                ?.takeIf { provider.generationFor(it.first) == it.second }?.first
        }
        scope.launch { host.mediaCardLyrics.collect { mediaCardLyricsPublisher?.update(it) } }
        scope.launch { host.localAudioPlayback?.changes?.collect { refreshLocalPreferences() } }
        scope.launch { selection.state.collect { sessionPlayer.refresh() } }
        scope.launch {
            provider.authorization.collect {
                if (isLocal) return@collect
                if (activeGeneration != null && activeGeneration != activeMediaId?.let(provider::generationFor)) {
                    host.onPlaybackStopRequested()
                    sleepTimer?.cancel()
                    playbackWanted = false
                    cancelSelection(); sources.clear(); activeGeneration = null
                    queueEpoch = java.util.UUID.randomUUID().toString()
                    appliedQueueBatches.clear()
                    player.stop(); player.clearMediaItems()
                    failure = PlaybackFailure.AUTH_REQUIRED; publish()
                } else if (selectedTicket != null && !sources.sourceAuthorized(selectedTicket!!)) {
                    playbackWanted = false
                    cancelSelection(); currentTicket()?.let(sources::invalidate)
                    player.pause(); player.stop()
                    failure = PlaybackFailure.AUTH_REQUIRED; publish()
                }
            }
        }
        checkpointReady = true
        outputs = AndroidPlaybackOutputs(this, main) { outputSnapshot.value = it; publish() }.also { it.start() }
        val restoreEpoch = queueEpoch
        val restoreRecovery = recoveryEpoch
        host.localAudioPlayback?.restoreQueue { restored ->
            main.post {
                if (!destroyed && queueEpoch == restoreEpoch && recoveryEpoch == restoreRecovery && player.mediaItemCount == 0) {
                    restored.getOrNull()?.let { replaceLocalQueue(QueueCommandMailbox.Action.LocalReplace(it, false)) }
                }
            }
        }
    }

    private fun qualityCommand(command: String, args: Bundle): ListenableFuture<SessionResult> {
        val ticket = currentTicket()
        if (ticket == null || ticket != args.getString(EXTRA_CURRENT_TICKET) || activeGeneration == null ||
            qualitySwitch != null || player.playerError != null)
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
        val epoch = recoveryEpoch
        val expectedSource = sources.currentSource(ticket)
        val request = if (command == COMMAND_SET_QUALITY) ++qualityRequest else qualityRequest
        val result = SettableFuture.create<SessionResult>()
        scope.launch {
            try {
                if (command == COMMAND_QUALITIES) {
                    val options = withContext(Dispatchers.IO) { sources.qualities(ticket) }
                    if (currentTicket() != ticket || sources.currentSource(ticket) != expectedSource ||
                        epoch != recoveryEpoch || destroyed) throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
                    result.set(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                        putStringArrayList(EXTRA_QUALITY_IDS, ArrayList(options.options.map { it.id }))
                        putStringArrayList(EXTRA_QUALITY_NAMES, ArrayList(options.options.map { it.name }))
                        putString(EXTRA_QUALITY_ID, options.current)
                    }))
                } else {
                    val id = args.getString(EXTRA_QUALITY_ID)?.takeIf { it.length <= 64 }
                        ?: throw PlaybackSourceException(PlaybackFailure.PROTOCOL)
                    val change = withContext(Dispatchers.IO) { sources.prepareQuality(ticket, id) }
                    if (currentTicket() != ticket || epoch != recoveryEpoch || request != qualityRequest || destroyed)
                        throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
                    val index = player.currentMediaItemIndex
                    val original = player.getMediaItemAt(index)
                    val source = sources.currentSource(ticket) ?: throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
                    val platform = tech.musxi.core.account.Platform.entries.firstOrNull { it.name.equals(source.first.substringBefore(':'), true) }
                        ?: throw PlaybackSourceException(PlaybackFailure.PROTOCOL)
                    sources.commitQuality(change)
                    qualitySwitch = QualitySwitch(change, original, result, platform)
                    main.postDelayed(qualityTimeout, 20_000)
                    // A fresh cache key forces the media source to reload while its queue URI and slot survive.
                    val position = player.currentPosition
                    player.replaceMediaItem(index, original.buildUpon().setCustomCacheKey(java.util.UUID.randomUUID().toString()).build())
                    player.seekTo(index, position)
                    player.prepare()
                    publish()
                }
            } catch (error: Exception) {
                val category = (error as? PlaybackSourceException)?.failure ?: PlaybackFailure.UNKNOWN
                if (qualitySwitch?.result === result) finishQualitySwitch(category)
                else result.set(qualityFailure(category))
            }
        }.invokeOnCompletion { error ->
            if (error != null && !result.isDone) result.set(qualityFailure(PlaybackFailure.AUTH_REQUIRED))
        }
        return result
    }

    private fun qualityFailure(category: PlaybackFailure) = SessionResult(SessionError.ERROR_IO, Bundle().apply {
        putString(EXTRA_FAILURE, category.name)
    })

    private fun finishQualitySwitch(error: PlaybackFailure? = null) {
        val pending = qualitySwitch ?: return
        qualitySwitch = null
        main.removeCallbacks(qualityTimeout)
        if (currentTicket() != pending.change.uri || destroyed) {
            pending.result.set(qualityFailure(PlaybackFailure.AUTH_REQUIRED)); return
        }
        if (error == null) {
            host.onPlaybackQualityChanged(pending.platform, requireNotNull(pending.change.lease.qualityId))
            pending.result.set(SessionResult(SessionResult.RESULT_SUCCESS))
        } else {
            sources.rollbackQuality(pending.change)
            val position = player.currentPosition
            val index = player.currentMediaItemIndex
            player.replaceMediaItem(index, pending.original.buildUpon().setCustomCacheKey(java.util.UUID.randomUUID().toString()).build())
            player.seekTo(index, position); player.prepare()
            pending.result.set(qualityFailure(error))
        }
        publish()
    }

    private fun abandonQualitySwitch() {
        qualitySwitch?.let {
            sources.rollbackQuality(it.change)
            it.result.set(qualityFailure(PlaybackFailure.AUTH_REQUIRED))
        }
        qualitySwitch = null
        main.removeCallbacks(qualityTimeout)
    }

    private fun applyQueue(token: Long): SessionResult {
        when (val action = queueCommands.take(token)) {
            null -> return SessionResult(SessionError.ERROR_INVALID_STATE)
            QueueCommandMailbox.Action.Clear -> {
                sleepTimer?.cancel()
                abandonQualitySwitch()
                checkpoint()
                host.onPlaybackStopRequested()
                if (isLocal) host.localAudioPlayback?.deactivateQueue(clear = true)
                queueEpoch = java.util.UUID.randomUUID().toString()
                appliedQueueBatches.clear()
                playbackWanted = false
                cancelSelection(); clearLocalQueue(); queueSongs = emptyList(); offeredSources = null; attemptedSources.clear(); selectedTicket = null; playingSource = null
                failure = null; lastSkipped = null; recovery.reset()
                sources.clear(); activeGeneration = null
                player.pause(); player.stop(); player.clearMediaItems(); publish()
            }
            is QueueCommandMailbox.Action.Replace -> {
                val queue = action.queue
                if (queue.songs.isEmpty() || action.positionMs !in 0..86_400_000 ||
                    (action.repeatMode != null && action.repeatMode !in setOf("OFF", "ONE", "ALL")))
                    return SessionResult(SessionError.ERROR_BAD_VALUE)
                val mediaId = queue.songs.first().key.stableId
                val generation = provider.generationFor(mediaId)
                if (generation == null || queue.accountGeneration != generation ||
                    queue.songs.any { !PlaybackSourcePolicy.acceptsMediaId(it.key.stableId) || it.key.platform != queue.songs.first().key.platform })
                    return SessionResult(SessionError.ERROR_BAD_VALUE)
                host.onPlatformPlaybackRequested()
                checkpoint()
                abandonQualitySwitch()
                val uris = try { sources.replace(queue.songs.map { it.key.stableId }, generation) }
                    catch (_: Exception) { return SessionResult(SessionError.ERROR_BAD_VALUE) }
                queueEpoch = java.util.UUID.randomUUID().toString()
                appliedQueueBatches.clear()
                val items = queue.songs.mapIndexed { index, song ->
                    MediaItem.Builder().setMediaId(song.key.stableId).setUri(uris[index])
                        .setMediaMetadata(metadataFor(PlaybackMetadataSnapshot.fromSong(song, song.key.platform))).build()
                }
                sleepTimer?.queueReplaced()
                cancelSelection(); offeredSources = null; attemptedSources.clear(); selectedTicket = null; playingSource = null
                activeGeneration = generation
                activeMediaId = mediaId
                failure = null; lastSkipped = null; skippedCount = 0; refreshCount = 0
                automaticTransitions = 0; consecutiveSkips = 0; recovery.reset()
                editingQueue = true
                try {
                    player.pause(); player.stop()
                    clearLocalQueue()
                    queueSongs = queue.songs
                    historyTicket = null
                    action.repeatMode?.let { player.repeatMode = when (it) {
                        "ONE" -> Player.REPEAT_MODE_ONE; "ALL" -> Player.REPEAT_MODE_ALL; else -> Player.REPEAT_MODE_OFF
                    } }
                    action.shuffleEnabled?.let { player.shuffleModeEnabled = it }
                    player.setMediaItems(items, queue.startIndex, action.positionMs)
                    lastTicket = currentTicket()
                } finally { editingQueue = false }
                playbackWanted = action.playWhenReady
                if (action.playWhenReady) beginGatedPlayback()
                publish()
                trace("queue_applied")
            }
            is QueueCommandMailbox.Action.Jump -> return jumpToQueueIndex(action)
            is QueueCommandMailbox.Action.Edit -> return editQueue(action)
            is QueueCommandMailbox.Action.Insert -> return insertQueue(action)
            is QueueCommandMailbox.Action.InsertBatch -> return insertQueueBatch(action)
            is QueueCommandMailbox.Action.LocalReplace -> return replaceLocalQueue(action)
            is QueueCommandMailbox.Action.LocalJump -> return jumpLocalQueue(action)
            is QueueCommandMailbox.Action.LocalEdit -> return editLocalQueue(action)
        }
        publishQueue(force = true)
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    private fun localMetadata(entry: LocalAudioQueueEntry): MediaMetadata = MediaMetadata.Builder()
        .setTitle(entry.audio.title).setArtist(entry.audio.metadata.creator.orEmpty())
        .setArtworkUri(host.localAudioPlayback?.artworkUri(entry)?.let(Uri::parse))
        .setExtras(Bundle().apply { putString(EXTRA_LOCAL_AUDIO_ID, entry.audio.id) }).build()

    private fun replaceLocalQueue(action: QueueCommandMailbox.Action.LocalReplace): SessionResult {
        val resolver = localSources ?: return SessionResult(SessionError.ERROR_NOT_SUPPORTED)
        val queue = action.queue
        if (host.localAudioPlayback?.isQueueCurrent(queue) != true) return SessionResult(SessionError.ERROR_INVALID_STATE)
        val uris = try { resolver.replace(queue.entries) }
            catch (_: Exception) { return SessionResult(SessionError.ERROR_BAD_VALUE) }
        checkpoint()
        abandonQualitySwitch()
        host.onLocalPlaybackRequested() // Cancel platform fetch/restore, preserve a newer local intent.
        sleepTimer?.queueReplaced()
        cancelSelection(); sources.clear()
        offeredSources = null; attemptedSources.clear(); selectedTicket = null; playingSource = null
        activeGeneration = null; activeMediaId = null; lastTicket = null; historyTicket = null
        queueEpoch = java.util.UUID.randomUUID().toString(); appliedQueueBatches.clear()
        editingQueue = true
        try {
            player.pause(); player.stop()
            queueSongs = emptyList()
            localEntries = queue.entries
            localCheckpointEnabled = false
            localUseStoredProgress = queue.useStoredProgress
            localRestoreActive = true
            localFolderId = queue.folderId
            localContinue = queue.continuePlayback
            localPositions.clear(); queue.entries.forEach { localPositions[it.audio.id] = it.resumePositionMs }
            failure = null; localFailure = null; lastSkipped = null; skippedCount = 0; refreshCount = 0
            automaticTransitions = 0; consecutiveSkips = 0; recovery.reset()
            player.repeatMode = when (queue.repeatMode) {
                "ONE" -> Player.REPEAT_MODE_ONE; "ALL" -> Player.REPEAT_MODE_ALL; else -> Player.REPEAT_MODE_OFF
            }
            player.shuffleModeEnabled = queue.shuffleEnabled
            player.setMediaItems(queue.entries.mapIndexed { index, entry ->
                MediaItem.Builder().setMediaId(LOCAL_MEDIA_PREFIX + entry.audio.id).setUri(uris[index])
                    .setMediaMetadata(localMetadata(entry)).build()
            }, queue.startIndex, queue.positionMs)
            applyCurrentSpeed()
            queue.shuffleOrder?.let { player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(it.toIntArray(), System.nanoTime())) }
            lastTicket = currentTicket()
        } finally { editingQueue = false }
        playbackWanted = action.playWhenReady
        if (action.playWhenReady) beginGatedPlayback()
        publishQueue(force = true); publish()
        // Journal the list immediately; idle installation leaves the audio's old progress untouched.
        checkpoint()
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    private fun localEditable(revision: Long) = isLocal && revision == localQueueSnapshot.value.revision &&
        player.mediaItemCount == localEntries.size

    private fun jumpLocalQueue(action: QueueCommandMailbox.Action.LocalJump): SessionResult {
        if (!localEditable(action.expectedRevision)) return SessionResult(SessionError.ERROR_INVALID_STATE)
        val entry = localEntries.getOrNull(action.index) ?: return SessionResult(SessionError.ERROR_BAD_VALUE)
        checkpoint()
        sleepTimer?.explicitPlay()
        cancelPendingSelection()
        player.pause()
        localPositionOverride = true
        localEndTicket = null
        try { player.seekTo(action.index, if (action.fromStart || !localContinue) 0 else localPositions[entry.audio.id] ?: 0) }
        finally { localPositionOverride = false }
        localCheckpointEnabled = true
        localRestoreActive = true
        failure = null; localFailure = null; recovery.reset()
        playbackWanted = true; beginGatedPlayback()
        publish(); checkpoint()
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    private fun editLocalQueue(action: QueueCommandMailbox.Action.LocalEdit): SessionResult {
        if (!localEditable(action.expectedRevision)) return SessionResult(SessionError.ERROR_INVALID_STATE)
        val plan = try { QueueEdits.plan(localEntries.size, player.currentMediaItemIndex, action.edit) }
            catch (_: IllegalArgumentException) { return SessionResult(SessionError.ERROR_BAD_VALUE) }
        if (plan.order == localEntries.indices.toList()) return SessionResult(SessionResult.RESULT_SUCCESS)
        checkpoint()
        val retained = plan.order.map { player.getMediaItemAt(it).localConfiguration!!.uri.toString() }.toSet()
        val next = plan.order.map(localEntries::get)
        if (plan.currentRemoved) { playbackWanted = false; cancelPendingSelection(); player.pause() }
        editingQueue = true
        try {
            localEntries = next
            if (plan.currentRemoved) {
                // The replacement slot is selected paused; it has not been listened to yet.
                localCheckpointEnabled = false
                localUseStoredProgress = false
            }
            when (val edit = action.edit) {
                is QueueEdit.Move -> player.moveMediaItem(edit.from, edit.to)
                is QueueEdit.Remove -> player.removeMediaItem(edit.index)
                QueueEdit.Clear -> { player.stop(); player.clearMediaItems() }
            }
            localSources?.retain(retained)
            if (plan.currentRemoved && next.isNotEmpty()) {
                val index = plan.currentIndex!!
                player.seekTo(index, if (localContinue) localPositions[next[index].audio.id] ?: 0 else 0)
            }
        } finally { editingQueue = false }
        applyCurrentSpeed()
        if (next.isEmpty()) {
            host.onPlaybackStopRequested()
            host.localAudioPlayback?.deactivateQueue(clear = true)
            sleepTimer?.cancel(); clearLocalQueue(); lastTicket = null
        }
        failure = null; localFailure = null
        publishQueue(force = true); publish()
        if (next.isNotEmpty()) checkpoint()
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    private fun editable(revision: Long): Boolean = revision == queueSnapshot.value.revision &&
        queueSongs.isNotEmpty() && player.mediaItemCount == queueSongs.size &&
        activeGeneration != null && activeGeneration == activeMediaId?.let(provider::generationFor)

    private fun jumpToQueueIndex(action: QueueCommandMailbox.Action.Jump): SessionResult {
        if (!editable(action.expectedRevision)) return SessionResult(SessionError.ERROR_INVALID_STATE)
        if (action.index !in queueSongs.indices) return SessionResult(SessionError.ERROR_BAD_VALUE)
        sleepTimer?.explicitPlay()
        val targetTicket = player.getMediaItemAt(action.index).localConfiguration?.uri?.toString()
            ?: return SessionResult(SessionError.ERROR_INVALID_STATE)
        // A same-slot click is an explicit retry, retaining a healthy selected source.
        // Read retry intent before cancelling a pending search/selection.
        if (action.index == player.currentMediaItemIndex) retryFromCommand()
        cancelPendingSelection()
        player.pause()
        // Keep every slot URI and the timeline intact, including duplicate media IDs.
        // The normal transition listener restores the source of the slot we leave.
        player.seekTo(action.index, 0)
        recovery.reset(); consecutiveSkips = 0; failure = null; lastSkipped = null
        playbackWanted = true
        beginGatedPlayback(targetTicket)
        publish(); checkpoint()
        // Navigation does not edit the queue or advance its revision.
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    private fun editQueue(action: QueueCommandMailbox.Action.Edit): SessionResult {
        if (!editable(action.expectedRevision)) return SessionResult(SessionError.ERROR_INVALID_STATE)
        val plan = try { QueueEdits.plan(queueSongs.size, player.currentMediaItemIndex, action.edit) }
            catch (_: IllegalArgumentException) { return SessionResult(SessionError.ERROR_BAD_VALUE) }
        if (plan.order == queueSongs.indices.toList()) return SessionResult(SessionResult.RESULT_SUCCESS)
        val platform = queueSongs.first().key.platform
        val generation = activeGeneration!!
        val nextSongs = plan.order.map(queueSongs::get)
        val keepUris = plan.order.map { player.getMediaItemAt(it).localConfiguration!!.uri.toString() }.toSet()
        // Invalidate delayed recovery only when its actual slot is removed. Edits around a
        // playing/substituted slot retain its ticket, position, selection and retry budget.
        if (plan.currentRemoved) {
            playbackWanted = false; recoveryEpoch++; cancelSelection()
            offeredSources = null; attemptedSources.clear(); selectedTicket = null; playingSource = null
            failure = null; lastSkipped = null; recovery.reset()
            player.pause()
        }
        editingQueue = true
        try {
            queueSongs = nextSongs
            when (val edit = action.edit) {
                is QueueEdit.Move -> player.moveMediaItem(edit.from, edit.to)
                is QueueEdit.Remove -> player.removeMediaItem(edit.index)
                QueueEdit.Clear -> { player.stop(); player.clearMediaItems() }
            }
            sources.retain(keepUris)
            if (plan.currentRemoved && nextSongs.isNotEmpty()) {
                // Explicit removal chooses the original next/previous item and leaves it paused.
                player.seekTo(plan.currentIndex!!, 0)
            }
        } finally { editingQueue = false }
        if (nextSongs.isEmpty()) {
            sleepTimer?.cancel()
            host.onPlaybackStopRequested()
            queueEpoch = java.util.UUID.randomUUID().toString()
            appliedQueueBatches.clear()
        }
        publishQueue(force = true); publish()
        if (nextSongs.isEmpty()) {
            activeGeneration = null; activeMediaId = null; lastTicket = null; historyTicket = null
            host.onPlaybackQueueCleared(platform, generation)
        } else checkpoint()
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    private fun insertQueue(action: QueueCommandMailbox.Action.Insert): SessionResult {
        if (!editable(action.expectedRevision)) return SessionResult(SessionError.ERROR_INVALID_STATE)
        val song = action.song
        if (queueSongs.size >= QueueSources.MAX_QUEUE_SIZE || song.key.platform != queueSongs.first().key.platform ||
            !PlaybackSourcePolicy.acceptsMediaId(song.key.stableId) || song.title.length > 4096 ||
            song.album.length > 4096 || song.artists.size > 100 || song.artists.any { it.length > 4096 } ||
            song.durationMs !in 0..86_400_000) return SessionResult(SessionError.ERROR_BAD_VALUE)
        val safe = song.copy(artists = song.artists.toList(), coverUrl = tech.musxi.core.library.ArtworkPolicy.sanitize(song.key.platform, song.coverUrl))
        val uri = try { sources.append(song.key.stableId, activeGeneration!!) }
            catch (_: Exception) { return SessionResult(SessionError.ERROR_BAD_VALUE) }
        val index = if (action.playNext) player.currentMediaItemIndex + 1 else queueSongs.size
        val item = MediaItem.Builder().setMediaId(safe.key.stableId).setUri(uri)
            .setMediaMetadata(metadataFor(PlaybackMetadataSnapshot.fromSong(safe, safe.key.platform))).build()
        editingQueue = true
        try {
            queueSongs = queueSongs.toMutableList().apply { add(index, safe) }
            player.addMediaItem(index, item)
        } finally { editingQueue = false }
        publishQueue(force = true); checkpoint()
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    private fun insertQueueBatch(action: QueueCommandMailbox.Action.InsertBatch): SessionResult {
        if (action.queueEpoch != queueEpoch || queueSongs.isEmpty() || player.mediaItemCount != queueSongs.size ||
            activeGeneration == null || activeGeneration != activeMediaId?.let(provider::generationFor))
            return SessionResult(SessionError.ERROR_INVALID_STATE)
        if (action.batchId.isBlank() || action.batchId.length > 128 ||
            !action.batchId.matches(Regex("[A-Za-z0-9._:-]+")) || action.songs.isEmpty() ||
            action.songs.size > QueueSources.MAX_QUEUE_SIZE)
            return SessionResult(SessionError.ERROR_BAD_VALUE)
        if (appliedQueueBatches["${action.queueEpoch}:${action.batchId}"] != null)
            return SessionResult(SessionResult.RESULT_SUCCESS)
        val platform = queueSongs.first().key.platform
        val generation = activeGeneration ?: return SessionResult(SessionError.ERROR_INVALID_STATE)
        if (queueSongs.size > QueueSources.MAX_QUEUE_SIZE - action.songs.size || action.songs.any { song ->
                song.key.platform != platform || !PlaybackSourcePolicy.acceptsMediaId(song.key.stableId) ||
                    song.title.length > 4096 || song.album.length > 4096 || song.artists.size > 100 ||
                    song.artists.any { it.length > 4096 } || song.durationMs !in 0..86_400_000
            }) return SessionResult(SessionError.ERROR_BAD_VALUE)
        val safeSongs = action.songs.map { song ->
            song.copy(artists = song.artists.toList(),
                coverUrl = tech.musxi.core.library.ArtworkPolicy.sanitize(song.key.platform, song.coverUrl))
        }
        val plan = planQueueBatch(queueSongs, safeSongs, action.at)
            ?: return SessionResult(SessionError.ERROR_BAD_VALUE)
        val existingUris = (0 until player.mediaItemCount).mapNotNull {
            player.getMediaItemAt(it).localConfiguration?.uri?.toString()
        }.toSet()
        val uris = try { sources.appendAll(safeSongs.map { it.key.stableId }, generation) }
            catch (_: Exception) { return SessionResult(SessionError.ERROR_BAD_VALUE) }
        val items = safeSongs.mapIndexed { index, song ->
            MediaItem.Builder().setMediaId(song.key.stableId).setUri(uris[index])
                .setMediaMetadata(metadataFor(PlaybackMetadataSnapshot.fromSong(song, song.key.platform))).build()
        }
        editingQueue = true
        try {
            player.addMediaItems(plan.insertionIndex, items)
            queueSongs = plan.songs
        } catch (_: Exception) {
            runCatching { sources.retain(existingUris) }
            return SessionResult(SessionError.ERROR_INVALID_STATE)
        } finally { editingQueue = false }
        appliedQueueBatches["${action.queueEpoch}:${action.batchId}"] = Unit
        publishQueue(force = true)
        publish()
        checkpoint()
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    private fun recover(error: PlaybackException) {
        foregroundGate.cancel()
        if (isLocal) {
            localFailure = localError(error)
            failure = when (localFailure) {
                LocalAudioError.PERMISSION_REQUIRED -> PlaybackFailure.PERMISSION
                LocalAudioError.NOT_FOUND -> PlaybackFailure.UNAVAILABLE
                else -> PlaybackFailure.PROTOCOL
            }
            // A local recording must never be replaced by a network song or silently skipped.
            playbackWanted = false; player.pause(); checkpoint(); publish()
            return
        }
        val ticket = currentTicket() ?: return
        val epoch = recoveryEpoch
        val selectedFailure = classify(error)
        (playingSource?.song?.key?.platform ?: queueSongs.getOrNull(player.currentMediaItemIndex)?.key?.platform)?.let {
            sourceFailures[it] = selectedFailure
            var cause: Throwable? = error
            var reason = FailureReason.fromFailure(selectedFailure)
            repeat(12) {
                (cause as? PlaybackSourceException)?.let { source -> reason = source.reason }
                cause = cause?.cause
            }
            sourceFailureReasons[it] = reason
            selection.playbackFailure(selection.state.value.revision, it, selectedFailure, reason)
        }
        if (selectedTicket == ticket) {
            val original = queueSongs.getOrNull(player.currentMediaItemIndex)?.key
            val source = playingSource?.song?.key
            if (original != null && source != null) host.onAlternativeSourceFailed(original, source)
        }
        if (!playbackWanted) {
            // A resolver may finish after foreground start was refused. Preserve the actionable
            // restriction until an explicit retry, rather than replacing it with that late error.
            if (failure != PlaybackFailure.BACKGROUND_RESTRICTED) failure = selectedFailure
            player.pause(); publish(); return
        }
        val action = recovery.decide(selectedFailure, player.hasNextMediaItem())
        trace("failure=${selectedFailure.name} action=${action.name}")
        main.post {
            if (destroyed || !playbackWanted || recoveryEpoch != epoch || currentTicket() != ticket || player.playerError !== error) return@post
            when {
                selectedFailure in setOf(PlaybackFailure.PERMISSION, PlaybackFailure.UNAVAILABLE, PlaybackFailure.PROTOCOL) -> {
                    offerAlternative(ticket, selectedFailure)
                }
                action == RecoveryAction.REFRESH_SOURCE -> {
                    sources.invalidate(ticket); refreshCount++; failure = null; publish()
                    player.seekTo(player.currentMediaItemIndex, player.currentPosition); beginGatedPlayback()
                }
                action == RecoveryAction.SKIP_NEXT && consecutiveSkips < MAX_AUTO_SKIPS -> {
                    consecutiveSkips++; skippedCount++; lastSkipped = selectedFailure; failure = null; publish()
                    player.seekToNextMediaItem(); beginGatedPlayback()
                }
                else -> { playbackWanted = false; failure = selectedFailure; player.pause(); publish() }
            }
        }
    }


    private fun cancelSelection() {
        recoveryEpoch++
        foregroundGate.cancel()
        searchJob?.cancel(); searchJob = null
        selectionTimer?.cancel(); selectionTimer = null
        selection.clear()
    }

    private fun cancelPrefetch() {
        prefetchJob?.cancel(); prefetchJob = null; prefetchKey = null
        prefetchedSource?.let { if (it.first != currentTicket()) sources.resetSource(it.first) }
        prefetchedSource = null
    }

    /** Resolve only the actual next window; capabilities stay in QueueSources and are revalidated on use. */
    private fun schedulePrefetch() {
        if (destroyed || !::player.isInitialized || isLocal) return
        if (!player.isPlaying) {
            if (!player.playWhenReady) cancelPrefetch()
            return
        }
        val index = player.nextMediaItemIndex.takeIf { it in queueSongs.indices && it != player.currentMediaItemIndex }
        if (index == null) { cancelPrefetch(); return }
        val ticket = player.getMediaItemAt(index).localConfiguration?.uri?.toString() ?: return
        val original = queueSongs[index]
        val key = "$queueEpoch:$ticket:${provider.generationFor(original.key.stableId)}:$preferences"
        if (prefetchKey == key) return
        cancelPrefetch(); prefetchKey = key
        val epoch = queueEpoch
        val prefs = preferences
        prefetchJob = scope.launch {
            var ready = false
            try {
            delay(maxOf(1500, 15_000 - (SystemClock.elapsedRealtime() - prefetchLastStarted)))
            prefetchLastStarted = SystemClock.elapsedRealtime()
            fun current() = !destroyed && queueEpoch == epoch && prefetchKey == key && preferences == prefs &&
                player.nextMediaItemIndex == index && currentTicket() != ticket
            if (!current()) return@launch
            val resolved = runCatching { withContext(Dispatchers.IO) { sources.resolve(ticket) } }
            ensureActive()
            if (!current()) return@launch
            if (resolved.isSuccess) { ready = true; return@launch }
            if (!prefs.automatic) { ready = true; return@launch }
            val failure = (resolved.exceptionOrNull() as? PlaybackSourceException)?.failure
            if (failure !in setOf(PlaybackFailure.PERMISSION, PlaybackFailure.UNAVAILABLE, PlaybackFailure.PROTOCOL)) return@launch
            val found = runCatching { withTimeout(20_000) {
                suspendCancellableCoroutine<AlternativeSources> { continuation ->
                    var request: Job? = null
                    continuation.invokeOnCancellation { request?.cancel() }
                    request = host.findAlternativeSources(original, emptySet(), SourceSelectionMode.AUTO,
                        SourceSearchProgressListener { }) { if (continuation.isActive) continuation.resumeWith(it) }
                    if (!continuation.isActive) request?.cancel()
                }
            } }.getOrNull() ?: return@launch
            ensureActive()
            if (!current()) return@launch
            val refreshed = PlaybackMetadataSnapshot.refreshOriginal(original, found.original)
            if (refreshed !== original) queueSongs = queueSongs.toMutableList().apply { this[index] = refreshed }
            val eligible = SourceMatchPolicy.rank(found.original, found.candidates, prefs.relaxDuration).filter {
                SourceMatchPolicy.eligible(found.original, it.song, prefs.relaxDuration) &&
                    provider.generationFor(it.song.key.stableId) == it.accountGeneration
            }
            val prepared = prepareAutomaticAlternatives(eligible) { candidate ->
                runInterruptible(Dispatchers.IO) {
                    sources.prepareSource(ticket, candidate.song.key.stableId, candidate.accountGeneration, candidate.song)
                }
            }
            if (!current()) return@launch
            val candidate = eligible.firstOrNull { it.song.key in prepared } ?: return@launch
            if (runCatching { sources.commitSource(prepared.getValue(candidate.song.key)) }.isFailure) return@launch
            prefetchedSource = ticket to candidate
            ready = true; trace("prefetch_ready platform=${candidate.song.key.platform.name}")
            } finally {
                if (!ready && prefetchKey == key) {
                    prefetchKey = null
                    main.postDelayed({ if (!destroyed) schedulePrefetch() }, 15_000)
                }
            }
        }
    }

    private fun cancelPendingSelection() {
        recoveryEpoch++
        foregroundGate.cancel()
        if (selection.state.value.phase in setOf(SourceSelectionPhase.SEARCHING, SourceSelectionPhase.CHOOSING))
            cancelSelection()
    }

    private fun offerAlternative(ticket: String, reason: PlaybackFailure, manual: Boolean = false) {
        val original = queueSongs.getOrNull(player.currentMediaItemIndex) ?: return
        if (!manual) player.pause()
        failure = null; publish()
        searchJob?.cancel(); selectionTimer?.cancel()
        trace("fallback_search_started")
        var prepared = emptyMap<tech.musxi.core.library.MediaKey, QueueSources.PreparedSource>()
        val token = selection.begin(original, onChoose = { candidate ->
            if (destroyed || (!manual && !playbackWanted) || currentTicket() != ticket || activeGeneration != activeMediaId?.let(provider::generationFor)) return@begin
            if (provider.generationFor(candidate.song.key.stableId) != candidate.accountGeneration) {
                failure = PlaybackFailure.AUTH_REQUIRED; selection.clear(); publish(); return@begin
            }
            try {
                prepared[candidate.song.key]?.let(sources::commitSource)
                    ?: sources.selectSource(ticket, candidate.song.key.stableId, candidate.accountGeneration, candidate.song)
            }
            catch (_: Exception) { failure = PlaybackFailure.AUTH_REQUIRED; selection.clear(); publish(); return@begin }
            attemptedSources += candidate.song.key.stableId
            trace("fallback_selected platform=${candidate.song.key.platform.name}")
            selectedTicket = ticket
            playingSource = candidate
            updateMetadata(player.currentMediaItemIndex, candidate.song)
            recovery.reset(); failure = null; publish()
            val position = if (manual) player.currentPosition.coerceAtLeast(0) else 0L
            if (manual) player.stop()
            player.seekTo(player.currentMediaItemIndex, position)
            if (playbackWanted) beginGatedPlayback(ticket) else { player.pause(); player.prepare() }
        }, onDismiss = {
            if (currentTicket() == ticket) {
                if (manual) {
                    searchJob?.cancel(); searchJob = null
                    selectionTimer?.cancel(); selectionTimer = null
                    selection.clear(); publish()
                }
                else { playbackWanted = false; cancelSelection(); failure = reason; player.pause(); publish() }
            }
        }, mode = if (manual) SourceSelectionMode.MANUAL else SourceSelectionMode.AUTO)
        sourceFailures.forEach { (platform, failure) -> selection.playbackFailure(token, platform, failure,
            sourceFailureReasons[platform] ?: FailureReason.fromFailure(failure)) }
        searchJob = scope.launch {
            val response = offeredSources?.takeIf { found -> !manual && found.candidates.any {
                it.song.key.stableId !in attemptedSources && provider.generationFor(it.song.key.stableId) == it.accountGeneration
            } }?.let { Result.success(it) } ?: runCatching {
                withTimeout(22_000) {
                    suspendCancellableCoroutine<AlternativeSources> { continuation ->
                        var request: Job? = null
                        continuation.invokeOnCancellation { request?.cancel() }
                        request = host.findAlternativeSources(original, if (manual) emptySet() else attemptedSources.toSet(),
                            if (manual) SourceSelectionMode.MANUAL else SourceSelectionMode.AUTO,
                            SourceSearchProgressListener { progress ->
                                if (!destroyed && currentTicket() == ticket) selection.progress(token, progress)
                            }) {
                            if (continuation.isActive) continuation.resumeWith(it)
                        }
                        if (!continuation.isActive) request?.cancel()
                    }
                }
            }
            if (destroyed || currentTicket() != ticket || selection.state.value.revision != token) return@launch
            val foundResponse = response.getOrNull()
            if (!manual && preferences.automatic && foundResponse != null) {
                val eligible = SourceMatchPolicy.rank(foundResponse.original, foundResponse.candidates, preferences.relaxDuration)
                    .filter { it.song.key.stableId !in attemptedSources &&
                        SourceMatchPolicy.eligible(foundResponse.original, it.song, preferences.relaxDuration) }
                prepared = prepareAutomaticAlternatives(eligible, maxAttempts = (3 - attemptedSources.size).coerceAtLeast(0)) { candidate ->
                    val prepareStarted = SystemClock.elapsedRealtime()
                    try {
                        val capability = runInterruptible(Dispatchers.IO) {
                            sources.prepareSource(ticket, candidate.song.key.stableId, candidate.accountGeneration, candidate.song)
                        }
                        trace("fallback_prepare_ready platform=${candidate.song.key.platform.name} ms=${SystemClock.elapsedRealtime() - prepareStarted}")
                        capability
                    } catch (error: PlaybackSourceException) {
                        trace("fallback_prepare_failed platform=${candidate.song.key.platform.name} reason=${error.failure.name} ms=${SystemClock.elapsedRealtime() - prepareStarted}")
                        sourceFailures[candidate.song.key.platform] = error.failure
                        sourceFailureReasons[candidate.song.key.platform] = error.reason
                        selection.playbackFailure(token, candidate.song.key.platform, error.failure, error.reason)
                        attemptedSources += candidate.song.key.stableId
                        host.onAlternativeSourceFailed(foundResponse.original.key, candidate.song.key)
                        throw error
                    }
                }
                if (destroyed || currentTicket() != ticket || selection.state.value.revision != token) return@launch
                trace("fallback_prepared count=${prepared.size}")
            }
            response.onSuccess { found ->
                trace("fallback_search_done count=${found.candidates.size}")
                // A denied original stream never delivers resolver metadata. The bounded
                // alternative search may still resolve its display data before searching.
                val index = player.currentMediaItemIndex
                queueSongs.getOrNull(index)?.let { original ->
                    val refreshed = PlaybackMetadataSnapshot.refreshOriginal(original, found.original)
                    if (refreshed !== original) {
                        queueSongs = queueSongs.toMutableList().apply { this[index] = refreshed }
                        if (selectedTicket != ticket) updateMetadata(index, refreshed)
                    }
                }
                offeredSources = found
                val remaining = if (!manual && attemptedSources.size >= 3) emptyList() else found.candidates.filter {
                    (manual || it.song.key.stableId !in attemptedSources) && provider.generationFor(it.song.key.stableId) == it.accountGeneration
                }
                selection.offer(token, found.copy(candidates = remaining),
                    if (!manual && preferences.automatic) prepared.keys else null)
                if (!manual && selection.state.value.phase == SourceSelectionPhase.FAILED) { playbackWanted = false; failure = reason; publish() }
                selection.state.value.deadlineElapsedMs?.let { deadline ->
                    selectionTimer = scope.launch {
                        delay((deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
                        selection.tick(token)
                    }
                }
            }.onFailure {
                selection.fail(token, "search_unavailable")
                if (!manual) { playbackWanted = false; failure = reason }
                publish()
            }
        }
    }

    /** Request focus only after Media3 has established a foreground service, within a fixed budget. */
    private fun beginGatedPlayback(ticket: String? = currentTicket()) {
        if (sleepTimer?.synchronize() == true) {
            playbackWanted = false; foregroundGate.cancel(); publish(); return
        }
        if (destroyed || !playbackWanted || ticket == null) return
        val epoch = recoveryEpoch
        foregroundGate.cancel()
        player.pause()
        // prepare() must run with raw playWhenReady=false: it may request focus internally.
        player.prepare()
        trace("foreground_gate_wait foreground=$isPlaybackOngoing")
        foregroundGate.start {
            !destroyed && playbackWanted && recoveryEpoch == epoch && currentTicket() == ticket &&
                (if (isLocal) localSources?.contains(ticket) == true
                else activeGeneration != null && activeGeneration == activeMediaId?.let(provider::generationFor) &&
                    (selectedTicket != ticket || sources.sourceAuthorized(ticket)))
        }
    }

    private fun foregroundStartDenied() {
        trace("foreground_gate_denied")
        playbackWanted = false
        cancelPendingSelection()
        failure = PlaybackFailure.BACKGROUND_RESTRICTED
        player.pause()
        publish()
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        trace("notification required=$startInForegroundRequired foreground=$isPlaybackOngoing gate=${foregroundGate.pending}")
        super.onUpdateNotification(session, startInForegroundRequired)
        trace("notification_updated foreground=$isPlaybackOngoing")
    }

    private fun shouldRetryOriginal() = selection.state.value.phase in setOf(SourceSelectionPhase.SEARCHING,
        SourceSelectionPhase.CHOOSING, SourceSelectionPhase.FAILED) ||
        failure in setOf(PlaybackFailure.PERMISSION, PlaybackFailure.UNAVAILABLE) ||
        player.playerError?.let(::isSourceSelectionError) == true ||
        selectedTicket?.let { !sources.sourceAuthorized(it) } == true

    private fun retryFromCommand(retryOriginal: Boolean = shouldRetryOriginal()) {
        if (isLocal) {
            failure = null; localFailure = null; recovery.reset(); publish()
            return
        }
        if (failure == PlaybackFailure.BACKGROUND_RESTRICTED) { failure = null; publish() }
        if (player.playerError == null && !(retryOriginal && selectedTicket != null)) return
        trace("retry_from_command")
        recovery.reset(); consecutiveSkips = 0; failure = null
        if (retryOriginal) {
            cancelSelection()
            currentTicket()?.let(sources::resetSource)
            queueSongs.getOrNull(player.currentMediaItemIndex)?.let { updateMetadata(player.currentMediaItemIndex, it) }
            selectedTicket = null; playingSource = null; attemptedSources.clear(); offeredSources = null
        }
        currentTicket()?.let(sources::invalidate); publish()
    }

    private fun metadataFor(snapshot: PlaybackMetadataSnapshot): MediaMetadata = MediaMetadata.Builder()
        .setTitle(snapshot.title).setArtist(snapshot.artist).setAlbumTitle(snapshot.album)
        .setArtworkUri(snapshot.coverUrl?.let(Uri::parse))
        .setExtras(Bundle().apply {
            putString(PlaybackMetadataSnapshot.EXTRA_QUEUE_PLATFORM, snapshot.queuePlatform?.name)
            putString(PlaybackMetadataSnapshot.EXTRA_SOURCE_PLATFORM, snapshot.sourcePlatform?.name)
            putString(PlaybackMetadataSnapshot.EXTRA_SOURCE_ID, snapshot.sourceId)
            putStringArrayList(PlaybackMetadataSnapshot.EXTRA_SOURCE_ARTISTS, ArrayList(snapshot.sourceArtists))
            putStringArrayList(PlaybackMetadataSnapshot.EXTRA_SOURCE_ARTIST_IDS, ArrayList(snapshot.sourceArtistIds))
            putString(PlaybackMetadataSnapshot.EXTRA_SOURCE_ALBUM_ID, snapshot.sourceAlbumId)
        }).build()

    /** All service metadata writes use the same public snapshot, including source restoration. */
    private fun updateMetadata(index: Int, song: SongSummary) {
        val queuePlatform = queueSongs.getOrNull(index)?.key?.platform ?: return
        val item = player.getMediaItemAt(index)
        val metadata = item.mediaMetadata
        val previous = PlaybackMetadataSnapshot(metadata.title?.toString().orEmpty(),
            metadata.artist?.toString().orEmpty(), metadata.albumTitle?.toString().orEmpty(),
            metadata.artworkUri?.toString(),
            PlaybackMetadataSnapshot.platform(metadata.extras?.getString(PlaybackMetadataSnapshot.EXTRA_QUEUE_PLATFORM)),
            PlaybackMetadataSnapshot.platform(metadata.extras?.getString(PlaybackMetadataSnapshot.EXTRA_SOURCE_PLATFORM)),
            metadata.extras?.getString(PlaybackMetadataSnapshot.EXTRA_SOURCE_ID),
            metadata.extras?.getStringArrayList(PlaybackMetadataSnapshot.EXTRA_SOURCE_ARTISTS)?.toList().orEmpty())
        val next = PlaybackMetadataSnapshot.fromSong(song, queuePlatform)
        // Includes album/cover/platform changes even when title and artist are identical.
        if (previous != next) player.replaceMediaItem(index,
            item.buildUpon().setMediaMetadata(metadataFor(next)).build())
    }

    private fun classify(error: PlaybackException): PlaybackFailure {
        trace("player_error_code=${error.errorCode}")
        var cause: Throwable? = error
        repeat(12) {
            when (val current = cause) {
                is PlaybackSourceException -> return current.failure
                is javax.net.ssl.SSLException -> { trace("transport=ssl"); return PlaybackFailure.NETWORK }
                is HttpDataSource.InvalidResponseCodeException -> return when (current.responseCode) {
                    401, 403, 404, 410 -> PlaybackFailure.SOURCE_EXPIRED
                    429 -> PlaybackFailure.RATE_LIMIT
                    else -> PlaybackFailure.NETWORK
                }
                is java.net.SocketTimeoutException -> return PlaybackFailure.TIMEOUT
                is java.net.UnknownHostException -> { trace("transport=dns"); return PlaybackFailure.NETWORK }
                is java.net.ConnectException -> { trace("transport=connect"); return PlaybackFailure.NETWORK }
            }
            cause = cause?.cause
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> PlaybackFailure.NETWORK
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> PlaybackFailure.TIMEOUT
            else -> PlaybackFailure.UNKNOWN
        }
    }

    private fun localError(error: Throwable): LocalAudioError {
        var cause: Throwable? = error
        repeat(12) {
            when (val current = cause) {
                is LocalAudioFailure -> return current.error
                is SecurityException -> return LocalAudioError.PERMISSION_REQUIRED
                is java.io.FileNotFoundException -> return LocalAudioError.NOT_FOUND
            }
            cause = cause?.cause
        }
        return LocalAudioError.READ_FAILED
    }

    private fun isSourceSelectionError(error: PlaybackException): Boolean {
        var cause: Throwable? = error
        repeat(12) {
            val current = cause
            if (current is PlaybackSourceException) return current.failure in setOf(PlaybackFailure.PERMISSION, PlaybackFailure.UNAVAILABLE)
            cause = current?.cause
        }
        return false
    }


    private fun publish() {
        publishQueue()
        refreshSessionState?.invoke()
        session?.setSessionExtras(Bundle().apply {
            putString(EXTRA_FAILURE, failure?.name); putString(EXTRA_SKIPPED, lastSkipped?.name)
            val platform = playingSource?.song?.key?.platform ?: queueSongs.getOrNull(player.currentMediaItemIndex)?.key?.platform
            val reason = failure?.let { coarse ->
                if (platform != null && sourceFailures[platform] == coarse) sourceFailureReasons[platform]
                else FailureReason.fromFailure(coarse)
            }
            putString(EXTRA_FAILURE_REASON, reason?.name)
            putString(EXTRA_FAILURE_PLATFORM, platform?.name)
            putInt(EXTRA_SKIP_COUNT, skippedCount); putInt(EXTRA_REFRESH_COUNT, refreshCount); putInt(EXTRA_AUTO_COUNT, automaticTransitions)
            putString(EXTRA_CURRENT_TICKET, currentTicket())
            putString(EXTRA_LOCAL_FAILURE, localFailure?.name)
            putString(EXTRA_QUALITY_NAME, currentTicket()?.let(sources::currentQualityName))
        })
    }
    private fun currentTicket() = player.currentMediaItem?.localConfiguration?.uri?.toString()
    private fun trace(event: String) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) android.util.Log.d("MusxiQueue", event)
    }
    private fun isAppController(controller: MediaSession.ControllerInfo) = controller.uid == applicationInfo.uid && controller.packageName == packageName
    private fun rejected(): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = Futures.immediateFailedFuture(SecurityException("Media queue rejected"))
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: android.content.Intent?) { if (!playbackWanted) stopSelf() }
    override fun onDestroy() {
        mediaCardLyricsPublisher?.close(); mediaCardLyricsPublisher = null
        sleepTimer?.close(); sleepTimer = null
        checkpoint()
        qualitySwitch?.result?.set(qualityFailure(PlaybackFailure.AUTH_REQUIRED)); qualitySwitch = null
        outputs?.close(); outputs = null; outputSnapshot.value = PlaybackOutputs()
        destroyed = true; cancelSelection(); queueCommands.invalidate(); scope.cancel(); sources.clear(); clearLocalQueue(); main.removeCallbacksAndMessages(null)
        refreshSessionState = null
        session?.run { player.release(); release() }; session = null
        queueEpoch = java.util.UUID.randomUUID().toString()
        appliedQueueBatches.clear()
        queueSnapshot.value = PlaybackQueueState(queueSnapshot.value.revision + 1, queueEpoch = queueEpoch)
        localQueueSnapshot.value = LocalAudioQueueState(localQueueSnapshot.value.revision + 1)
        super.onDestroy()
    }

    /** Debug bridge injects service queue loss without erasing the persisted journal. */
    internal fun validationForeground(hold: Boolean, fastTimeout: Boolean): Boolean {
        if (destroyed || applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
        validationHoldForeground = hold
        setForegroundServiceTimeoutMs(if (fastTimeout) 0L else 600_000L)
        return true
    }

    internal fun validationForegroundState(): Pair<Boolean, Boolean> = isPlaybackOngoing to foregroundGate.pending

    internal fun validationLoseQueue(): Boolean {
        if (destroyed || applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
        checkpoint()
        playbackWanted = false
        cancelPendingSelection()
        queueCommands.invalidate()
        player.pause(); player.stop(); player.clearMediaItems()
        publishQueue(); publish()
        return true
    }

    companion object {
        internal var validationService: java.lang.ref.WeakReference<MusxiPlaybackService>? = null
        const val COMMAND_APPLY_QUEUE = "musxi.applyQueue"
        const val COMMAND_QUALITIES = "musxi.qualities"
        const val COMMAND_SET_QUALITY = "musxi.setQuality"
        const val COMMAND_SELECT_OUTPUT = "musxi.selectOutput"
        const val COMMAND_FIND_SOURCES = "musxi.findSources"
        const val COMMAND_CHECKPOINT = "musxi.checkpointBeforeInstall"
        const val COMMAND_SLEEP_TIMER = "musxi.sleepTimer"
        const val EXTRA_SLEEP_MODE = "musxi.sleepMode"
        const val EXTRA_SLEEP_DURATION = "musxi.sleepDurationMs"
        const val EXTRA_CURRENT_TICKET = "musxi.currentTicket"
        const val EXTRA_QUALITY_ID = "musxi.qualityId"
        const val EXTRA_QUALITY_NAME = "musxi.qualityName"
        const val EXTRA_QUALITY_IDS = "musxi.qualityIds"
        const val EXTRA_QUALITY_NAMES = "musxi.qualityNames"
        const val EXTRA_OUTPUT_ID = "musxi.outputId"
        const val EXTRA_OUTPUT_FAILURE = "musxi.outputFailure"
        const val EXTRA_QUEUE_REVISION = "musxi.queueRevision"
        const val EXTRA_FAILURE = "musxi.failure"
        const val EXTRA_FAILURE_REASON = "musxi.failureReason"
        const val EXTRA_FAILURE_PLATFORM = "musxi.failurePlatform"
        const val EXTRA_SKIPPED = "musxi.lastSkipped"
        const val EXTRA_SKIP_COUNT = "musxi.skipCount"
        const val EXTRA_REFRESH_COUNT = "musxi.refreshCount"
        const val EXTRA_AUTO_COUNT = "musxi.autoCount"
        const val EXTRA_LOCAL_AUDIO_ID = "musxi.localAudioId"
        const val EXTRA_LOCAL_FAILURE = "musxi.localFailure"
        private const val LOCAL_MEDIA_PREFIX = "local:"
        private const val MAX_AUTO_SKIPS = 3
    }
}
