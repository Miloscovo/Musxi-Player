package tech.musxi.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import tech.musxi.player.localaudio.LocalAudioPickers

/** Internal debug-only SAF bridge. Actual result flags stay in memory, never in diagnostics. */
class LocalAudioPickerValidationActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        current = this
        if (state != null) return
        selection = null
        val picker = when (intent.getStringExtra("operation")) {
            "files" -> LocalAudioPickers.files()
            "tree" -> LocalAudioPickers.directory()
            "followup_files", "followup_oem_files" -> LocalAudioPickers.files(showAllFileTypes = true)
            "expansion_files" -> LocalAudioPickers.files(showAllFileTypes = true)
            "dsf_pipe_files" -> LocalAudioPickers.files(showAllFileTypes = true, allowMultiple = false)
            "dsf_actual_external", "dsf_actual_downloads" -> LocalAudioPickers.files(showAllFileTypes = true, allowMultiple = false)
            "expansion_tree" -> LocalAudioPickers.directory()
            "followup_oem_tree" -> LocalAudioPickers.directory()
            "export" -> LocalAudioPickers.exportAudio("musxi-device-export.wav", "audio/wav")
            "backup" -> LocalAudioPickers.exportOrganization("musxi-device-organization.musxiorg")
            "restore" -> LocalAudioPickers.restoreOrganization()
            else -> { finish(); return }
        }
        val operation = intent.getStringExtra("operation").orEmpty()
        val directory = when {
            operation.startsWith("expansion_") -> "Musxi-Expansion-20261114"
            operation.startsWith("followup_") -> "Musxi-Followup-20261110"
            else -> DIRECTORY
        }
        picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:Download/$directory"))
        if (operation == "dsf_pipe_files") picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
            DocumentsContract.buildRootUri("$packageName.dsf.validation.documents", "root"))
        if (operation == "dsf_actual_external") picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/Musxi-Actual-20261116"))
        if (operation == "dsf_actual_downloads") picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
            DocumentsContract.buildRootUri("com.android.providers.downloads.documents", "downloads"))
        // Fixed acceptance provider; production pickers continue using the user's system choice.
        picker.setPackage(if (operation.startsWith("followup_oem_")) "com.coloros.filemanager" else "com.android.documentsui")
        @Suppress("DEPRECATION")
        startActivityForResult(picker, 41)
    }

    @Deprecated("Fixed debug bridge uses the platform result contract")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 41) return
        val uris = buildList {
            data?.data?.let(::add)
            data?.clipData?.let { clip -> repeat(clip.itemCount) { add(clip.getItemAt(it).uri) } }
        }.distinct()
        selection = Selection(resultCode == RESULT_OK, uris, data?.flags ?: 0)
        // Keep temporary URI grants alive until the followup importer has consumed the result.
        if (intent.getStringExtra("operation").orEmpty().let { !it.startsWith("followup_") && !it.startsWith("expansion_") && it != "dsf_pipe_files" && !it.startsWith("dsf_actual_") } || resultCode != RESULT_OK) finish()
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    class Selection(val accepted: Boolean, val uris: List<Uri>, val flags: Int) {
        override fun toString() = "Selection(accepted=$accepted, count=${uris.size})"
    }

    companion object {
        const val DIRECTORY = "Musxi-Acceptance-20261110"
        @Volatile var selection: Selection? = null
            private set
        private var current: LocalAudioPickerValidationActivity? = null
        fun clearSelection() { selection = null }
        fun finishSelection() { current?.finish() }
    }
}
