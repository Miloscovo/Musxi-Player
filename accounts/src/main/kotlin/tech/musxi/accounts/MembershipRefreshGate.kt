package tech.musxi.accounts

import tech.musxi.core.account.Platform

/** Called under AccountRepository's gate; foreground returns refresh at most once a minute. */
internal class MembershipRefreshGate(private val now: () -> Long) {
    private val requested = mutableMapOf<Platform, Pair<Long, Long>>()

    fun accept(platform: Platform, generation: Long, force: Boolean): Boolean {
        val time = now()
        val previous = requested[platform]
        if (!force && previous?.first == generation && time - previous.second in 0 until 60_000L) return false
        requested[platform] = generation to time
        return true
    }
}
