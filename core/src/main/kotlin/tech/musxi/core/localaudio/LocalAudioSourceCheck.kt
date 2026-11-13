package tech.musxi.core.localaudio

/** Public observation only: no file paths, source capabilities or provider exception text. */
data class LocalAudioSourceCheckResult(
    val audioId: String,
    val availability: LocalAudioAvailability? = null,
    val error: LocalAudioError? = null,
    val applied: Boolean = false,
)

data class LocalAudioSourceCheckState(
    val running: Boolean = false,
    val total: Int = 0,
    val processed: Int = 0,
    val currentAudioId: String? = null,
    val results: List<LocalAudioSourceCheckResult> = emptyList(),
    val completed: Boolean = false,
    val cancelled: Boolean = false,
    val error: LocalAudioError? = null,
)
