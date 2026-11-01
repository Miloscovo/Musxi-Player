package tech.musxi.core.playback

/** App-wide quality policy used when a platform has no explicit remembered quality. */
enum class PreferredAudioQuality {
    STANDARD,
    HIGH,
    LOSSLESS,
    BEST;

    companion object {
        /** Accepts the values previously stored by AccountRepository.DefaultAudioQuality. */
        fun fromStored(value: String?): PreferredAudioQuality =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: STANDARD
    }
}
