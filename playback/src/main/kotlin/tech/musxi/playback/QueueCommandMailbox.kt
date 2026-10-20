package tech.musxi.playback

import tech.musxi.core.library.PlaybackQueue

/** One in-process payload, never a large Binder bundle. Latest intent wins before dispatch. */
internal class QueueCommandMailbox {
    sealed interface Action {
        class Replace(val queue: PlaybackQueue) : Action
        data object Clear : Action
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
