package tech.musxi.player.localaudio

import tech.musxi.core.localaudio.LocalAudioError

/** A null key without an error means the source has no extractable image. */
data class LocalAudioArtworkResult(val audioId: String, val artworkKey: String? = null, val error: LocalAudioError? = null)

data class LocalAudioArtworkState(
    val running: Boolean = false,
    val total: Int = 0,
    val processed: Int = 0,
    val currentAudioId: String? = null,
    val results: List<LocalAudioArtworkResult> = emptyList(),
    val completed: Boolean = false,
    val cancelled: Boolean = false,
    val error: LocalAudioError? = null,
)
