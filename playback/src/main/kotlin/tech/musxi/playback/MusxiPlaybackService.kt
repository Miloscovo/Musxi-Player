package tech.musxi.playback

import android.app.PendingIntent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private var searchJob: Job? = null
    private var selectionTimer: Job? = null
    private val attemptedSources = mutableSetOf<String>()
    private var offeredSources: AlternativeSources? = null
    private var selectedTicket: String? = null
    /** Invalidates delayed recovery work after a user pause/stop/navigation. */
    private var recoveryEpoch = 0L
    private var playbackWanted = false


    override fun onCreate() {
        super.onCreate()
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
                    val item = player.getMediaItemAt(index)
                    val artist = metadata.artists.joinToString(" / ")
                    if (item.mediaMetadata.title?.toString() != metadata.title || item.mediaMetadata.artist?.toString() != artist) {
                        player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon()
                            .setTitle(metadata.title).setArtist(artist).setAlbumTitle(metadata.album).build()).build())
                    }
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
                        val previousTicket = lastTicket
                        lastTicket = ticket
                        if (selectedTicket == previousTicket) {
                            val previous = (0 until player.mediaItemCount).firstOrNull {
                                player.getMediaItemAt(it).localConfiguration?.uri?.toString() == previousTicket
                            }
                            val original = selection.state.value.original ?: previous?.let(queueSongs::getOrNull)
                            if (original != null && previous != null) {
                                val oldItem = player.getMediaItemAt(previous)
                                player.replaceMediaItem(previous, oldItem.buildUpon().setMediaMetadata(
                                    oldItem.mediaMetadata.buildUpon().setTitle(original.title)
                                        .setArtist(original.artists.joinToString(" / ")).setAlbumTitle(original.album).build()).build())
                            }
                        }
                        previousTicket?.let(sources::resetSource)
                        cancelSelection()
                        attemptedSources.clear(); offeredSources = null; selectedTicket = null
                        recovery.reset(); failure = null
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) automaticTransitions++
                        publish()
                    }
                }
                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    if (timeline.isEmpty) { cancelSelection(); sources.clear(); queueSongs = emptyList(); activeGeneration = null; lastTicket = null }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) consecutiveSkips = 0 }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    trace("ready=$playWhenReady reason=$reason")
                    if (!playWhenReady && reason in setOf(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)) {
                        playbackWanted = false; cancelPendingSelection()
                    }
                }
                override fun onPlayerError(error: PlaybackException) = recover(error)
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
                    selection.state.value.phase != SourceSelectionPhase.FAILED &&
                    state.playerError?.let(::isSourceSelectionError) == true
                return if (waiting) state.buildUpon().setPlayerError(null)
                    .setPlaybackState(Player.STATE_BUFFERING)
                    .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build() else state
            }
            override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
                playbackWanted = playWhenReady
                if (!playWhenReady) cancelPendingSelection()
                // A controller may send play() without prepare(), including while
                // the session presents an outstanding source selection as buffering.
                if (playWhenReady && player.playerError != null) player.prepare()
                return super.handleSetPlayWhenReady(playWhenReady)
            }
            override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
                val resume = playbackWanted && player.playbackState == Player.STATE_IDLE
                val result = super.handleSeek(mediaItemIndex, positionMs, seekCommand)
                if (resume) { player.prepare(); player.play() }
                return result
            }
            override fun handleStop(): ListenableFuture<*> {
                trace("stop_batch_ignored")
                return Futures.immediateVoidFuture()
            }
        }
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
                    .remove(Player.COMMAND_SET_REPEAT_MODE).remove(Player.COMMAND_SET_SHUFFLE_MODE)
                commands.remove(Player.COMMAND_SET_MEDIA_ITEM).remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                val accepted = MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailablePlayerCommands(commands.build())
                if (isAppController(controller)) accepted.setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                    .buildUpon().add(SessionCommand(COMMAND_APPLY_QUEUE, Bundle.EMPTY)).build())
                return accepted.build()
            }
            override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int {
                trace("command=$playerCommand app=${isAppController(controller)} notification=${session.isMediaNotificationController(controller)}")
                val retryOriginal = selection.state.value.phase in setOf(SourceSelectionPhase.SEARCHING,
                    SourceSelectionPhase.CHOOSING, SourceSelectionPhase.FAILED) ||
                    failure in setOf(PlaybackFailure.PERMISSION, PlaybackFailure.UNAVAILABLE) ||
                    selectedTicket?.let { !sources.sourceAuthorized(it) } == true
                if (playerCommand == Player.COMMAND_SET_MEDIA_ITEM || playerCommand == Player.COMMAND_CHANGE_MEDIA_ITEMS)
                    return SessionError.ERROR_PERMISSION_DENIED
                if (playerCommand in setOf(Player.COMMAND_STOP, Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                        Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE)) {
                    recoveryEpoch++
                    if (selection.state.value.phase in setOf(SourceSelectionPhase.SEARCHING, SourceSelectionPhase.CHOOSING))
                        cancelSelection()
                }
                if (playerCommand == Player.COMMAND_STOP) {
                    queueCommands.invalidate()
                    playbackWanted = false
                    recoveryEpoch++
                    player.pause(); player.stop()
                    trace("stop_applied")
                    failure = null; lastSkipped = null; publish()
                    (application as PlaybackHost).onPlaybackStopRequested()
                }
                if ((playerCommand == Player.COMMAND_PREPARE || playerCommand == Player.COMMAND_PLAY_PAUSE) &&
                    (player.playerError != null || (retryOriginal && selectedTicket != null))) {
                    trace("retry_from_command")
                    recovery.reset(); consecutiveSkips = 0; failure = null
                    if (retryOriginal) {
                        currentTicket()?.let(sources::resetSource)
                        val original = offeredSources?.original ?: queueSongs.getOrNull(player.currentMediaItemIndex)
                        val item = player.currentMediaItem
                        if (original != null && item != null) player.replaceMediaItem(player.currentMediaItemIndex,
                            item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setTitle(original.title)
                                .setArtist(original.artists.joinToString(" / ")).setAlbumTitle(original.album).build()).build())
                        selectedTicket = null; attemptedSources.clear(); offeredSources = null
                        selection.clear()
                    }
                    currentTicket()?.let(sources::invalidate); publish()
                }
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
    }

    private fun applyQueue(token: Long): SessionResult {
        when (val action = queueCommands.take(token)) {
            null -> return SessionResult(SessionError.ERROR_INVALID_STATE)
            QueueCommandMailbox.Action.Clear -> {
                playbackWanted = false
                cancelSelection(); queueSongs = emptyList(); offeredSources = null; attemptedSources.clear(); selectedTicket = null
                failure = null; lastSkipped = null; recovery.reset()
                sources.clear(); activeGeneration = null
                player.pause(); player.stop(); player.clearMediaItems(); publish()
            }
            is QueueCommandMailbox.Action.Replace -> {
                val queue = action.queue
                if (queue.songs.isEmpty()) return SessionResult(SessionError.ERROR_BAD_VALUE)
                val mediaId = queue.songs.first().key.stableId
                val generation = provider.generationFor(mediaId)
                if (generation == null || queue.accountGeneration != generation ||
                    queue.songs.any { !PlaybackSourcePolicy.acceptsMediaId(it.key.stableId) || it.key.platform != queue.songs.first().key.platform })
                    return SessionResult(SessionError.ERROR_BAD_VALUE)
                val uris = try { sources.replace(queue.songs.map { it.key.stableId }, generation) }
                    catch (_: Exception) { return SessionResult(SessionError.ERROR_BAD_VALUE) }
                val items = queue.songs.mapIndexed { index, song ->
                    MediaItem.Builder().setMediaId(song.key.stableId).setUri(uris[index])
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title)
                            .setArtist(song.artists.joinToString(" / ")).setAlbumTitle(song.album).build()).build()
                }
                cancelSelection(); offeredSources = null; attemptedSources.clear(); selectedTicket = null
                activeGeneration = generation
                activeMediaId = mediaId
                failure = null; lastSkipped = null; skippedCount = 0; refreshCount = 0
                automaticTransitions = 0; consecutiveSkips = 0; recovery.reset()
                queueSongs = queue.songs
                playbackWanted = true
                player.setMediaItems(items, queue.startIndex, 0)
                player.prepare(); player.play(); publish()
                trace("queue_applied")
            }
        }
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    private fun recover(error: PlaybackException) {
        val ticket = currentTicket() ?: return
        val epoch = recoveryEpoch
        val selectedFailure = classify(error)
        if (!playbackWanted) { failure = selectedFailure; player.pause(); publish(); return }
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
                    player.seekTo(player.currentMediaItemIndex, player.currentPosition); player.prepare()
                }
                action == RecoveryAction.SKIP_NEXT && consecutiveSkips < MAX_AUTO_SKIPS -> {
                    consecutiveSkips++; skippedCount++; lastSkipped = selectedFailure; failure = null; publish()
                    player.seekToNextMediaItem(); player.prepare()
                }
                else -> { playbackWanted = false; failure = selectedFailure; player.pause(); publish() }
            }
        }
    }


    private fun cancelSelection() {
        recoveryEpoch++
        searchJob?.cancel(); searchJob = null
        selectionTimer?.cancel(); selectionTimer = null
        selection.clear()
    }

    private fun cancelPendingSelection() {
        recoveryEpoch++
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
            val index = player.currentMediaItemIndex
            val item = player.currentMediaItem
            if (item != null) player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(
                item.mediaMetadata.buildUpon().setTitle(candidate.song.title)
                    .setArtist(candidate.song.artists.joinToString(" / ")).setAlbumTitle(candidate.song.album).build()).build())
            recovery.reset(); failure = null; publish()
            player.seekTo(player.currentMediaItemIndex, 0); player.prepare(); player.play()
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
        destroyed = true; cancelSelection(); queueCommands.invalidate(); scope.cancel(); sources.clear(); main.removeCallbacksAndMessages(null)
        session?.run { player.release(); release() }; session = null
        super.onDestroy()
    }

    companion object {
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
