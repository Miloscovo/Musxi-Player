package tech.musxi.accounts

import tech.musxi.core.playback.PlaybackQualities

/** Only entitlement/unavailability permit falling back; account/network failures stay visible. */
internal fun <T> resolvePreferredQuality(platform: String, preferred: String?, exact: String?,
    category: (Throwable) -> String?, isCurrent: () -> Boolean = { true }, request: (String) -> T): T {
    fun checkCurrent() {
        if (!isCurrent()) throw tech.musxi.core.library.LibraryPageFailure("cancelled")
    }
    if (exact != null) { checkCurrent(); return request(exact).also { checkCurrent() } }
    var last: Throwable? = null
    for (id in PlaybackQualities.candidates(platform, preferred)) {
        checkCurrent()
        try { return request(id).also { checkCurrent() } } catch (error: Exception) {
            checkCurrent()
            if (category(error) !in setOf("permission", "membership", "copyright", "preview", "unavailable")) throw error
            last = error
        }
    }
    throw last ?: IllegalArgumentException("Unknown quality platform")
}
