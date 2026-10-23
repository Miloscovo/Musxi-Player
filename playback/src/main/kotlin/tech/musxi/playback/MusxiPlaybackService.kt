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
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionError
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import tech.musxi.core.playback.*
import tech.musxi.core.library.SongSummary

/** Owns the actual timeline. Signed URLs never enter MediaItems or controller state. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class MusxiPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var sources: QueueSources
    private lateinit var provider: PlaybackSourceProvider
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
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
    private var editingQueue = false
    private fun publishQueue(force: Boolean = false) {
        if (editingQueue) return
        val previous = queueSnapshot.value
        if (force || previous.songs != queueSongs) queueSnapshot.value = PlaybackQueueState(previous.revision + 1,
            java.util.Collections.unmodifiableList(queueSongs.map { it.copy(artists = java.util.Collections.unmodifiableList(it.artists.toList())) }))
    }
    private var searchJob: Job? = null
    private var selectionTimer: Job? = null
    private val attemptedSources = mutableSetOf<String>()
    private var offeredSources: AlternativeSources? = null
    private var selectedTicket: String? = null
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
            player.play()
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

    private fun checkpoint() {
        if (!checkpointReady || queueSongs.isEmpty() || player.currentMediaItemIndex !in queueSongs.indices) return
        val generation = activeGeneration ?: return
        val index = player.currentMediaItemIndex
        val time = System.currentTimeMillis().coerceAtLeast(0)
        val position = if (player.playbackState == Player.STATE_ENDED) 0 else player.currentPosition.coerceIn(0, 86_400_000)
        val saved = SavedPlaybackQueue(queueSongs, index, position, when (player.repeatMode) {
            Player.REPEAT_MODE_ONE -> "ONE"; Player.REPEAT_MODE_ALL -> "ALL"; else -> "OFF"
        }, player.shuffleModeEnabled, time)
        val ticket = currentTicket()
        val original = queueSongs[index]
        val selected = selection.state.value.selected?.song ?: original
        val metadata = player.currentMediaItem?.mediaMetadata
        val source = selected.copy(title = metadata?.title?.toString() ?: selected.title,
            album = metadata?.albumTitle?.toString() ?: selected.album,
            durationMs = player.duration.takeIf { it > 0 } ?: selected.durationMs,
            coverUrl = metadata?.artworkUri?.toString() ?: selected.coverUrl)
        val historyKey = ticket?.let { it + ":" + source.key.stableId }
        val recent = if (player.isPlaying && historyTicket != historyKey) {
            historyTicket = historyKey
            RecentPlay(original, source, time)
        } else null
        host.onPlaybackCheckpoint(generation, saved, recent)
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
        val dataSource = ResolvingDataSource.Factory(DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(15_000)) { request ->
            val ticket = request.uri.toString()
            val resolved = sources.resolve(ticket)
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
            })).build().apply {
            setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val ticket = mediaItem?.localConfiguration?.uri?.toString()
                    if (ticket != lastTicket) {
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
                        attemptedSources.clear(); offeredSources = null; selectedTicket = null
                        recovery.reset(); failure = null
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
                    if (timeline.isEmpty) { cancelSelection(); sources.clear(); queueSongs = emptyList(); activeGeneration = null; lastTicket = null }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) { consecutiveSkips = 0; checkpoint() }
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    trace("ready=$playWhenReady reason=$reason")
                    if (!playWhenReady && reason in setOf(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)) {
                        playbackWanted = false; cancelPendingSelection()
                    }
                }
                override fun onPlayerError(error: PlaybackException) = recover(error)
                override fun onEvents(player: Player, events: Player.Events) { publishQueue(); scheduleCheckpoint() }
            })
        }
        // STOP takes effect synchronously in the request callback, alongside cancellation.
        // Media3 batches the later Player.stop call. Suppress that duplicate so an old STOP
        // cannot stop a newer queue accepted between the request and its batched execution.
        val sessionPlayer = object : ForwardingSimpleBasePlayer(player) {
            fun refresh() = invalidateState()
            override fun getState(): SimpleBasePlayer.State {
                val state = super.getState()
                val waiting = playbackWanted && failure == null &&
                    (foregroundGate.pending ||
                        (selection.state.value.phase != SourceSelectionPhase.FAILED &&
                            state.playerError?.let(::isSourceSelectionError) == true))
                return if (waiting) state.buildUpon().setPlayerError(null)
                    .setPlaybackState(Player.STATE_BUFFERING)
                    .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build() else state
            }
            override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
                playbackWanted = playWhenReady
                if (!playWhenReady) cancelPendingSelection()
                else retryFromCommand()
                // A controller may send play() without prepare(), including while
                // the session presents an outstanding source selection as buffering.
                if (playWhenReady) {
                    beginGatedPlayback()
                    return Futures.immediateVoidFuture()
                }
                return super.handleSetPlayWhenReady(playWhenReady)
            }
            override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
                val resume = playbackWanted && (foregroundGate.pending || player.playbackState == Player.STATE_IDLE)
                val result = super.handleSeek(mediaItemIndex, positionMs, seekCommand)
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
                    .buildUpon().add(SessionCommand(COMMAND_APPLY_QUEUE, Bundle.EMPTY)).build())
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
                    checkpoint()
                    queueCommands.invalidate()
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
                if (customCommand.customAction != COMMAND_APPLY_QUEUE || !isAppController(controller))
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
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
        scope.launch { selection.state.collect { sessionPlayer.refresh() } }
        scope.launch {
            provider.authorization.collect {
                if (activeGeneration != null && activeGeneration != activeMediaId?.let(provider::generationFor)) {
                    playbackWanted = false
                    cancelSelection(); sources.clear(); activeGeneration = null
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
    }

    private fun applyQueue(token: Long): SessionResult {
        when (val action = queueCommands.take(token)) {
            null -> return SessionResult(SessionError.ERROR_INVALID_STATE)
            QueueCommandMailbox.Action.Clear -> {
                checkpoint()
                playbackWanted = false
                cancelSelection(); queueSongs = emptyList(); offeredSources = null; attemptedSources.clear(); selectedTicket = null
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
                checkpoint()
                val uris = try { sources.replace(queue.songs.map { it.key.stableId }, generation) }
                    catch (_: Exception) { return SessionResult(SessionError.ERROR_BAD_VALUE) }
                val items = queue.songs.mapIndexed { index, song ->
                    MediaItem.Builder().setMediaId(song.key.stableId).setUri(uris[index])
                        .setMediaMetadata(metadataFor(PlaybackMetadataSnapshot.fromSong(song, song.key.platform))).build()
                }
                cancelSelection(); offeredSources = null; attemptedSources.clear(); selectedTicket = null
                activeGeneration = generation
                activeMediaId = mediaId
                failure = null; lastSkipped = null; skippedCount = 0; refreshCount = 0
                automaticTransitions = 0; consecutiveSkips = 0; recovery.reset()
                queueSongs = queue.songs
                historyTicket = null
                playbackWanted = action.playWhenReady
                player.pause(); player.stop()
                action.repeatMode?.let { player.repeatMode = when (it) {
                    "ONE" -> Player.REPEAT_MODE_ONE; "ALL" -> Player.REPEAT_MODE_ALL; else -> Player.REPEAT_MODE_OFF
                } }
                action.shuffleEnabled?.let { player.shuffleModeEnabled = it }
                player.setMediaItems(items, queue.startIndex, action.positionMs)
                if (action.playWhenReady) beginGatedPlayback()
                publish()
                trace("queue_applied")
            }
            is QueueCommandMailbox.Action.Jump -> return jumpToQueueIndex(action)
            is QueueCommandMailbox.Action.Edit -> return editQueue(action)
            is QueueCommandMailbox.Action.Insert -> return insertQueue(action)
        }
        publishQueue(force = true)
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    private fun editable(revision: Long): Boolean = revision == queueSnapshot.value.revision &&
        queueSongs.isNotEmpty() && player.mediaItemCount == queueSongs.size &&
        activeGeneration != null && activeGeneration == activeMediaId?.let(provider::generationFor)

    private fun jumpToQueueIndex(action: QueueCommandMailbox.Action.Jump): SessionResult {
        if (!editable(action.expectedRevision)) return SessionResult(SessionError.ERROR_INVALID_STATE)
        if (action.index !in queueSongs.indices) return SessionResult(SessionError.ERROR_BAD_VALUE)
        val targetTicket = player.getMediaItemAt(action.index).localConfiguration?.uri?.toString()
            ?: return SessionResult(SessionError.ERROR_INVALID_STATE)
        host.onPlaybackStopRequested()
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
        host.onPlaybackStopRequested()
        val platform = queueSongs.first().key.platform
        val generation = activeGeneration!!
        val nextSongs = plan.order.map(queueSongs::get)
        val keepUris = plan.order.map { player.getMediaItemAt(it).localConfiguration!!.uri.toString() }.toSet()
        // Invalidate delayed recovery only when its actual slot is removed. Edits around a
        // playing/substituted slot retain its ticket, position, selection and retry budget.
        if (plan.currentRemoved) {
            playbackWanted = false; recoveryEpoch++; cancelSelection()
            offeredSources = null; attemptedSources.clear(); selectedTicket = null
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
        host.onPlaybackStopRequested()
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

    private fun recover(error: PlaybackException) {
        foregroundGate.cancel()
        val ticket = currentTicket() ?: return
        val epoch = recoveryEpoch
        val selectedFailure = classify(error)
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
                selectedFailure in setOf(PlaybackFailure.PERMISSION, PlaybackFailure.UNAVAILABLE) -> {
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

    private fun cancelPendingSelection() {
        recoveryEpoch++
        foregroundGate.cancel()
        if (selection.state.value.phase in setOf(SourceSelectionPhase.SEARCHING, SourceSelectionPhase.CHOOSING))
            cancelSelection()
    }

    private fun offerAlternative(ticket: String, reason: PlaybackFailure) {
        val original = queueSongs.getOrNull(player.currentMediaItemIndex) ?: return
        player.pause()
        failure = null; publish()
        searchJob?.cancel(); selectionTimer?.cancel()
        val token = selection.begin(original, onChoose = { candidate ->
            if (destroyed || !playbackWanted || currentTicket() != ticket || activeGeneration != activeMediaId?.let(provider::generationFor)) return@begin
            if (provider.generationFor(candidate.song.key.stableId) != candidate.accountGeneration) {
                failure = PlaybackFailure.AUTH_REQUIRED; selection.clear(); publish(); return@begin
            }
            try { sources.selectSource(ticket, candidate.song.key.stableId, candidate.accountGeneration) }
            catch (_: Exception) { failure = PlaybackFailure.AUTH_REQUIRED; selection.clear(); publish(); return@begin }
            attemptedSources += candidate.song.key.stableId
            selectedTicket = ticket
            updateMetadata(player.currentMediaItemIndex, candidate.song)
            recovery.reset(); failure = null; publish()
            player.seekTo(player.currentMediaItemIndex, 0); beginGatedPlayback(ticket)
        }, onDismiss = {
            if (currentTicket() == ticket) { playbackWanted = false; cancelSelection(); failure = reason; player.pause(); publish() }
        })
        searchJob = scope.launch {
            val response = offeredSources?.let { Result.success(it) } ?: runCatching {
                withTimeout(22_000) {
                    suspendCancellableCoroutine<AlternativeSources> { continuation ->
                        var request: Job? = null
                        continuation.invokeOnCancellation { request?.cancel() }
                        request = host.findAlternativeSources(original) {
                            if (continuation.isActive) continuation.resumeWith(it)
                        }
                        if (!continuation.isActive) request?.cancel()
                    }
                }
            }
            if (destroyed || currentTicket() != ticket || selection.state.value.revision != token) return@launch
            response.onSuccess { found ->
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
                val remaining = if (attemptedSources.size >= 3) emptyList() else found.candidates.filter {
                    it.song.key.stableId !in attemptedSources && provider.generationFor(it.song.key.stableId) == it.accountGeneration
                }
                selection.offer(token, found.copy(candidates = remaining))
                if (selection.state.value.phase == SourceSelectionPhase.FAILED) { playbackWanted = false; failure = reason; publish() }
                selection.state.value.deadlineElapsedMs?.let { deadline ->
                    selectionTimer = scope.launch {
                        delay((deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
                        selection.tick(token)
                    }
                }
            }.onFailure {
                playbackWanted = false; selection.fail(token, "search_unavailable"); failure = reason; publish()
            }
        }
    }

    /** Request focus only after Media3 has established a foreground service, within a fixed budget. */
    private fun beginGatedPlayback(ticket: String? = currentTicket()) {
        if (destroyed || !playbackWanted || ticket == null) return
        val epoch = recoveryEpoch
        foregroundGate.cancel()
        player.pause()
        // prepare() must run with raw playWhenReady=false: it may request focus internally.
        player.prepare()
        trace("foreground_gate_wait foreground=$isPlaybackOngoing")
        foregroundGate.start {
            !destroyed && playbackWanted && recoveryEpoch == epoch && currentTicket() == ticket &&
                activeGeneration != null && activeGeneration == activeMediaId?.let(provider::generationFor) &&
                (selectedTicket != ticket || sources.sourceAuthorized(ticket))
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
        if (failure == PlaybackFailure.BACKGROUND_RESTRICTED) { failure = null; publish() }
        if (player.playerError == null && !(retryOriginal && selectedTicket != null)) return
        trace("retry_from_command")
        recovery.reset(); consecutiveSkips = 0; failure = null
        if (retryOriginal) {
            cancelSelection()
            currentTicket()?.let(sources::resetSource)
            queueSongs.getOrNull(player.currentMediaItemIndex)?.let { updateMetadata(player.currentMediaItemIndex, it) }
            selectedTicket = null; attemptedSources.clear(); offeredSources = null
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
            metadata.extras?.getString(PlaybackMetadataSnapshot.EXTRA_SOURCE_ID))
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
            putInt(EXTRA_SKIP_COUNT, skippedCount); putInt(EXTRA_REFRESH_COUNT, refreshCount); putInt(EXTRA_AUTO_COUNT, automaticTransitions)
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
        checkpoint()
        destroyed = true; cancelSelection(); queueCommands.invalidate(); scope.cancel(); sources.clear(); main.removeCallbacksAndMessages(null)
        refreshSessionState = null
        session?.run { player.release(); release() }; session = null
        queueSnapshot.value = PlaybackQueueState(queueSnapshot.value.revision + 1)
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
        const val EXTRA_QUEUE_REVISION = "musxi.queueRevision"
        const val EXTRA_FAILURE = "musxi.failure"
        const val EXTRA_SKIPPED = "musxi.lastSkipped"
        const val EXTRA_SKIP_COUNT = "musxi.skipCount"
        const val EXTRA_REFRESH_COUNT = "musxi.refreshCount"
        const val EXTRA_AUTO_COUNT = "musxi.autoCount"
        private const val MAX_AUTO_SKIPS = 3
    }
}
