package tech.musxi.core.playback

import tech.musxi.core.account.Platform

/** Public explanation; only explicit platform evidence permits a specific entitlement label. */
enum class FailureReason {
    MEMBERSHIP_REQUIRED, COPYRIGHT_RESTRICTED, PREVIEW_ONLY, PERMISSION_RESTRICTED,
    NETWORK, TIMEOUT, AUTH_REQUIRED, PLATFORM_UNAVAILABLE, RATE_LIMIT, PROTOCOL,
    BACKGROUND_RESTRICTED, UNKNOWN;

    companion object {
        fun fromCategory(category: String?): FailureReason = when (category) {
            "membership" -> MEMBERSHIP_REQUIRED
            "copyright" -> COPYRIGHT_RESTRICTED
            "preview" -> PREVIEW_ONLY
            "permission" -> PERMISSION_RESTRICTED
            "network", "http" -> NETWORK
            "timeout" -> TIMEOUT
            "expired", "account_changed" -> AUTH_REQUIRED
            "unavailable", "platform" -> PLATFORM_UNAVAILABLE
            "rate", "risk" -> RATE_LIMIT
            "protocol", "identity", "endpoint", "incomplete", "limit", "request" -> PROTOCOL
            else -> UNKNOWN
        }
        fun fromFailure(failure: PlaybackFailure): FailureReason = when (failure) {
            PlaybackFailure.PERMISSION -> PERMISSION_RESTRICTED
            PlaybackFailure.NETWORK -> NETWORK
            PlaybackFailure.TIMEOUT -> TIMEOUT
            PlaybackFailure.AUTH_REQUIRED -> AUTH_REQUIRED
            PlaybackFailure.UNAVAILABLE, PlaybackFailure.SOURCE_EXPIRED -> PLATFORM_UNAVAILABLE
            PlaybackFailure.RATE_LIMIT -> RATE_LIMIT
            PlaybackFailure.PROTOCOL -> PROTOCOL
            PlaybackFailure.BACKGROUND_RESTRICTED -> BACKGROUND_RESTRICTED
            PlaybackFailure.UNKNOWN -> UNKNOWN
        }
    }
}

data class PlaybackFailureReason(val reason: FailureReason, val platform: Platform? = null)
