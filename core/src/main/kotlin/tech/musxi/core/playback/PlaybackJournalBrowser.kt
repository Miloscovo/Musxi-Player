package tech.musxi.core.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.musxi.core.account.Platform
import tech.musxi.core.library.LibraryCacheIdentity
import tech.musxi.core.library.LibraryPageFailure

data class PlaybackJournalState(
    val queue: SavedPlaybackQueue? = null,
    val recent: List<RecentPlay> = emptyList(),
    val accountVerified: Boolean = false,
    val loading: Boolean = false,
    val restoring: Boolean = false,
    val error: String? = null,
)

/** Main-thread account/revision fences. Disk access itself is serialized with account erasure. */
class PlaybackJournalBrowser(
    private val identity: (Platform) -> LibraryCacheIdentity?,
    private val access: (Platform, LibraryCacheIdentity, ((PlaybackJournal) -> PlaybackJournal)?, (Result<PlaybackJournal>) -> Unit) -> Unit,
    private val submit: (SavedPlaybackQueue, (Result<Unit>) -> Unit) -> Unit,
) {
    private val mutable = MutableStateFlow(Platform.entries.associateWith { PlaybackJournalState() })
    val state = mutable.asStateFlow()
    private val revisions = Platform.entries.associateWith { 0L }.toMutableMap()
    private val owners = mutableMapOf<Platform, LibraryCacheIdentity?>()
    private var restoreRevision = 0L

    fun accountChanged() {
        Platform.entries.forEach { platform ->
            val next = identity(platform)
            if (!owners.containsKey(platform) || owners[platform] != next) {
                refresh(platform)
            }
        }
    }

    fun refresh(platform: Platform) {
        val expected = identity(platform)
        val restoring = expected != null && owners[platform] == expected && state.value.getValue(platform).restoring
        owners[platform] = expected
        val token = revisions.getValue(platform) + 1
        revisions[platform] = token
        publish(platform, PlaybackJournalState(accountVerified = expected?.verified == true,
            loading = expected != null, restoring = restoring))
        if (expected == null) return
        access(platform, expected, null) { response ->
            if (!current(platform, token, expected)) return@access
            response.onSuccess { publish(platform, PlaybackJournalState(it.queue, it.recent,
                accountVerified = identity(platform)?.verified == true, restoring = state.value.getValue(platform).restoring)) }
                .onFailure { publish(platform, PlaybackJournalState(accountVerified = identity(platform)?.verified == true,
                    restoring = state.value.getValue(platform).restoring, error = safeError(it))) }
        }
    }

    /** Explicit restore only; the service receives a paused original queue and fresh authorization. */
    fun restore(platform: Platform) {
        val expected = identity(platform)
        val saved = state.value.getValue(platform).queue
        if (expected?.verified != true || saved == null || owners[platform]?.sameAttempt(expected) != true) {
            publish(platform, state.value.getValue(platform).copy(error = "account_unavailable")); return
        }
        cancelRestore()
        val token = ++restoreRevision
        publish(platform, state.value.getValue(platform).copy(restoring = true, error = null))
        submit(saved) { result ->
            if (token != restoreRevision || expected != identity(platform)) return@submit
            publish(platform, state.value.getValue(platform).copy(restoring = false,
                error = if (result.isSuccess) state.value.getValue(platform).error?.takeIf { it == "storage" } else "player"))
        }
    }

    /** Called on any new playback intent/ordinary stop so old restore callbacks cannot win. */
    fun cancelRestore() {
        ++restoreRevision
        Platform.entries.forEach { platform ->
            val value = state.value.getValue(platform)
            if (value.restoring) publish(platform, value.copy(restoring = false))
        }
    }

    fun save(platform: Platform, generation: Long, queue: SavedPlaybackQueue?, recent: RecentPlay? = null) {
        val expected = identity(platform)?.takeIf { it.verified && it.generation == generation } ?: return
        val token = revisions.getValue(platform) + 1
        revisions[platform] = token
        owners[platform] = expected
        access(platform, expected, { previous ->
            val recents = if (recent == null) previous.recent else listOf(recent) +
                previous.recent.filter { it.original.key != recent.original.key }.take(99)
            PlaybackJournal(queue, recents)
        }) { response ->
            if (!current(platform, token, expected)) return@access
            response.onSuccess { publish(platform, state.value.getValue(platform).copy(queue = it.queue,
                recent = it.recent, accountVerified = true, loading = false,
                error = state.value.getValue(platform).error?.takeUnless { error -> error == "storage" })) }
                .onFailure { publish(platform, state.value.getValue(platform).copy(error = safeError(it))) }
        }
    }

    fun clearRecent(platform: Platform) = change(platform) { it.copy(recent = emptyList()) }

    private fun change(platform: Platform, transform: (PlaybackJournal) -> PlaybackJournal) {
        val expected = identity(platform) ?: return
        val token = revisions.getValue(platform) + 1
        revisions[platform] = token
        access(platform, expected, transform) { response ->
            if (!current(platform, token, expected)) return@access
            response.onSuccess { publish(platform, state.value.getValue(platform).copy(queue = it.queue, recent = it.recent,
                accountVerified = expected.verified, loading = false)) }
                .onFailure { publish(platform, state.value.getValue(platform).copy(error = safeError(it))) }
        }
    }

    private fun current(platform: Platform, token: Long, expected: LibraryCacheIdentity) =
        revisions[platform] == token && expected == identity(platform)
    private fun publish(platform: Platform, value: PlaybackJournalState) {
        mutable.value = mutable.value + (platform to value)
    }
    private fun safeError(error: Throwable) = (error as? LibraryPageFailure)?.category
        ?.takeIf { it in setOf("account_changed", "storage") } ?: "storage"
}
