package tech.musxi.playback

import tech.musxi.core.library.PlaybackQueue
import tech.musxi.core.library.SongSummary

enum class QueueInsertAt { HEAD, TAIL }

/** One in-process payload, never a large Binder bundle. Latest intent wins before dispatch. */
internal class QueueCommandMailbox {
    sealed interface Action {
        class LocalReplace(val queue: LocalAudioPlaybackQueue, val playWhenReady: Boolean = true) : Action
        class LocalJump(val index: Int, val expectedRevision: Long, val fromStart: Boolean = false) : Action
        class LocalEdit(val expectedRevision: Long, val edit: tech.musxi.core.playback.QueueEdit) : Action
        class Replace(val queue: PlaybackQueue, val positionMs: Long = 0, val playWhenReady: Boolean = true,
            val repeatMode: String? = null, val shuffleEnabled: Boolean? = null) : Action
        data object Clear : Action
        class Jump(val index: Int, val expectedRevision: Long) : Action
        class Edit(val expectedRevision: Long, val edit: tech.musxi.core.playback.QueueEdit) : Action
        class Insert(val expectedRevision: Long, val song: tech.musxi.core.library.SongSummary,
            val playNext: Boolean) : Action
        class InsertBatch(val queueEpoch: String, val batchId: String, val songs: List<SongSummary>,
            val at: QueueInsertAt) : Action
    }
    private var revision = 0L
    private var pending: Action? = null

    @Synchronized fun submit(action: Action): Long {
        pending = action
        return ++revision
    }

    @Synchronized fun take(token: Long): Action? {
        if (token != revision) return null
        return pending.also { pending = null }
    }

    @Synchronized fun isPending(token: Long) = token == revision && pending != null

    @Synchronized fun invalidate() { ++revision; pending = null }
}

internal val queueCommands = QueueCommandMailbox()
