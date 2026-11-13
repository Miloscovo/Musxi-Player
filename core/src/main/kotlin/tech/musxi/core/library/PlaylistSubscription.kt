package tech.musxi.core.library

import tech.musxi.core.account.Platform

/** One verified account attempt's view of whether it follows a collected playlist. */
data class PlaylistSubscription(
    val playlist: PlaylistSummary,
    val accountGeneration: Long,
    val followed: Boolean,
)

data class PlaylistFollowResult(
    val playlist: MediaKey,
    val requestedFollowed: Boolean,
    val status: PlaylistChangeStatus,
    val confirmedFollowed: Boolean? = null,
    val error: String? = null,
)

/**
 * Transport callbacks must finish once; account credentials never cross this boundary.
 * Only playlists the platform already verified as collected, non-self and non-system may be read here.
 */
interface PlaylistSubscriptionPort {
    fun generation(platform: Platform): Long?
    fun read(playlist: MediaKey, result: (Result<PlaylistSubscription>) -> Unit)
    fun setFollowed(snapshot: PlaylistSubscription, followed: Boolean, result: (Result<Unit>) -> Unit)
}

/** Single follow bit per whole playlist: one fresh read, at most one write, one confirming read. */
class PlaylistSubscriptionCoordinator(private val port: PlaylistSubscriptionPort) {
    private val following = mutableSetOf<Platform>()

    fun read(key: MediaKey, result: (Result<PlaylistSubscription>) -> Unit) {
        val generation = port.generation(key.platform) ?: return fail("account_unverified", result)
        fun current() = port.generation(key.platform) == generation
        port.read(key) fetched@{ response ->
            if (!current()) return@fetched fail("account_changed", result)
            val snapshot = response.getOrElse { return@fetched result(Result.failure(it)) }
            if (snapshot.playlist.key != key || snapshot.accountGeneration != generation) {
                return@fetched fail("protocol", result)
            }
            if (snapshot.playlist.kind != PlaylistKind.COLLECTED) return@fetched fail("not_collected", result)
            result(Result.success(snapshot))
        }
    }

    fun update(snapshot: PlaylistSubscription, followed: Boolean, result: (Result<PlaylistFollowResult>) -> Unit) {
        val key = snapshot.playlist.key
        val platform = key.platform
        if (snapshot.playlist.kind != PlaylistKind.COLLECTED) return fail("not_collected", result)
        if (port.generation(platform) != snapshot.accountGeneration) return fail("account_changed", result)
        synchronized(following) { if (!following.add(platform)) return fail("busy", result) }
        fun current() = port.generation(platform) == snapshot.accountGeneration
        fun finish(status: PlaylistChangeStatus, confirmed: Boolean? = null, error: String? = null) {
            synchronized(following) { following.remove(platform) }
            result(Result.success(PlaylistFollowResult(key, followed, status, confirmed, error)))
        }
        // The caller's snapshot carries intent only; every outcome is settled from fresh state.
        port.read(key) before@{ response ->
            if (!current()) return@before finish(PlaylistChangeStatus.FAILED, null, "account_changed")
            val actual = response.getOrNull()
                ?: return@before finish(PlaylistChangeStatus.FAILED, null, category(response.exceptionOrNull()!!))
            if (actual.playlist.key != key || actual.accountGeneration != snapshot.accountGeneration ||
                actual.playlist.kind != PlaylistKind.COLLECTED) {
                return@before finish(PlaylistChangeStatus.FAILED, null, "protocol")
            }
            if (actual.followed == followed) return@before finish(PlaylistChangeStatus.UNCHANGED, actual.followed)
            // An unchanged switch is preservation intent, not permission to undo a remote edit.
            if (snapshot.followed == followed) {
                return@before finish(PlaylistChangeStatus.CONFLICT, actual.followed, "conflict")
            }
            port.setFollowed(actual, followed) write@{ write ->
                // An attempted write can reach the server even when its response is lost.
                if (!current()) return@write finish(PlaylistChangeStatus.UNKNOWN, null, "account_changed")
                port.read(key) confirm@{ confirmation ->
                    if (!current()) return@confirm finish(PlaylistChangeStatus.UNKNOWN, null, "account_changed")
                    val confirmed = confirmation.getOrNull()
                        ?: return@confirm finish(PlaylistChangeStatus.UNKNOWN, null,
                            category(confirmation.exceptionOrNull()!!))
                    if (confirmed.playlist.key != key || confirmed.accountGeneration != snapshot.accountGeneration ||
                        confirmed.playlist.kind != PlaylistKind.COLLECTED) {
                        return@confirm finish(PlaylistChangeStatus.UNKNOWN, null, "protocol")
                    }
                    when {
                        confirmed.followed == followed -> finish(PlaylistChangeStatus.APPLIED, confirmed.followed)
                        write.isFailure -> finish(PlaylistChangeStatus.FAILED, confirmed.followed,
                            category(write.exceptionOrNull()!!))
                        else -> finish(PlaylistChangeStatus.FAILED, confirmed.followed, "not_confirmed")
                    }
                }
            }
        }
    }

    private fun category(error: Throwable): String = (error as? LibraryPageFailure)?.category ?: "unavailable"
    private fun <T> fail(category: String, result: (Result<T>) -> Unit) = result(Result.failure(LibraryPageFailure(category)))
}
