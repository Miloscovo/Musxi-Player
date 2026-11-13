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
import tech.musxi.core.lyrics.LyricsOrigin
import tech.musxi.core.lyrics.LyricsWordTimeline
import tech.musxi.core.lyrics.LyricsCacheHit
import tech.musxi.neteaseloginpoc.NeteaseApi
import tech.musxi.neteaseloginpoc.NeteaseLibraryApi
import tech.musxi.qqloginpoc.QqMusicApi
import tech.musxi.qqloginpoc.QqLibraryApi
import tech.musxi.qqloginpoc.QrcLyricsDecoder
import tech.musxi.loginpoc.KugouApi
import tech.musxi.loginpoc.KugouLibraryApi

/** Requests and bounded parsing share the account worker; session material stays internal. */
class LyricsRepository(private val accounts: AccountRepository) {
    class LyricsFailure internal constructor(category: String, val diagnostic: String) : LibraryPageFailure(category)
    private class Stage(val key: MediaKey, val owner: LibraryCacheIdentity, val raw: String,
        val parsed: ParsedLyrics, val document: Any) {
        var initial = true
        var displayed = parsed
        override fun toString() = "LyricsStage(redacted)"
    }
    // Main-thread bounded contexts; private platform documents/handles never enter UI or disk.
    private val stages = java.util.IdentityHashMap<ParsedLyrics, Stage>()
    private val order = java.util.ArrayDeque<ParsedLyrics>()
    private data class Display(val originalKey: MediaKey, val key: MediaKey, val source: ParsedLyrics,
        val originalOwner: LibraryCacheIdentity, val sourceOwner: LibraryCacheIdentity)
    private val displays = java.util.IdentityHashMap<ParsedLyrics, Display>()
    private var cacheRevision = 0L

    /** Main-thread cache maintenance; never removes manually saved bindings. */
    fun clearCache() {
        ++cacheRevision
        displays.clear()
        stages.clear()
        order.clear()
    }

    fun accountChanged() {
        displays.entries.removeAll { (_, display) ->
            !display.originalOwner.sameAttempt(accounts.playbackIdentity(display.originalKey.platform)) ||
                !display.sourceOwner.sameAttempt(accounts.playbackIdentity(display.key.platform))
        }
        order.removeAll { parsed -> stages[parsed]?.let { cached ->
            val identity = accounts.playbackIdentity(cached.key.platform)
            (!cached.owner.sameAttempt(identity) || identity?.verified != true).also { if (it) stages.remove(parsed) }
        } ?: true }
    }

    fun releaseOriginal(parsed: ParsedLyrics) {
        displays.remove(parsed)?.let { releaseOriginal(it.source) }
        stages.remove(parsed)
        order.removeAll { it === parsed }
    }

    fun fetchOriginal(key: MediaKey, result: (Result<ParsedLyrics>) -> Unit) = fetchOriginal(key, { true }, result)

    fun fetchOriginal(key: MediaKey, isCurrent: () -> Boolean, result: (Result<ParsedLyrics>) -> Unit) =
        fetchForOriginal(key, key, isCurrent, result)

    /** Bindings belong to the original queue item; automatic lyrics still follow the actual source. */
    fun fetchForOriginal(original: MediaKey, automatic: MediaKey, isCurrent: () -> Boolean,
        result: (Result<ParsedLyrics>) -> Unit) {
        val expectedCacheRevision = cacheRevision
        val owner = accounts.playbackIdentity(original.platform)
            ?: return result(Result.failure(LyricsFailure("account_changed", "NONE")))
        accounts.lyricsBinding(original, owner) binding@{ response ->
            if (expectedCacheRevision != cacheRevision || !isCurrent() || owner != accounts.playbackIdentity(original.platform)) return@binding
            val saved = response.getOrNull()
            if (response.isFailure) return@binding result(Result.failure(response.exceptionOrNull()!!))
            val key = saved?.candidateKey ?: automatic
            val sourceOwner = accounts.playbackIdentity(key.platform)
                ?: return@binding result(Result.failure(LyricsFailure("account_changed", "NONE")))
            fun valid() = expectedCacheRevision == cacheRevision && isCurrent() && owner == accounts.playbackIdentity(original.platform) &&
                sourceOwner == accounts.playbackIdentity(key.platform)
            fetchCached(key, ::valid) fetched@{ fetched ->
                if (!valid()) return@fetched
                result(fetched.map { parsed ->
                    val displayed = parsed.copy(origin = if (saved == null) LyricsOrigin.AUTO else LyricsOrigin.MATCHED,
                        offsetMs = saved?.offsetMs ?: 0)
                    displays[displayed] = Display(original, key, parsed, owner, sourceOwner)
                    while (displays.size > 16) displays.remove(displays.keys.first())
                    displayed
                })
            }
        }
    }

    /** Search previews never pass through the disk-cache or binding APIs. */
    fun preview(key: MediaKey, isCurrent: () -> Boolean, result: (Result<ParsedLyrics>) -> Unit) {
        val owner = accounts.playbackIdentity(key.platform)
        fetch(key) { response ->
            if (isCurrent() && owner?.verified == true && owner == accounts.playbackIdentity(key.platform)) result(response)
        }
    }

    private fun fetchCached(key: MediaKey, isCurrent: () -> Boolean, result: (Result<ParsedLyrics>) -> Unit) {
        val owner = accounts.playbackIdentity(key.platform)
            ?: return result(Result.failure(LyricsFailure("account_changed", "NONE")))
        accounts.lyricsCache(key, owner) cached@{ response ->
            if (!isCurrent()) return@cached
            val cached = response.getOrNull()
            if (!owner.verified) {
                result(if (cached != null) Result.success(cached.lyrics)
                    else Result.failure(LyricsFailure("account_changed", "NONE")))
                return@cached
            }
            if (cached != null && !cached.stale && !NeteaseWordLyrics.needsRefresh(key.platform, cached.lyrics))
                return@cached result(Result.success(cached.lyrics))
            fetchNetworkOriginal(key, isCurrent) network@{ fresh ->
                if (!isCurrent()) return@network
                val parsed = fresh.getOrNull()
                if (parsed == null || cached != null && key.platform == Platform.NETEASE &&
                    cached.lyrics.timingRevision >= NeteaseWordLyrics.TIMING_REVISION &&
                    parsed.timingRevision < NeteaseWordLyrics.TIMING_REVISION) {
                    result(if (cached != null) Result.success(cached.lyrics) else fresh)
                } else {
                    accounts.lyricsCache(key, owner, parsed) { }
                    result(Result.success(parsed))
                }
            }
        }
    }

    private fun fetchNetworkOriginal(key: MediaKey, isCurrent: () -> Boolean, result: (Result<ParsedLyrics>) -> Unit) {
        val expectedCacheRevision = cacheRevision
        val owner = accounts.playbackIdentity(key.platform)?.takeIf { it.verified }
            ?: return result(Result.failure(LyricsFailure("account_changed", "NONE")))
        fun stage(raw: String, document: Any): Stage {
            val parsed = LrcParser.parse(raw)
            val original = parsed.copy(translation = LyricsTranslation(lookup = TranslationLookup(
                if (parsed.lines.isNotEmpty()) TranslationLookupStatus.IDLE else TranslationLookupStatus.UNSUPPORTED,
                if (parsed.lines.isNotEmpty()) null else if (parsed.plainText.isBlank())
                    TranslationReason.NO_LYRICS else TranslationReason.NOT_SYNCED)))
            val timed = if (document is NeteaseLibraryApi.LyricsDocument) NeteaseWordLyrics.attach(document, original) else original
            return Stage(key, owner, raw, timed, document)
        }
        val callback: (Result<Stage>) -> Unit = callback@{ response ->
            if (expectedCacheRevision != cacheRevision || !isCurrent()) return@callback
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
                val doc = NeteaseLibraryApi(api).lyricsTimedDocument(session, key.id)
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
        val expectedCacheRevision = cacheRevision
        val display = displays[original]
        if (display != null) {
            if (display.originalOwner != accounts.playbackIdentity(display.originalKey.platform) ||
                display.sourceOwner != accounts.playbackIdentity(display.key.platform)) {
                result(Result.failure(LyricsFailure("account_changed", "NONE"))); return
            }
            fetchTranslation(display.key, display.source, refresh) { response ->
                if (expectedCacheRevision == cacheRevision && display.originalOwner == accounts.playbackIdentity(display.originalKey.platform) &&
                    display.sourceOwner == accounts.playbackIdentity(display.key.platform))
                    result(response.map { it.copy(origin = original.origin, offsetMs = original.offsetMs) })
            }
            return
        }
        val stage = stages[original]
        val owner = accounts.playbackIdentity(key.platform)
        if (owner != null && !owner.verified && stage == null) {
            result(Result.success(original)); return
        }
        if (stage == null && owner?.verified == true) {
            if (!refresh && original.translation.lookup.status != TranslationLookupStatus.IDLE) {
                result(Result.success(original)); return
            }
            fetchWithPrevious(key, original) { response ->
                if (expectedCacheRevision != cacheRevision || owner != accounts.playbackIdentity(key.platform)) return@fetchWithPrevious
                val retained = response.map { LyricsWordTimeline.retainOnFailure(original, it) }
                retained.getOrNull()?.let { accounts.lyricsCache(key, owner, it) { } }
                result(retained)
            }
            return
        }
        if (stage == null || stage.key != key || !stage.owner.sameAttempt(owner) || owner?.verified != true) {
            result(Result.failure(LyricsFailure(if (owner?.verified == true) "unavailable" else "account_changed", "NONE")))
            return
        }
        val useInitial = stage.initial && !refresh
        stage.initial = false
        fun align(raw: String, translated: () -> ParsedLyrics): ParsedLyrics =
            if (raw == stage.raw) translated() else
                LyricsTranslationAligner.failed(original, TranslationReason.VERSION_MISMATCH)
        val callback = normalized<ParsedLyrics> { response ->
            if (expectedCacheRevision != cacheRevision || !stage.owner.sameAttempt(accounts.playbackIdentity(key.platform))) return@normalized
            val retained = response.map { LyricsWordTimeline.retainOnFailure(stage.displayed, it) }
            retained.getOrNull()?.let { stage.displayed = it; accounts.lyricsCache(key, stage.owner, it) { } }
            result(retained)
        }
        when (key.platform) {
            Platform.NETEASE -> accounts.withNeteaseSession({ api, session ->
                val doc = if (useInitial) stage.document as NeteaseLibraryApi.LyricsDocument
                    else NeteaseLibraryApi(api).lyricsTimedDocument(session, key.id)
                align(doc.original) { NeteaseWordLyrics.parse(doc, stage.displayed) }
            }, callback, RequestOperation.LYRICS)
            Platform.QQ -> accounts.withQqSession({ api, session ->
                val library = QqLibraryApi(api)
                val doc = if (useInitial) stage.document as QqLibraryApi.LyricsDocument
                    else library.lyricsDocument(session, key.id)
                align(doc.original) { alignQq(doc, library.lyricsWords(session, key.id), stage.displayed) }
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
                KrcTranslationAligner.Row(it.timeMs, it.text, it.translation, it.words.map { word ->
                    tech.musxi.core.lyrics.LyricWord(word.textStart, word.textEnd, word.startTimeMs, word.endTimeMs)
                })
            })
        else -> LyricsTranslationAligner.failed(LrcParser.parse(document.original), when (document.translationStatus) {
            KugouLibraryApi.TranslationStatus.NETWORK -> TranslationReason.NETWORK
            KugouLibraryApi.TranslationStatus.TIMEOUT -> TranslationReason.TIMEOUT
            KugouLibraryApi.TranslationStatus.PLATFORM_ERROR -> TranslationReason.PLATFORM_ERROR
            KugouLibraryApi.TranslationStatus.UNSUPPORTED -> TranslationReason.UNSUPPORTED_FORMAT
            else -> TranslationReason.INVALID_DATA
        })
    }

    private fun alignQq(document: QqLibraryApi.LyricsDocument, words: QrcLyricsDecoder.Document,
        previous: ParsedLyrics? = null): ParsedLyrics {
        val translated = if (document.translationStatus == QqLibraryApi.TranslationStatus.INVALID)
            LyricsTranslationAligner.failed(LrcParser.parse(document.original), TranslationReason.INVALID_DATA)
        else LyricsTranslationAligner.parse(document.original, document.translation)
        return QqWordLyrics.attach(document.original, translated, words, previous)
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
        fetchWithPrevious(key, null, result)
    }

    private fun fetchWithPrevious(key: MediaKey, previous: ParsedLyrics?, result: (Result<ParsedLyrics>) -> Unit) {
        val callback = normalized(result)
        when (key.platform) {
            Platform.NETEASE -> accounts.withNeteaseSession({ api, session ->
                val document = NeteaseLibraryApi(api).lyricsTimedDocument(session, key.id)
                NeteaseWordLyrics.parse(document, previous)
            }, callback, RequestOperation.LYRICS)
            Platform.QQ -> accounts.withQqSession({ api, session ->
                val library = QqLibraryApi(api)
                val document = library.lyricsDocument(session, key.id)
                alignQq(document, library.lyricsWords(session, key.id), previous)
            }, callback, RequestOperation.LYRICS)
            Platform.KUGOU -> accounts.withKugouSession({ api, session ->
                val document = KugouLibraryApi(api).lyricsDocument(session, key.id)
                alignKugou(document)
            }, callback, RequestOperation.LYRICS)
        }
    }
}
