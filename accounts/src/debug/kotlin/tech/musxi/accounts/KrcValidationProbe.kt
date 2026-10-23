package tech.musxi.accounts

import tech.musxi.core.diagnostics.RequestOperation
import tech.musxi.loginpoc.KugouLibraryApi

/** Debug-only, fixed category/count output; never exposes platform response text. */
class KrcValidationProbe(private val accounts: AccountRepository) {
    data class Summary(val status: String, val diagnostic: String, val rows: Int)
    fun fetch(id: String, result: (Result<Summary>) -> Unit) {
        accounts.withKugouSession({ api, session ->
            val doc = KugouLibraryApi(api).lyricsDocument(session, id)
            Summary(doc.translationStatus.name, doc.diagnostic, doc.rows.size)
        }, result, RequestOperation.LYRICS)
    }
}
