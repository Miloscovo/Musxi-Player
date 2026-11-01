package tech.musxi.accounts

import tech.musxi.core.playback.PlaybackQualities

/** Only entitlement/unavailability permit falling back; account/network failures stay visible. */
internal fun <T> resolvePreferredQuality(platform: String, preferred: String?, exact: String?,
    category: (Throwable) -> String?, request: (String) -> T): T {
    if (exact != null) return request(exact)
    var last: Throwable? = null
    for (id in PlaybackQualities.candidates(platform, preferred)) {
        try { return request(id) } catch (error: Exception) {
            if (category(error) !in setOf("permission", "unavailable")) throw error
            last = error
        }
    }
    throw last ?: IllegalArgumentException("Unknown quality platform")
}
