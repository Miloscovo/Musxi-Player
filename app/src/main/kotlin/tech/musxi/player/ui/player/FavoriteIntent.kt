package tech.musxi.player.ui.player

import tech.musxi.core.library.FavoritesState
import tech.musxi.core.library.SongSummary

/** What the user just asked the heart to become, until the backend confirms or refuses it. */
data class FavoriteIntent(
    val song: SongSummary,
    val liked: Boolean,
    /** True once `setLiked` has been called for this intent; it is submitted at most once per attempt. */
    val submitted: Boolean = false,
    val attempts: Int = 0,
    /** Monotonic clock reading when the user tapped, used only to give up after [FAVORITE_TIMEOUT_MS]. */
    val startedAtMs: Long,
)

/** What the shell should do next with an intent. */
sealed interface FavoriteStep {
    /** Nothing yet: the collection is syncing or another write is in flight. */
    data object Wait : FavoriteStep

    /** Call `setLiked(song, liked)` now with the returned intent (already marked as submitted). */
    data class Submit(val intent: FavoriteIntent) : FavoriteStep

    /** Confirmed: clear the intent. */
    data object Done : FavoriteStep

    /** Clear the intent, show [message] and shake the heart. */
    data class Fail(val message: String) : FavoriteStep
}

internal const val FAVORITE_TIMEOUT_MS = 20_000L
internal const val FAVORITE_MAX_ATTEMPTS = 3

/**
 * The heart the player shows.
 *
 * While an intent is open for this song the heart shows the requested state at once and `syncing`
 * stays on until the backend settles; otherwise it shows the confirmed state, with `syncing` only
 * when the backend is writing this very song. `enabled` is false only for a collection that has
 * been read and turned out to be read-only: an unsynced collection still takes taps, because the
 * intent waits for the sync.
 */
fun favoriteUiOf(state: FavoritesState?, song: SongSummary?, intent: FavoriteIntent?): FavoriteUi? {
    if (song == null || state == null) return null
    val confirmed = song.key in state.keys
    val own = intent?.takeIf { it.song.key == song.key }
    return FavoriteUi(
        liked = own?.liked ?: confirmed,
        syncing = own != null || state.pending == song.key || song.key in state.queuedKeys,
        enabled = !(state.synced && !state.canWrite),
    )
}

/**
 * Decides the next move for [intent] given the backend [state].
 *
 * The backend refuses `setLiked` while it is loading or verifying another write, and it never
 * updates optimistically, so the shell keeps the tap as an intent and feeds it to the backend only
 * when the backend can take it. Each call is a pure function of its arguments; the shell re-asks
 * whenever the state changes or a second has passed.
 */
fun nextFavoriteStep(state: FavoritesState, intent: FavoriteIntent, nowMs: Long): FavoriteStep {
    if (nowMs - intent.startedAtMs > FAVORITE_TIMEOUT_MS) return FavoriteStep.Fail("收藏同步太久，请稍后再试")
    val confirmed = intent.song.key in state.keys
    if (!intent.submitted) {
        if (state.synced && !state.loading && state.pending == null) {
            return when {
                confirmed == intent.liked -> FavoriteStep.Done
                !state.canWrite -> FavoriteStep.Fail(favoriteFailureText("permission"))
                else -> FavoriteStep.Submit(
                    intent.copy(submitted = true, attempts = intent.attempts + 1),
                )
            }
        }
        return FavoriteStep.Wait
    }
    // The backend now queues writes: a submitted song can be waiting behind another one rather than
    // running, and only its absence from both `pending` and `queuedKeys` means its write is over.
    if (state.pending == intent.song.key || intent.song.key in state.queuedKeys || state.loading) {
        return FavoriteStep.Wait
    }
    return when {
        !state.synced -> FavoriteStep.Fail(favoriteFailureText(state.error))
        confirmed == intent.liked -> FavoriteStep.Done
        // `setLiked` returned early with a reason (for example an unverified account) without
        // touching the collection; submitting again would only repeat it.
        state.error != null -> FavoriteStep.Fail(favoriteFailureText(state.error))
        // The user changed their mind while the previous write was being verified.
        intent.attempts < FAVORITE_MAX_ATTEMPTS ->
            FavoriteStep.Submit(intent.copy(attempts = intent.attempts + 1))
        else -> FavoriteStep.Fail("没能确认收藏状态，请稍后再试")
    }
}

/** Plain-language reason for a backend error category from `FavoritesBrowser`. */
internal fun favoriteFailureText(error: String?): String = when (error) {
    "permission" -> "当前账号不能修改收藏"
    "account_unverified", "expired", "account_unavailable", "account_changed" ->
        "账号需要重新验证，请到「我的」检查"
    "network", "timeout", "http" -> "网络异常，收藏未生效"
    "rate", "risk", "limit" -> "操作太频繁，请稍后再试"
    "write_unverified" -> "平台没有确认这次收藏，请稍后再试"
    else -> "收藏未生效，请稍后重试"
}
