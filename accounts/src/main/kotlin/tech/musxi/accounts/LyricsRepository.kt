package tech.musxi.accounts

import tech.musxi.core.account.Platform
import tech.musxi.core.diagnostics.RequestOperation
import tech.musxi.core.library.LibraryPageFailure
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.LibraryCacheIdentity
import tech.musxi.core.lyrics.LrcParser
import tech.musxi.core.lyrics.ParsedLyrics
import tech.musxi.core.lyrics.LyricsTranslationAligner
import tech.musxi.core.lyrics.KrcTranslationAligner
import tech.musxi.core.lyrics.TranslationReason
import tech.musxi.core.lyrics.LyricsTranslation
import tech.musxi.core.lyrics.TranslationLookup
import tech.musxi.core.lyrics.TranslationLookupStatus
import tech.musxi.neteaseloginpoc.NeteaseApi
import tech.musxi.neteaseloginpoc.NeteaseLibraryApi
import tech.musxi.qqloginpoc.QqMusicApi
import tech.musxi.qqloginpoc.QqLibraryApi
import tech.musxi.loginpoc.KugouApi
import tech.musxi.loginpoc.KugouLibraryApi

/** Requests and bounded parsing share the account worker; session material stays internal. */
class LyricsRepository(private val accounts: AccountRepository) {
    class LyricsFailure internal constructor(category: String, val diagnostic: String) : LibraryPageFailure(category)
    private class Stage(val key: MediaKey, val owner: LibraryCacheIdentity, val raw: String,
        val parsed: ParsedLyrics, val document: Any) {
        var initial = true
        override fun toString() = "LyricsStage(redacted)"
    }
    // Main-thread bounded contexts; private platform documents/handles never enter UI or disk.
    private val stages = java.util.IdentityHashMap<ParsedLyrics, Stage>()
    private val order = java.util.ArrayDeque<ParsedLyrics>()

    fun accountChanged() {
        order.removeAll { parsed -> stages[parsed]?.let { cached ->
            val identity = accounts.playbackIdentity(cached.key.platform)
            (!cached.owner.sameAttempt(identity) || identity?.verified != true).also { if (it) stages.remove(parsed) }
        } ?: true }
    }

    fun releaseOriginal(parsed: ParsedLyrics) {
        stages.remove(parsed)
        order.removeAll { it === parsed }
    }

    fun fetchOriginal(key: MediaKey, result: (Result<ParsedLyrics>) -> Unit) = fetchOriginal(key, { true }, result)

    fun fetchOriginal(key: MediaKey, isCurrent: () -> Boolean, result: (Result<ParsedLyrics>) -> Unit) {
        val owner = accounts.playbackIdentity(key.platform)?.takeIf { it.verified }
            ?: return result(Result.failure(LyricsFailure("account_changed", "NONE")))
        fun stage(raw: String, document: Any): Stage {
            val parsed = LrcParser.parse(raw)
            val original = parsed.copy(translation = LyricsTranslation(lookup = TranslationLookup(
                if (parsed.lines.isNotEmpty()) TranslationLookupStatus.IDLE else TranslationLookupStatus.UNSUPPORTED,
                if (parsed.lines.isNotEmpty()) null else if (parsed.plainText.isBlank())
                    TranslationReason.NO_LYRICS else TranslationReason.NOT_SYNCED)))
            return Stage(key, owner, raw, original, document)
        }
        val callback: (Result<Stage>) -> Unit = callback@{ response ->
            if (!isCurrent()) return@callback
            val current = accounts.playbackIdentity(key.platform)
            if (!owner.sameAttempt(current) || current?.verified != true)
                result(Result.failure(LyricsFailure("account_changed", "NONE")))
            else normalized(result)(response.map { value ->
                accountChanged()
                stages[value.parsed] = value
                order.addLast(value.parsed)
                while (order.size > 8) stages.remove(order.removeFirst())
                value.parsed
            })
        }
        when (key.platform) {
            Platform.NETEASE -> accounts.withNeteaseSession({ api, session ->
                if (!isCurrent()) throw LibraryPageFailure("cancelled")
                val doc = NeteaseLibraryApi(api).lyricsDocument(session, key.id)
                stage(doc.original, doc)
            }, callback, RequestOperation.LYRICS)
            Platform.QQ -> accounts.withQqSession({ api, session ->
                if (!isCurrent()) throw LibraryPageFailure("cancelled")
                val doc = QqLibraryApi(api).lyricsDocument(session, key.id)
                stage(doc.original, doc)
            }, callback, RequestOperation.LYRICS)
            Platform.KUGOU -> accounts.withKugouSession({ api, session ->
                if (!isCurrent()) throw LibraryPageFailure("cancelled")
                val doc = KugouLibraryApi(api).lyricsOriginal(session, key.id)
                stage(doc.original, doc)
            }, callback, RequestOperation.LYRICS)
        }
    }

    /** Initial lookup consumes the same response; explicit refresh never replaces the original. */
    fun fetchTranslation(key: MediaKey, original: ParsedLyrics, refresh: Boolean,
        result: (Result<ParsedLyrics>) -> Unit) {
        val stage = stages[original]
        val owner = accounts.playbackIdentity(key.platform)
        if (stage == null || stage.key != key || !stage.owner.sameAttempt(owner) || owner?.verified != true) {
            result(Result.failure(LyricsFailure(if (owner?.verified == true) "unavailable" else "account_changed", "NONE")))
            return
        }
        val useInitial = stage.initial && !refresh
        stage.initial = false
        fun align(raw: String, translated: () -> ParsedLyrics): ParsedLyrics =
            if (raw == stage.raw) translated() else
                LyricsTranslationAligner.failed(original, TranslationReason.VERSION_MISMATCH)
        val callback = normalized(result)
        when (key.platform) {
            Platform.NETEASE -> accounts.withNeteaseSession({ api, session ->
                val doc = if (useInitial) stage.document as NeteaseLibraryApi.LyricsDocument
                    else NeteaseLibraryApi(api).lyricsDocument(session, key.id)
                align(doc.original) { if (doc.translationStatus == NeteaseLibraryApi.TranslationStatus.INVALID)
                    LyricsTranslationAligner.failed(original, TranslationReason.INVALID_DATA)
                    else LyricsTranslationAligner.parse(doc.original, doc.translation) }
            }, callback, RequestOperation.LYRICS)
            Platform.QQ -> accounts.withQqSession({ api, session ->
                val doc = if (useInitial) stage.document as QqLibraryApi.LyricsDocument
                    else QqLibraryApi(api).lyricsDocument(session, key.id)
                align(doc.original) { if (doc.translationStatus == QqLibraryApi.TranslationStatus.INVALID)
                    LyricsTranslationAligner.failed(original, TranslationReason.INVALID_DATA)
                    else LyricsTranslationAligner.parse(doc.original, doc.translation) }
            }, callback, RequestOperation.LYRICS)
            Platform.KUGOU -> accounts.withKugouSession({ api, session ->
                val doc = KugouLibraryApi(api).lyricsTranslation(session, stage.document as KugouLibraryApi.LyricsOriginal)
                align(doc.original) { alignKugou(doc) }
            }, callback, RequestOperation.LYRICS)
        }
    }

    private fun alignKugou(document: KugouLibraryApi.LyricsDocument): ParsedLyrics = when (document.translationStatus) {
        KugouLibraryApi.TranslationStatus.PRESENT, KugouLibraryApi.TranslationStatus.ABSENT ->
            if (document.original.isBlank()) LyricsTranslationAligner.parse(document.original, null)
            else KrcTranslationAligner.parse(document.original, document.rows.map {
                KrcTranslationAligner.Row(it.timeMs, it.text, it.translation)
            })
        else -> LyricsTranslationAligner.failed(LrcParser.parse(document.original), when (document.translationStatus) {
            KugouLibraryApi.TranslationStatus.NETWORK -> TranslationReason.NETWORK
            KugouLibraryApi.TranslationStatus.TIMEOUT -> TranslationReason.TIMEOUT
            KugouLibraryApi.TranslationStatus.PLATFORM_ERROR -> TranslationReason.PLATFORM_ERROR
            KugouLibraryApi.TranslationStatus.UNSUPPORTED -> TranslationReason.UNSUPPORTED_FORMAT
            else -> TranslationReason.INVALID_DATA
        })
    }

    private fun <T> normalized(result: (Result<T>) -> Unit): (Result<T>) -> Unit = { response ->
        result(response.fold({ Result.success(it) }, { error ->
            val code = when (error) {
                is LibraryPageFailure -> error.category
                is NeteaseApi.ApiException -> error.code
                is QqMusicApi.ApiException -> error.code
                is KugouApi.ApiException -> error.code
                is java.net.SocketTimeoutException -> "timeout"
                is java.io.IOException -> "network"
                is IllegalArgumentException -> "protocol"
                else -> "unavailable"
            }
            val diagnostic = (error as? KugouApi.ApiException)?.identityDiagnostic?.takeIf { it in DIAGNOSTICS }
                ?: if (error is IllegalArgumentException) "lyrics_parse" else "NONE"
            Result.failure(LyricsFailure(code, diagnostic))
        }))
    }

    private companion object {
        val DIAGNOSTICS = setOf("lyrics_search_status", "lyrics_search_rows", "lyrics_search_candidate", "lyrics_search_key",
            "lyrics_download_status", "lyrics_content_type", "lyrics_content_encoding", "lyrics_transport_limit", "lyrics_transport_json",
            "lyrics_search_json_html", "lyrics_search_json_array", "lyrics_search_json_other", "lyrics_download_json_html",
            "lyrics_download_json_array", "lyrics_download_json_other", "lyrics_search_json_empty", "lyrics_search_json_null",
            "lyrics_search_json_bom", "lyrics_search_json_compressed", "lyrics_search_json_jsonp", "lyrics_download_json_empty",
            "lyrics_download_json_null", "lyrics_download_json_bom", "lyrics_download_json_compressed", "lyrics_download_json_jsonp")
    }
    fun fetch(key: MediaKey, result: (Result<ParsedLyrics>) -> Unit) {
        val callback = normalized(result)
        when (key.platform) {
            Platform.NETEASE -> accounts.withNeteaseSession({ api, session ->
                val document = NeteaseLibraryApi(api).lyricsDocument(session, key.id)
                if (document.translationStatus == NeteaseLibraryApi.TranslationStatus.INVALID)
                    LyricsTranslationAligner.failed(LrcParser.parse(document.original), TranslationReason.INVALID_DATA)
                else LyricsTranslationAligner.parse(document.original, document.translation)
            }, callback, RequestOperation.LYRICS)
            Platform.QQ -> accounts.withQqSession({ api, session ->
                val document = QqLibraryApi(api).lyricsDocument(session, key.id)
                if (document.translationStatus == QqLibraryApi.TranslationStatus.INVALID)
                    LyricsTranslationAligner.failed(LrcParser.parse(document.original), TranslationReason.INVALID_DATA)
                else LyricsTranslationAligner.parse(document.original, document.translation)
            }, callback, RequestOperation.LYRICS)
            Platform.KUGOU -> accounts.withKugouSession({ api, session ->
                val document = KugouLibraryApi(api).lyricsDocument(session, key.id)
                alignKugou(document)
            }, callback, RequestOperation.LYRICS)
        }
    }
}
