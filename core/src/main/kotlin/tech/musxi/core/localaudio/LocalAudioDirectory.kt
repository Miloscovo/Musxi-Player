package tech.musxi.core.localaudio

/** Public directory association metadata; the system URI stays in backend storage. */
data class LocalAudioDirectoryLink(
    val id: String,
    val folderId: String,
    val name: String,
    val mode: LocalImportMode,
    val includeSubdirectories: Boolean = false,
    val lastRefreshedAtMs: Long? = null,
    val missingCount: Int = 0,
    val error: LocalAudioError? = null,
) {
    init {
        LocalAudioLimits.requireId(id); LocalAudioLimits.requireId(folderId); LocalAudioLimits.requireName(name)
        require(lastRefreshedAtMs == null || lastRefreshedAtMs >= 0)
        require(missingCount in 0..LocalAudioLimits.MAX_ITEMS)
    }
}

class StoredLocalAudioDirectory(val link: LocalAudioDirectoryLink, val treeUri: String) {
    init { require(LocalAudioLimits.isContentUri(treeUri)) }
    override fun toString() = "StoredLocalAudioDirectory(${link.id}, redacted)"
}

/** One provenance record, committed atomically with its audio import. Never publish its identity. */
class LocalAudioDirectoryImport(val directoryId: String, val documentIdentity: String,
    val sizeBytes: Long?, val modifiedAtMs: Long?) {
    init {
        LocalAudioLimits.requireId(directoryId)
        require(documentIdentity.isNotBlank() && documentIdentity.length <= 8192)
        require(sizeBytes == null || sizeBytes in 1..LocalAudioLimits.MAX_FILE_BYTES)
        require(modifiedAtMs == null || modifiedAtMs >= 0)
    }
    override fun toString() = "LocalAudioDirectoryImport($directoryId, redacted)"
}

class LocalAudioDirectoryEntry(val origin: LocalAudioDirectoryImport, val audioId: String) {
    init { LocalAudioLimits.requireId(audioId) }
    override fun toString() = "LocalAudioDirectoryEntry($audioId, redacted)"
}

data class LocalAudioDirectoryRefreshResult(val directoryId: String,
    val results: List<LocalAudioImportResult>, val missingCount: Int)
