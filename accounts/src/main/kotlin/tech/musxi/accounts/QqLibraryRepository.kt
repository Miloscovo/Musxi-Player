package tech.musxi.accounts

import android.os.SystemClock
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*
import tech.musxi.qqloginpoc.QqMusicApi
import tech.musxi.qqloginpoc.QqLibraryApi

/** Official account-bound requests. No session or raw platform response reaches a page. */
class QqLibraryRepository(private val accounts: AccountRepository) : LibraryPageFetch {
    private val queueRevision = java.util.concurrent.atomic.AtomicLong()
    fun cancelQueueLoading() { queueRevision.incrementAndGet() }
    class LibraryFailure(category: String, val diagnostic: String? = null) : LibraryPageFailure(category)
    private fun <T> safe(result: (Result<T>) -> Unit): (Result<T>) -> Unit = { response ->
        result(response.fold({ Result.success(it) }, {
            val code = (it as? LibraryFailure)?.category ?: (it as? QqMusicApi.ApiException)?.code
            val detail = (it as? QqMusicApi.ApiException)?.diagnostic?.takeIf { fixed -> fixed in setOf(
                "vkey_denied_104003", "vkey_denied_104004", "vkey_denied_104013", "vkey_denied_other",
                "filename_mismatch", "encrypted_audio", "preview_audio", "purl_path_mismatch") }
            Result.failure(LibraryFailure(if (code in setOf("expired", "network", "risk", "rate", "protocol", "identity", "limit",
                    "incomplete", "permission", "unavailable", "endpoint", "platform", "http", "timeout", "source_expired",
                    "signature", "request", "account_changed")) code!! else "unclassified", detail))
        }))
    }
    override fun playlists(offset: Int, result: (Result<Page<PlaylistSummary>>) -> Unit) {
        val generation = accounts.states.value.getValue(Platform.QQ).generation
        accounts.withQqSession({ api, session ->
            val page = QqLibraryApi(api) { accounts.isQqGenerationConnected(generation) }.playlists(session, offset, 30)
            Page(page.items.map { PlaylistSummary(MediaKey(Platform.QQ, it.id), it.name, null, it.trackCount) },
                page.nextOffset.takeIf { it >= 0 }, page.total.takeIf { it >= 0 })
        }, safe(result))
    }
    override fun tracks(playlist: MediaKey, offset: Int, result: (Result<Page<SongSummary>>) -> Unit) {
        require(playlist.platform == Platform.QQ)
        val generation = accounts.states.value.getValue(Platform.QQ).generation
        accounts.withQqSession({ api, session ->
            val page = QqLibraryApi(api) { accounts.isQqGenerationConnected(generation) }.tracks(session, playlist.id, offset, 30)
            Page(page.items.map { SongSummary(MediaKey(Platform.QQ, it.id), it.name, it.artists, it.album, it.durationMs) },
                page.nextOffset.takeIf { it >= 0 }, page.total.takeIf { it >= 0 })
        }, safe(result))
    }
    fun search(keywords: String, result: (Result<List<SongSummary>>) -> Unit) {
        val generation = accounts.states.value.getValue(Platform.QQ).generation
        accounts.withQqSession({ api, session ->
            QqLibraryApi(api) { accounts.isQqGenerationConnected(generation) }.search(session, keywords)
                .take(10)
                .map { SongSummary(MediaKey(Platform.QQ, it.id), it.name, it.artists, it.album, it.durationMs) }
        }, safe(result))
    }
    fun song(key: MediaKey, result: (Result<SongSummary>) -> Unit) {
        require(key.platform == Platform.QQ)
        val generation = accounts.states.value.getValue(Platform.QQ).generation
        accounts.withQqSession({ api, session ->
            val item = QqLibraryApi(api) { accounts.isQqGenerationConnected(generation) }.song(session, key.id)
            SongSummary(MediaKey(Platform.QQ, item.id), item.name, item.artists, item.album, item.durationMs)
        }, safe(result))
    }
    fun queue(playlist: MediaKey, startSong: MediaKey, result: (Result<PlaybackQueue>) -> Unit) {
        require(playlist.platform == Platform.QQ && startSong.platform == Platform.QQ)
        val revision = queueRevision.incrementAndGet()
        val generation = accounts.states.value.getValue(Platform.QQ).generation
        accounts.withQqSession({ api, session ->
            val library = QqLibraryApi(api) { queueRevision.get() == revision && accounts.isQqGenerationConnected(generation) }
            val deadline = SystemClock.elapsedRealtime() + 60_000
            val songs = mutableListOf<SongSummary>()
            var offset = 0
            var expectedTotal: Int? = null
            while (true) {
                if (queueRevision.get() != revision || !accounts.isQqGenerationConnected(generation)) throw LibraryFailure("account_changed")
                if (SystemClock.elapsedRealtime() >= deadline) throw LibraryFailure("timeout")
                val page = library.tracks(session, playlist.id, offset, 100)
                if (page.total >= 0) {
                    if (expectedTotal != null && expectedTotal != page.total) throw LibraryFailure("incomplete")
                    expectedTotal = page.total
                    if (page.total > 20_000) throw LibraryFailure("limit")
                }
                songs += page.items.map { SongSummary(MediaKey(Platform.QQ, it.id), it.name, it.artists, it.album, it.durationMs) }
                if (songs.size > 20_000) throw LibraryFailure("limit")
                if (page.nextOffset < 0) break
                if (page.items.isEmpty() || page.nextOffset != offset + page.items.size) throw LibraryFailure("incomplete")
                offset = page.nextOffset
            }
            if (expectedTotal != null && songs.size != expectedTotal) throw LibraryFailure("incomplete")
            if (SystemClock.elapsedRealtime() >= deadline) throw LibraryFailure("timeout")
            if (queueRevision.get() != revision) throw LibraryFailure("account_changed")
            val index = songs.indexOfFirst { it.key == startSong }
            if (index < 0) throw LibraryFailure("incomplete")
            PlaybackQueue(songs, index, generation)
        }, safe(result))
    }
    fun resolveAudio(song: MediaKey, result: (Result<AudioCapability>) -> Unit) {
        require(song.platform == Platform.QQ)
        val generation = accounts.states.value.getValue(Platform.QQ).generation
        accounts.withQqSession({ api, session ->
            val audio = QqLibraryApi(api) { accounts.isQqGenerationConnected(generation) }.audio(session, song.id, SystemClock.elapsedRealtime())
            AudioCapability(audio.url, audio.expiresAtElapsedMs) { accounts.isQqGenerationConnected(generation) }
        }, safe(result))
    }
    class AudioCapability internal constructor(private val url: String, private val expiresAt: Long, private val current: () -> Boolean) {
        fun deliverToPlayer(deliver: (String) -> Unit) {
            if (!current()) throw LibraryFailure("account_changed")
            if (SystemClock.elapsedRealtime() >= expiresAt) throw LibraryFailure("source_expired")
            deliver(url)
        }
        override fun toString() = "AudioCapability(redacted)"
    }
}
