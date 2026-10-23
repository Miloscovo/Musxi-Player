package tech.musxi.playback

/** Debug-only entry point. Call on the main thread; never edits persisted queues or accounts. */
object PlaybackServiceValidation {
    fun loseQueue(): Boolean = MusxiPlaybackService.validationService?.get()?.validationLoseQueue() ?: false
    fun foreground(hold: Boolean, fastTimeout: Boolean): Boolean =
        MusxiPlaybackService.validationService?.get()?.validationForeground(hold, fastTimeout) ?: false
    fun foregroundState(): Pair<Boolean, Boolean>? =
        MusxiPlaybackService.validationService?.get()?.validationForegroundState()
}
