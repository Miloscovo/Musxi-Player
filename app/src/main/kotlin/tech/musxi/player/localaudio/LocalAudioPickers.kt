package tech.musxi.player.localaudio

import android.content.Intent

/** Pass the returned Intent/ClipData and flags to the repository; picker cancellation is a no-op. */
object LocalAudioPickers {
    fun exportAudio(fileName: String, mimeType: String = "application/octet-stream"): Intent = create(fileName, mimeType)

    fun exportOrganization(fileName: String = "Musxi-本地整理.musxiorg"): Intent = create(fileName, "application/octet-stream")

    fun restoreOrganization(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun create(fileName: String, mimeType: String): Intent {
        tech.musxi.core.localaudio.LocalAudioLimits.requireName(fileName)
        require(fileName.none { it == '/' || it == '\\' })
        require(Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+").matches(mimeType))
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    fun files(showAllFileTypes: Boolean = false, allowMultiple: Boolean = true): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        // Video containers are accepted for sound-only import; EXTRA_MIME_TYPES narrows the listing.
        if (!showAllFileTypes) putExtra(Intent.EXTRA_MIME_TYPES, FILES_MIME_TYPES)
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    fun directory(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    }

    /**
     * Picks one cover picture. The selection is read once through the returned read grant and then
     * copied into managed artwork, so no persistable permission is requested.
     */
    fun image(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "image/*"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private val FILES_MIME_TYPES = arrayOf("audio/*", "video/*")
}
