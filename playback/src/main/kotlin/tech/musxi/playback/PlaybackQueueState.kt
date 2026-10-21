package tech.musxi.playback

import kotlinx.coroutines.flow.MutableStateFlow
import tech.musxi.core.library.SongSummary

/** Service-owned original queue. Revision fences edits against replaced/reordered queues. */
data class PlaybackQueueState(val revision: Long = 0, val songs: List<SongSummary> = emptyList())

internal val queueSnapshot = MutableStateFlow(PlaybackQueueState())
