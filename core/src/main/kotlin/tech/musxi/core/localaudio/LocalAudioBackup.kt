package tech.musxi.core.localaudio

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Organization only: no audio bytes, document capabilities, directory links or active queue. */
class LocalAudioBackup(
    val id: String,
    val createdAtMs: Long,
    items: List<LocalAudioBackupItem>,
    folders: List<LocalAudioFolder>,
    members: List<LocalAudioMember>,
    progress: List<LocalAudioProgress>,
    cursors: List<LocalAudioFolderCursor>,
    bookmarks: List<LocalAudioBookmark>,
) {
    val items = items.toList()
    val folders = folders.toList()
    val members = members.toList()
    val progress = progress.toList()
    val cursors = cursors.toList()
    val bookmarks = bookmarks.toList()
    init { validate() }

    fun validate() {
        LocalAudioLimits.requireId(id)
        require(createdAtMs >= 0)
        require(items.size <= LocalAudioLimits.MAX_ITEMS && folders.size <= LocalAudioLimits.MAX_FOLDERS)
        require(members.size <= LocalAudioLimits.MAX_MEMBERS && bookmarks.size <= LocalAudioLimits.MAX_BOOKMARKS)
        require(progress.size <= items.size && cursors.size <= folders.size)
        val audioIds = items.map { it.item.id }.toSet()
        val folderIds = folders.map { it.id }.toSet()
        require(audioIds.size == items.size && folderIds.size == folders.size)
        require(progress.map { it.audioId }.toSet().size == progress.size)
        require(cursors.map { it.folderId }.toSet().size == cursors.size)
        require(bookmarks.map { it.id }.toSet().size == bookmarks.size)
        require(items.map { it.item.mode to it.sourceKey }.toSet().size == items.size)
        val membership = members.map { it.folderId to it.audioId }.toSet()
        require(membership.size == members.size)
        require(members.all { it.folderId in folderIds && it.audioId in audioIds && it.position >= 0 })
        require(members.groupBy { it.folderId }.values.all { group -> group.map { it.position }.toSet().size == group.size })
        require(progress.all { it.audioId in audioIds })
        require(bookmarks.all { it.audioId in audioIds })
        require(cursors.all { (it.folderId to it.audioId) in membership })
    }

    override fun toString() = "LocalAudioBackup($id, ${items.size} items)"
}

/** Hashes permit matching existing imports without making a persisted URI an authority to read it. */
class LocalAudioBackupItem(val item: LocalAudioItem, val sourceKey: String) {
    init { require(Regex("[0-9a-f]{64}").matches(sourceKey)) }
    override fun toString() = "LocalAudioBackupItem(${item.id}, redacted)"
}

object LocalAudioBackupKeys {
    fun source(value: StoredLocalAudio): String {
        if (value.source.mode == LocalImportMode.COPY) return value.source.location
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeUTF("musxi-local-reference-v1")
            val identity = value.source.identity.toByteArray(Charsets.UTF_8)
            out.writeInt(identity.size); out.write(identity)
            out.writeLong(value.item.metadata.sizeBytes ?: -1)
            out.writeLong(value.item.metadata.modifiedAtMs ?: -1)
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

data class LocalAudioRestorePreview(
    val backupId: String,
    val expectedRevision: Long,
    val matchedItems: Int,
    val unmatchedItems: Int,
    val folders: Int,
    val bookmarks: Int,
)

data class LocalAudioRestoreResult(
    val backupId: String,
    val matchedItems: Int,
    val unmatchedItems: Int,
    val addedFolders: Int,
    val addedMemberships: Int,
    val addedBookmarks: Int,
)
