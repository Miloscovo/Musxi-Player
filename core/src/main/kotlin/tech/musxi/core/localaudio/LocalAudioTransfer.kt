package tech.musxi.core.localaudio

enum class LocalAudioTransferKind { AUDIO_EXPORT, ORGANIZATION_BACKUP, ORGANIZATION_PREVIEW, ORGANIZATION_RESTORE }

/** Public transfer status; no target URI, source capability or raw exception. */
data class LocalAudioTransferState(
    val running: Boolean = false,
    val kind: LocalAudioTransferKind? = null,
    val completedBytes: Long = 0,
    val expectedBytes: Long? = null,
    val completed: Boolean = false,
    val cancelled: Boolean = false,
    val error: LocalAudioError? = null,
    val cleanupFailed: Boolean = false,
)

data class LocalAudioExportResult(val audioId: String, val bytes: Long)
data class LocalAudioBackupResult(val backupId: String, val items: Int, val folders: Int)
