package tech.musxi.player

import tech.musxi.core.account.Platform
import tech.musxi.core.library.PlaybackQueue
import tech.musxi.core.recommendation.RecommendationsBrowser

/** UI facade: metadata and revision only; sessions and platform responses remain in accounts. */
class AppRecommendations internal constructor(
    private val browser: RecommendationsBrowser,
    private val playSnapshot: (PlaybackQueue, (Result<Unit>) -> Unit) -> Unit,
) {
    val state = browser.state
    fun open(platform: Platform) = browser.open(platform)
    fun refresh(platform: Platform) = browser.refresh(platform)
    fun cancel(platform: Platform) = browser.cancel(platform)
    internal fun clearCachedResults() = browser.clearCachedResults()

    /** Empty lists fail explicitly; refresh races are rejected rather than playing another index. */
    fun play(platform: Platform, startIndex: Int = 0, expectedRevision: Long, result: (Result<Unit>) -> Unit = {}) {
        browser.submitQueue(platform, startIndex, expectedRevision) { playSnapshot(it, result) }
            .onFailure { result(Result.failure(it)) }
    }
}
