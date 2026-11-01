package tech.musxi.core.update

/** Public metadata only. Download URLs, local files and certificate objects stay in the adapter. */
data class UpdateVersion(val versionName: String, val versionCode: Long)

enum class UpdateFailure {
    NETWORK, TIMEOUT, RATE_LIMITED, UNSAFE_REDIRECT, INVALID_MANIFEST, DOWNLOAD_UNAVAILABLE,
    SIZE_MISMATCH, CHECKSUM_MISMATCH, SIGNATURE_MISMATCH, PACKAGE_MISMATCH, VERSION_NOT_NEWER,
    STORAGE, PERMISSION_DENIED, CANCELLED, INSTALL_FAILED, RELEASE_CHANGED, DEVELOPMENT_BUILD,
    BUSY, NOT_READY, CHECKPOINT_FAILED, UNSUPPORTED_SDK,
}
enum class UpdateCheckPhase { IDLE, CHECKING, SUCCEEDED, FAILED }
data class UpdateCheckState(
    val phase: UpdateCheckPhase = UpdateCheckPhase.IDLE,
    val reason: UpdateFailure? = null,
    val retryAt: Long? = null,
)
enum class UpdateDownloadPhase { NONE, DOWNLOADING, VERIFYING, READY, NEEDS_PERMISSION, INSTALLING, FAILED }
enum class UpdateInstallOutcome { SUCCEEDED, CANCELLED, FAILED }
data class UpdateDownloadState(
    val phase: UpdateDownloadPhase = UpdateDownloadPhase.NONE,
    val version: String? = null,
    val receivedBytes: Long = 0,
    val totalBytes: Long = 0,
    val reason: UpdateFailure? = null,
)
data class LatestUpdate(
    val version: String,
    val tag: String,
    val prerelease: Boolean,
    val notes: String,
    val publishedAt: String?,
    val releasePage: String,
    val manifestAvailable: Boolean = false,
    val bytes: Long? = null,
    val ignored: Boolean = false,
    val newer: Boolean = false,
    val canDownload: Boolean = false,
    val manifestFailure: UpdateFailure? = null,
)
data class UpdateState(
    val current: UpdateVersion,
    val developmentBuild: Boolean,
    val autoCheck: Boolean = true,
    val lastSuccessAt: Long? = null,
    val check: UpdateCheckState = UpdateCheckState(),
    val latest: LatestUpdate? = null,
    val download: UpdateDownloadState = UpdateDownloadState(),
    val justUpdatedFrom: String? = null,
    val meteredNetwork: Boolean = true,
    val installOutcome: UpdateInstallOutcome? = null,
)

object DailyUpdatePolicy {
    const val INTERVAL_MILLIS = 24 * 60 * 60 * 1000L
    fun shouldCheck(releaseBuild: Boolean, enabled: Boolean, lastSuccess: Long?, now: Long): Boolean =
        releaseBuild && enabled && (lastSuccess == null || (now >= lastSuccess && now - lastSuccess >= INTERVAL_MILLIS))
}

class UpdateException(val reason: UpdateFailure) : java.io.IOException(reason.name)
