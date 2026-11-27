package tech.musxi.core.localaudio

/** Decoder capability, separate from broader container recognition and the application gate. */
object DsfPlaybackSupport {
    const val PCM_RATE_HZ = 176_400
    fun supportsFormat(sampleRateHz: Int, channelCount: Int): Boolean =
        sampleRateHz in setOf(2_822_400, 5_644_800, 11_289_600) && channelCount in 1..2

    fun supports(info: DsfFileInfo): Boolean = supportsFormat(info.sampleRateHz, info.channelCount) &&
        info.fileSize <= LocalAudioLimits.MAX_FILE_BYTES && info.durationMs <= LocalAudioLimits.MAX_POSITION_MS

    fun isCandidate(fileName: String, mime: String? = null): Boolean =
        fileName.substringAfterLast('.', "").equals("dsf", ignoreCase = true) ||
            mime?.lowercase(java.util.Locale.ROOT) in setOf("audio/dsf", "audio/x-dsf", "audio/dsd")
}
