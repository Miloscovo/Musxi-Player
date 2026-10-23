package tech.musxi.core.lyrics

/** Availability of verified, aligned content, independently of the latest lookup. */
enum class TranslationAvailability { UNKNOWN, NONE, AVAILABLE }

enum class TranslationLookupStatus { IDLE, PENDING, SUCCEEDED, FAILED, UNSUPPORTED }

/** Fixed categories only; platform payloads and exception messages never enter UI state. */
enum class TranslationReason {
    NOT_IMPLEMENTED,
    NOT_SYNCED,
    NO_LYRICS,
    NOT_PROVIDED,
    NETWORK,
    TIMEOUT,
    PLATFORM_ERROR,
    INVALID_DATA,
    VERSION_MISMATCH,
    UNSUPPORTED_FORMAT,
}

data class TranslationLookup(
    val status: TranslationLookupStatus = TranslationLookupStatus.UNSUPPORTED,
    val reason: TranslationReason? = if (status == TranslationLookupStatus.UNSUPPORTED)
        TranslationReason.NOT_IMPLEMENTED else null,
)

/**
 * A failed lookup may coexist with AVAILABLE content verified for this same original.
 * Default means this client has not implemented translation lookup, never verified absence.
 * Per-line translations live on [LyricLine]; they share the original playback timeline.
 */
data class LyricsTranslation(
    val availability: TranslationAvailability = TranslationAvailability.UNKNOWN,
    val lookup: TranslationLookup = TranslationLookup(),
)
