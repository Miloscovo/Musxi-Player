package tech.musxi.accounts

import tech.musxi.core.account.Platform
import tech.musxi.core.library.LibraryPageFailure
import tech.musxi.core.library.MediaKey
import tech.musxi.core.lyrics.LrcParser
import tech.musxi.core.lyrics.ParsedLyrics
import tech.musxi.neteaseloginpoc.NeteaseApi
import tech.musxi.neteaseloginpoc.NeteaseLibraryApi
import tech.musxi.qqloginpoc.QqMusicApi
import tech.musxi.qqloginpoc.QqLibraryApi
import tech.musxi.loginpoc.KugouApi
import tech.musxi.loginpoc.KugouLibraryApi

/** Requests and bounded parsing share the account worker; session material stays internal. */
class LyricsRepository(private val accounts: AccountRepository) {
    class LyricsFailure internal constructor(category: String, val diagnostic: String) : LibraryPageFailure(category)
    fun fetch(key: MediaKey, result: (Result<ParsedLyrics>) -> Unit) {
        val callback: (Result<ParsedLyrics>) -> Unit = { response ->
            result(response.fold({ Result.success(it) }, { error ->
                val code = when (error) {
                    is NeteaseApi.ApiException -> error.code
                    is QqMusicApi.ApiException -> error.code
                    is KugouApi.ApiException -> error.code
                    is IllegalArgumentException -> "protocol"
                    else -> "unavailable"
                }
                val diagnostic = (error as? KugouApi.ApiException)?.identityDiagnostic?.takeIf { it in setOf(
                    "lyrics_search_status", "lyrics_search_rows", "lyrics_search_candidate", "lyrics_search_key",
                    "lyrics_download_status", "lyrics_content_type", "lyrics_content_encoding",
                    "lyrics_transport_limit", "lyrics_transport_json", "lyrics_search_json_html", "lyrics_search_json_array",
                    "lyrics_search_json_other", "lyrics_download_json_html", "lyrics_download_json_array", "lyrics_download_json_other",
                    "lyrics_search_json_empty", "lyrics_search_json_null", "lyrics_search_json_bom", "lyrics_search_json_compressed",
                    "lyrics_search_json_jsonp", "lyrics_download_json_empty", "lyrics_download_json_null", "lyrics_download_json_bom",
                    "lyrics_download_json_compressed", "lyrics_download_json_jsonp") }
                    ?: if (error is IllegalArgumentException) "lyrics_parse" else "NONE"
                Result.failure(LyricsFailure(code, diagnostic))
            }))
        }
        when (key.platform) {
            Platform.NETEASE -> accounts.withNeteaseSession({ api, session ->
                LrcParser.parse(NeteaseLibraryApi(api).lyrics(session, key.id))
            }, callback)
            Platform.QQ -> accounts.withQqSession({ api, session ->
                LrcParser.parse(QqLibraryApi(api).lyrics(session, key.id))
            }, callback)
            Platform.KUGOU -> accounts.withKugouSession({ api, session ->
                LrcParser.parse(KugouLibraryApi(api).lyrics(session, key.id))
            }, callback)
        }
    }
}
