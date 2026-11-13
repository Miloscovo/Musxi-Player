package tech.musxi.playback

import android.app.Application
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.ListenerSet
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.PlaybackQueue
import tech.musxi.core.library.SongSummary
import tech.musxi.core.localaudio.*
import tech.musxi.core.playback.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LocalPlaybackTestApplication::class)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class LocalPlaybackServiceTest {
    private lateinit var service: MusxiPlaybackService
    private lateinit var player: ExoPlayer
    private val app get() = RuntimeEnvironment.getApplication() as LocalPlaybackTestApplication
    private lateinit var queue: LocalAudioPlaybackQueue

    @Before fun setup() {
        service = Robolectric.buildService(MusxiPlaybackService::class.java).create().get()
        player = field("player") as ExoPlayer
        queue = LocalAudioPlaybackQueue(LocalAudioLimits.newId(), listOf(entry("一", 9000), entry("二", 7000)), 0, 9000)
        assertEquals(0, apply(QueueCommandMailbox.Action.LocalReplace(queue, false)))
        shadowOf(Looper.getMainLooper()).idle()
        app.positions.clear()
    }
    @After fun cleanup() { service.onDestroy(); shadowOf(Looper.getMainLooper()).idle() }

    @Test fun localQueueInstallsWithoutAccountAndPublishesNoDocumentUri() {
        assertEquals(2, player.mediaItemCount)
        assertEquals(9000, player.currentPosition)
        assertEquals("一", player.currentMediaItem!!.mediaMetadata.title)
        assertTrue(player.currentMediaItem!!.localConfiguration!!.uri.toString().startsWith("musxi-local://"))
        assertFalse(player.currentMediaItem!!.toString().contains("content://"))
        assertTrue(queueSnapshot.value.songs.isEmpty())
        assertEquals(queue.entries.map { it.audio }, localQueueSnapshot.value.items)
        assertNull(field("activeGeneration"))
        assertFalse(player.playWhenReady)
    }

    @Test fun idleQueueRestorationDoesNotRewriteProgressOrCompletionBeforePlayback() {
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertTrue(app.positions.isEmpty())
    }

    @Test fun eachLocalSlotRestoresItsOwnSpeedAndKeepsPitchOne() {
        val first = queue.entries[0].audio.copy(playbackSpeed = 1.75f)
        val second = queue.entries[1].audio.copy(playbackSpeed = 0.75f)
        app.preferences[first.id] = first
        app.preferences[second.id] = second
        assertEquals(0, apply(QueueCommandMailbox.Action.LocalReplace(queue, false)))
        assertEquals(PlaybackParameters(1.75f, 1f), player.playbackParameters)
        player.seekTo(1, 5_000)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(PlaybackParameters(0.75f, 1f), player.playbackParameters)
        player.seekTo(0, 10_000)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(PlaybackParameters(1.75f, 1f), player.playbackParameters)
        assertEquals(10_000, player.currentPosition)
    }

    @Test fun platformTakeoverAlwaysRestoresNormalSpeed() {
        app.preferences[queue.entries[0].audio.id] = queue.entries[0].audio.copy(playbackSpeed = 2f)
        assertEquals(0, apply(QueueCommandMailbox.Action.LocalReplace(queue, false)))
        assertEquals(2f, player.playbackParameters.speed)
        val song = SongSummary(MediaKey(Platform.NETEASE, "1"), "平台音乐", emptyList(), "", 100_000)
        assertEquals(0, apply(QueueCommandMailbox.Action.Replace(PlaybackQueue(listOf(song), 0, 1), playWhenReady = false)))
        assertEquals(PlaybackParameters.DEFAULT, player.playbackParameters)
        assertTrue(changeSpeed(PlaybackParameters(1.5f, 1f)).isDone)
        assertEquals(PlaybackParameters.DEFAULT, player.playbackParameters)
    }

    @Test fun standardControllerCommandPersistsTheLocalSpeedAndRejectsPitchOrUnsupportedValues() {
        val sessionPlayer = (field("session") as androidx.media3.session.MediaSession).player
        sessionPlayer.setPlaybackParameters(PlaybackParameters(1.5f, 1f))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1.5f, app.preferences.getValue(queue.entries[0].audio.id).playbackSpeed)
        assertEquals(PlaybackParameters(1.5f, 1f), player.playbackParameters)
        val invalid = changeSpeed(PlaybackParameters(1.1f, 1f))
        val pitch = changeSpeed(PlaybackParameters(1.5f, 0.8f))
        assertThrows(java.util.concurrent.ExecutionException::class.java) { invalid.get() }
        assertThrows(java.util.concurrent.ExecutionException::class.java) { pitch.get() }
        assertEquals(PlaybackParameters(1.5f, 1f), player.playbackParameters)
    }

    @Test fun failedSpeedPersistenceLeavesPlaybackAtThePreviousSpeed() {
        app.speedFailure = true
        val future = changeSpeed(PlaybackParameters(2f, 1f))
        shadowOf(Looper.getMainLooper()).idle()
        assertThrows(java.util.concurrent.ExecutionException::class.java) { future.get() }
        assertEquals(PlaybackParameters.DEFAULT, player.playbackParameters)
    }

    @Test fun lateSpeedCompletionCannotChangeTheNextSlot() {
        app.delaySpeed = true
        val future = changeSpeed(PlaybackParameters(2f, 1f))
        player.seekTo(1, 0)
        app.finishSpeed!!()
        shadowOf(Looper.getMainLooper()).idle()
        assertThrows(java.util.concurrent.ExecutionException::class.java) { future.get() }
        assertEquals(PlaybackParameters.DEFAULT, player.playbackParameters)
        assertEquals(2f, app.preferences.getValue(queue.entries[0].audio.id).playbackSpeed)
    }

    @Test fun liveArtworkOffHidesOnlyAutomaticImagesAndNeverMovesTheTimeline() {
        val first = queue.entries[0].audio
        app.preferences[first.id] = first.copy(metadata = first.metadata.copy(artworkKey = "b".repeat(64)))
        app.preferenceChanges.tryEmit(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(player.currentMediaItem!!.mediaMetadata.artworkUri)
        app.artworkOff = true
        app.preferenceChanges.tryEmit(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(player.currentMediaItem!!.mediaMetadata.artworkUri)
        assertEquals(9_000, player.currentPosition)
        assertFalse(player.playWhenReady)
        app.preferences[first.id] = app.preferences.getValue(first.id).copy(customArtworkKey = "c".repeat(64))
        app.preferenceChanges.tryEmit(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("file:///derived-cover/${"c".repeat(64)}.jpg", player.currentMediaItem!!.mediaMetadata.artworkUri.toString())
        app.preferences[first.id] = app.preferences.getValue(first.id).copy(customArtworkKey = null)
        app.artworkOff = false
        app.preferenceChanges.tryEmit(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("file:///derived-cover/${"b".repeat(64)}.jpg", player.currentMediaItem!!.mediaMetadata.artworkUri.toString())
        assertEquals(9_000, player.currentPosition)
    }

    private fun changeSpeed(parameters: PlaybackParameters) = call("changeLocalSpeed",
        arrayOf(PlaybackParameters::class.java), parameters) as com.google.common.util.concurrent.ListenableFuture<*>

    @Test fun videoAudioUsesTheSameQueueAndExplicitlyDisablesTheVideoTrack() {
        val metadata = LocalAudioMetadata("video.mp4", kind = LocalMediaKind.VIDEO, artworkKey = "b".repeat(64))
        val video = LocalAudioQueueEntry(LocalAudioItem(LocalAudioLimits.newId(), metadata,
            LocalImportMode.COPY, addedAtMs = 0), "a".repeat(64), 9_000)
        val replacement = LocalAudioPlaybackQueue(null, listOf(video), 0, 9_000)
        assertEquals(0, apply(QueueCommandMailbox.Action.LocalReplace(replacement, false)))
        assertTrue(player.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_VIDEO))
        assertFalse(player.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_AUDIO))
        assertEquals(9_000, player.currentPosition)
        assertEquals(LocalMediaKind.VIDEO, localQueueSnapshot.value.items.single().kind)
        assertEquals("file:///derived-cover/${"b".repeat(64)}.jpg", player.currentMediaItem!!.mediaMetadata.artworkUri.toString())
        assertTrue(player.currentMediaItem!!.localConfiguration!!.uri.toString().startsWith("musxi-local://"))
    }

    @Test fun checkpointPersistsLongLocalPositionWithoutPlatformJournal() {
        player.seekTo(0, 25L * 60 * 60 * 1000)
        call("checkpoint", arrayOf(kotlin.jvm.functions.Function1::class.java), null)
        assertEquals(25L * 60 * 60 * 1000, app.positions.last().position)
        assertEquals(queue.entries[0].audio.id, app.positions.last().id)
        assertEquals(0, app.platformCheckpoints)
    }

    @Test fun platformReplacementSavesOldLocalPositionBeforeChangingQueue() {
        player.seekTo(0, 23_000)
        val before = app.positions.size
        val song = SongSummary(MediaKey(Platform.NETEASE, "1"), "平台音乐", emptyList(), "", 100_000)
        assertEquals(0, apply(QueueCommandMailbox.Action.Replace(PlaybackQueue(listOf(song), 0, 1), playWhenReady = false)))
        assertTrue(app.positions.drop(before).any { it.id == queue.entries[0].audio.id && it.position == 23_000L })
        assertTrue(localQueueSnapshot.value.items.isEmpty())
        assertEquals(listOf(song), queueSnapshot.value.songs)
        assertEquals("netease:1", player.currentMediaItem!!.mediaId)
    }

    @Test fun seekingNextSavesPreviousAudioAndRestoresTargetIndependentPosition() {
        player.seekTo(0, 23_000)
        player.seekTo(1, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(app.positions.any { it.id == queue.entries[0].audio.id && it.position == 23_000L })
        assertEquals(7000, player.currentPosition)
        assertEquals(queue.entries[1].audio.id, localQueueSnapshot.value.items[player.currentMediaItemIndex].id)
    }

    @Test fun staleLocalJumpAndEditLeaveTimelineUntouched() {
        val revision = localQueueSnapshot.value.revision
        assertNotEquals(0, apply(QueueCommandMailbox.Action.LocalJump(1, revision - 1)))
        assertNotEquals(0, apply(QueueCommandMailbox.Action.LocalEdit(revision - 1, QueueEdit.Clear)))
        assertEquals(0, player.currentMediaItemIndex)
        assertEquals(2, player.mediaItemCount)
    }

    @Test fun removingCurrentLocalItemLeavesNextPausedAtItsOwnPosition() {
        val revision = localQueueSnapshot.value.revision
        assertEquals(0, apply(QueueCommandMailbox.Action.LocalEdit(revision, QueueEdit.Remove(0))))
        assertEquals(1, player.mediaItemCount)
        assertEquals(7000, player.currentPosition)
        assertFalse(player.playWhenReady)
        assertEquals(queue.entries[1].audio.id, localQueueSnapshot.value.items.single().id)
    }

    @Test fun movingQueueItemKeepsOpaqueTicketAndPlaybackPosition() {
        val ticket = player.currentMediaItem!!.localConfiguration!!.uri
        assertEquals(0, apply(QueueCommandMailbox.Action.LocalEdit(localQueueSnapshot.value.revision, QueueEdit.Move(0, 1))))
        assertEquals(ticket, player.currentMediaItem!!.localConfiguration!!.uri)
        assertEquals(9000, player.currentPosition)
        assertEquals(1, player.currentMediaItemIndex)
    }

    @Test fun localFileFailurePausesAndNeverSearchesNetworkOrSkips() {
        val error = PlaybackException("Test unavailable", LocalAudioFailure(LocalAudioError.PERMISSION_REQUIRED), PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
        call("recover", arrayOf(PlaybackException::class.java), error)
        assertFalse(player.playWhenReady)
        assertEquals(0, app.alternativeRequests)
        assertEquals(0, player.currentMediaItemIndex)
        assertEquals(LocalAudioError.PERMISSION_REQUIRED, field("localFailure"))
        assertEquals(2, player.mediaItemCount)
    }

    @Test fun clearingLocalQueueCheckpointsAndRevokesOldSourceTickets() {
        player.seekTo(0, 10_000)
        val ticket = player.currentMediaItem!!.localConfiguration!!.uri.toString()
        val sources = field("localSources") as LocalQueueSources
        assertEquals(0, apply(QueueCommandMailbox.Action.LocalEdit(localQueueSnapshot.value.revision, QueueEdit.Clear)))
        assertFalse(sources.contains(ticket))
        assertTrue(localQueueSnapshot.value.items.isEmpty())
        assertEquals(0, player.mediaItemCount)
        assertTrue(app.positions.any { it.id == queue.entries[0].audio.id })
    }

    @Test fun networkQueueEditCannotMutateLocalTimeline() {
        assertNotEquals(0, apply(QueueCommandMailbox.Action.Edit(queueSnapshot.value.revision, QueueEdit.Clear)))
        assertEquals(2, player.mediaItemCount)
    }

    @Test fun localTakeoverCancelsPlatformLoadsWithoutCancellingNewerLocalPreparations() {
        assertEquals(1, app.localTakeovers)
        assertEquals(0, app.stopRequests)
        assertEquals(0, apply(QueueCommandMailbox.Action.LocalReplace(queue, false)))
        assertEquals(2, app.localTakeovers)
        assertEquals(0, app.stopRequests)
    }

    @Test fun platformLogoutCannotClearActiveLocalQueueOrItsPosition() {
        app.generation = null
        app.authorizationEvents.tryEmit(null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, player.mediaItemCount)
        assertEquals(9000, player.currentPosition)
        assertNull(field("failure"))
        assertEquals(queue.entries[0].audio.id, localQueueSnapshot.value.items.first().id)
    }

    @Test fun boundaryPauseMarksAudioCompletedAndLaterCheckpointsKeepZeroUntilExplicitReplay() {
        player.seekTo(0, 80_000)
        emit { it.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) }
        call("checkpoint", arrayOf(kotlin.jvm.functions.Function1::class.java), null)
        assertEquals(0, app.positions.last().position)
        assertTrue(app.positions.last().completed)
        assertEquals(queue.entries[0].audio.id, app.positions.last().id)
    }

    @Test fun explicitSessionSeekToStartDoesNotGetOverriddenByResumePosition() {
        val session = field("session") as androidx.media3.session.MediaSession
        session.player.seekTo(1, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals(0, player.currentPosition)
    }

    @Test fun idleQueueIsJournaledWithoutWritingAudioProgressAndModesFollowTheRealShuffleOrder() {
        assertFalse(app.savedQueues.last().useStoredProgress)
        assertTrue(app.positions.isEmpty())
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.shuffleModeEnabled = true
        player.setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(intArrayOf(1, 0), 1))
        shadowOf(Looper.getMainLooper()).idle()
        call("checkpoint", arrayOf(kotlin.jvm.functions.Function1::class.java), null)
        val saved = app.savedQueues.last()
        assertEquals("ALL", saved.repeatMode)
        assertTrue(saved.shuffleEnabled)
        assertEquals(listOf(1, 0), saved.shuffleOrder)
        assertEquals(queue.entries.map { it.audio.id }, saved.entries.map { it.audioId })
        assertFalse(saved.toString().contains("musxi-local"))
    }

    @Test fun coldQueueRestorationStaysIdlePreservesModesAndCreatesFreshTickets() {
        val oldTicket = player.currentMediaItem!!.localConfiguration!!.uri
        recreate()
        assertEquals(0, player.mediaItemCount)
        app.restoreCallback!!(Result.success(LocalAudioPlaybackQueue(queue.folderId, queue.entries, 1,
            25L * 60 * 60 * 1000, true, "ALL", true, listOf(1, 0), useStoredProgress = true)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, player.mediaItemCount)
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals(25L * 60 * 60 * 1000, player.currentPosition)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertFalse(player.playWhenReady)
        assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
        assertTrue(player.shuffleModeEnabled)
        assertNotEquals(oldTicket, player.currentMediaItem!!.localConfiguration!!.uri)
        assertTrue(app.savedQueues.last().useStoredProgress)
        app.positions.clear()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertTrue(app.positions.isEmpty())
    }

    @Test fun lateStartupRestoreCannotOverwriteNewLocalIntentOrReopenClearedQueue() {
        recreate()
        val older = app.restoreCallback!!
        assertEquals(0, apply(QueueCommandMailbox.Action.LocalReplace(queue, false)))
        val freshTicket = player.currentMediaItem!!.localConfiguration!!.uri
        older(Result.success(LocalAudioPlaybackQueue(null, listOf(entry("旧队列", 0)), 0, 0)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(freshTicket, player.currentMediaItem!!.localConfiguration!!.uri)
        assertEquals(0, apply(QueueCommandMailbox.Action.Clear))
        older(Result.success(queue))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, player.mediaItemCount)
        assertTrue(app.deactivations.last())
    }

    @Test fun startupRestoreCannotOverwriteAnAcceptedPlatformQueue() {
        recreate()
        val older = app.restoreCallback!!
        val song = SongSummary(MediaKey(Platform.NETEASE, "1"), "平台音乐", emptyList(), "", 100_000)
        assertEquals(0, apply(QueueCommandMailbox.Action.Replace(PlaybackQueue(listOf(song), 0, 1), playWhenReady = false)))
        older(Result.success(queue))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("netease:1", player.currentMediaItem!!.mediaId)
        assertTrue(localQueueSnapshot.value.items.isEmpty())
    }

    @Test fun queueEditsPersistTheirNewOrderAndClearRemovesTheRecoveryJournal() {
        assertEquals(0, apply(QueueCommandMailbox.Action.LocalEdit(localQueueSnapshot.value.revision, QueueEdit.Move(0, 1))))
        assertEquals(queue.entries.reversed().map { it.audio.id }, app.savedQueues.last().entries.map { it.audioId })
        assertEquals(1, app.savedQueues.last().index)
        assertEquals(0, apply(QueueCommandMailbox.Action.LocalEdit(localQueueSnapshot.value.revision, QueueEdit.Clear)))
        assertEquals(listOf(true), app.deactivations)
    }

    @Test fun removalDoesNotRewriteUnplayedReplacementProgress() {
        player.seekTo(0, 15_000)
        app.positions.clear()
        assertEquals(0, apply(QueueCommandMailbox.Action.LocalEdit(localQueueSnapshot.value.revision, QueueEdit.Remove(0))))
        assertTrue(app.positions.all { it.id == queue.entries[0].audio.id })
        assertFalse(app.savedQueues.last().useStoredProgress)
        assertFalse(player.playWhenReady)
    }

    @Test fun supersededPayloadCannotRevokeCurrentTicketsOrCancelNewPlatformLoad() {
        val ticket = player.currentMediaItem!!.localConfiguration!!.uri
        val takeovers = app.localTakeovers
        app.acceptPreparedQueue = false
        assertNotEquals(0, apply(QueueCommandMailbox.Action.LocalReplace(queue, false)))
        assertEquals(ticket, player.currentMediaItem!!.localConfiguration!!.uri)
        assertEquals(takeovers, app.localTakeovers)
    }

    private fun recreate() {
        service.onDestroy()
        shadowOf(Looper.getMainLooper()).idle()
        service = Robolectric.buildService(MusxiPlaybackService::class.java).create().get()
        player = field("player") as ExoPlayer
    }

    @Suppress("UNCHECKED_CAST")
    private fun emit(event: (Player.Listener) -> Unit) {
        val listeners = player.javaClass.getDeclaredField("listeners").apply { isAccessible = true }.get(player) as ListenerSet<Player.Listener>
        listeners.sendEvent(-1) { event(it) }
    }

    private fun apply(action: QueueCommandMailbox.Action): Int {
        val token = queueCommands.submit(action)
        return (call("applyQueue", arrayOf(Long::class.javaPrimitiveType!!), token) as androidx.media3.session.SessionResult).resultCode
    }
    private fun field(name: String): Any? = MusxiPlaybackService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)
    private fun call(name: String, args: Array<Class<*>>, vararg values: Any?): Any? =
        MusxiPlaybackService::class.java.getDeclaredMethod(name, *args).apply { isAccessible = true }.invoke(service, *values)
    private fun entry(title: String, resume: Long): LocalAudioQueueEntry = LocalAudioQueueEntry(
        LocalAudioItem(LocalAudioLimits.newId(), LocalAudioMetadata("$title.mp3"), LocalImportMode.COPY, addedAtMs = 0), "a".repeat(64), resume)
}

class LocalPlaybackTestApplication : Application(), PlaybackHost {
    data class Position(val folder: String?, val id: String, val position: Long, val completed: Boolean)
    val positions = mutableListOf<Position>()
    val savedQueues = mutableListOf<SavedLocalAudioQueue>()
    val deactivations = mutableListOf<Boolean>()
    var restoreCallback: ((Result<LocalAudioPlaybackQueue?>) -> Unit)? = null
    var acceptPreparedQueue = true
    var alternativeRequests = 0
    var platformCheckpoints = 0
    var localTakeovers = 0
    var stopRequests = 0
    var generation: Long? = 1L
    val preferences = mutableMapOf<String, LocalAudioItem>()
    val preferenceChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 10)
    var artworkOff = false
    var speedFailure = false
    var delaySpeed = false
    var finishSpeed: (() -> Unit)? = null
    override val sourceSelection = SourceSelection({ 0L }, { SourceSwitchPreferences() })
    val authorizationEvents = MutableSharedFlow<Long?>(extraBufferCapacity = 1)
    override val playbackSources = object : PlaybackSourceProvider {
        override val authorization = authorizationEvents
        override fun currentGeneration() = generation
        override fun resolve(mediaId: String, generation: Long): StreamLease = throw AssertionError("No platform IO expected")
    }
    override val localAudioPlayback = object : LocalAudioPlaybackHost {
        override val changes = preferenceChanges
        override fun currentItem(entry: LocalAudioQueueEntry) = preferences.getOrPut(entry.audio.id) { entry.audio }
        override fun artworkUri(entry: LocalAudioQueueEntry) = currentItem(entry).let { item ->
            item.artworkKey?.takeIf { item.artworkCustom || !artworkOff }?.let { "file:///derived-cover/$it.jpg" }
        }
        override fun setPlaybackSpeed(audioId: String, speed: Float, result: (Result<Unit>) -> Unit) {
            val complete = {
                if (speedFailure) result(Result.failure(LocalAudioFailure(LocalAudioError.STORAGE_FAILED)))
                else { preferences[audioId] = preferences.getValue(audioId).copy(playbackSpeed = speed); result(Result.success(Unit)) }
            }
            if (delaySpeed) finishSpeed = complete else complete()
        }
        override fun isQueueCurrent(queue: LocalAudioPlaybackQueue) = acceptPreparedQueue
        override fun resolve(entry: LocalAudioQueueEntry) = "content://test/${entry.audio.id}"
        override fun checkpoint(folderId: String?, audioId: String, positionMs: Long, completed: Boolean, result: (Result<Unit>) -> Unit) {
            positions += Position(folderId, audioId, positionMs, completed)
            result(Result.success(Unit))
        }
        override fun checkpointQueue(queue: SavedLocalAudioQueue, progress: LocalAudioProgress?, active: Boolean, result: (Result<Unit>) -> Unit) {
            savedQueues += queue
            super.checkpointQueue(queue, progress, active, result)
        }
        override fun restoreQueue(result: (Result<LocalAudioPlaybackQueue?>) -> Unit) { restoreCallback = result }
        override fun deactivateQueue(clear: Boolean) { deactivations += clear }
    }
    override fun findAlternativeSources(original: SongSummary, result: (Result<AlternativeSources>) -> Unit): Job {
        alternativeRequests++; return Job().also { it.complete() }
    }
    override fun onLocalPlaybackRequested() { localTakeovers++ }
    override fun onPlaybackStopRequested() { stopRequests++ }
    override fun onPlaybackCheckpoint(generation: Long, snapshot: SavedPlaybackQueue, recent: RecentPlay?) { platformCheckpoints++ }
}
