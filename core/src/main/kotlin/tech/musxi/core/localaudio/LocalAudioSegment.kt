package tech.musxi.core.localaudio

/** Safe public range metadata. No paths or source capabilities. End is exclusive, in source time. */
data class LocalAudioSegment(val startUs: Long, val endUs: Long, val trackNumber: Int) {
    init {
        require(startUs >= 0 && endUs > startUs)
        require(endUs <= LocalAudioLimits.MAX_POSITION_MS * 1_000)
        require(trackNumber in 1..99)
    }
    val durationMs: Long get() = (endUs - startUs) / 1_000
}

/** Backend-only atomic import input; source identity includes the complete CUE layout/version. */
class LocalCueImportTrack(val metadata: LocalAudioMetadata, val source: LocalAudioSource,
    val segment: LocalAudioSegment) {
    init {
        require(metadata.kind == LocalMediaKind.AUDIO)
        require(metadata.durationMs == segment.durationMs && segment.durationMs > 0)
    }
    override fun toString() = "LocalCueImportTrack(track=${segment.trackNumber}, redacted)"
}
