package tech.musxi.core.localaudio

enum class DsfPreviewError { INVALID_ARGUMENT, NOT_FOUND, PERMISSION_REQUIRED, READ_FAILED,
    TOO_LARGE, INVALID_DSF, UNSUPPORTED_DSF, SOURCE_CHANGED }
class DsfPreviewFailure(val reason: DsfPreviewError) : java.io.IOException(reason.name)
enum class DsfTagStatus { ABSENT, READ, UNAVAILABLE }

/** Source facts only. Recognition does not enable decoding, DoP or native DSD output. */
data class DsfPreviewInfo(
    val fileName: String,
    val sampleRateHz: Int,
    val channelCount: Int,
    val durationMs: Long,
    val dsdMultiplier: Int?,
    val bitsPerSample: Int = 1,
    val tags: DsfTextTags? = null,
    val tagStatus: DsfTagStatus = DsfTagStatus.ABSENT,
    val playbackSupported: Boolean = false,
)
data class DsfPreviewState(
    val revision: Long = 0,
    val running: Boolean = false,
    val completed: Boolean = false,
    val cancelled: Boolean = false,
    val preview: DsfPreviewInfo? = null,
    val error: DsfPreviewError? = null,
)
