package tech.musxi.core.localaudio

/** Facts read from the selected source header, never the decoded or physical output format. */
data class LocalAudioFormatInfo(val audioId: String, val codec: String? = null,
    val sampleRateHz: Int? = null, val bitsPerSample: Int? = null, val channelCount: Int? = null)
