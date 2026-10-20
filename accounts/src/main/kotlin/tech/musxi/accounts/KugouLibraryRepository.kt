package tech.musxi.accounts

import android.os.SystemClock
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*
import tech.musxi.loginpoc.KugouApi
import tech.musxi.loginpoc.KugouLibraryApi

/** Official account-bound requests. No session or raw platform response reaches a page. */
class KugouLibraryRepository(private val accounts: AccountRepository) : LibraryPageFetch {
    private val queueRevision = java.util.concurrent.atomic.AtomicLong()
    private val metadata = linkedMapOf<MediaKey, SongSummary>()
    private var metadataGeneration = -1L
    private fun summary(song: KugouLibraryApi.Song) =
        SongSummary(MediaKey(Platform.KUGOU, song.id), song.name, song.artists, song.album, song.durationMs)
    private fun remember(songs: List<SongSummary>, generation: Long): List<SongSummary> {
        if (!accounts.isKugouGenerationConnected(generation)) throw LibraryFailure("account_changed")
        synchronized(metadata) {
            if (metadataGeneration != generation) {
                metadata.clear()
                metadataGeneration = generation
            }
            songs.forEach { metadata[it.key] = it }
            while (metadata.size > 256) metadata.remove(metadata.keys.first())
        }
        return songs
    }
    fun cancelQueueLoading() { queueRevision.incrementAndGet() }
    class LibraryFailure(category: String, val diagnostic: String? = null) : LibraryPageFailure(category)
    private fun <T> safe(result: (Result<T>) -> Unit): (Result<T>) -> Unit = { response ->
        result(response.fold({ Result.success(it) }, {
            val code = (it as? LibraryFailure)?.category ?: (it as? KugouApi.ApiException)?.code
            val detail = (it as? KugouApi.ApiException)?.identityDiagnostic?.takeIf { fixed ->
                fixed.matches(Regex("transport_(connect|read)_(ssl|dns|connect|eof|io)")) ||
                    fixed.matches(Regex("cdn:(ssl_peer|ssl|dns|timeout|io)(,(ssl_peer|ssl|dns|timeout|io)){0,4}"))
            }
            Result.failure(LibraryFailure(if (code in setOf("expired", "network", "risk", "rate", "protocol", "identity", "limit",
                    "incomplete", "permission", "unavailable", "endpoint", "platform", "http", "timeout", "source_expired", "account_changed")) code!! else "unclassified", detail))
        }))
    }
    override fun playlists(offset: Int, result: (Result<Page<PlaylistSummary>>) -> Unit) {
        accounts.withKugouSession({ api, session ->
            val page = KugouLibraryApi(api).playlists(session, offset, 30)
            Page(page.items.map { PlaylistSummary(MediaKey(Platform.KUGOU, it.id), it.name, it.coverUrl, it.trackCount) },
                page.nextOffset.takeIf { it >= 0 }, page.total.takeIf { it >= 0 })
        }, safe(result))
    }
    override fun tracks(playlist: MediaKey, offset: Int, result: (Result<Page<SongSummary>>) -> Unit) {
        require(playlist.platform == Platform.KUGOU)
        val generation = accounts.states.value.getValue(Platform.KUGOU).generation
        accounts.withKugouSession({ api, session ->
            val page = KugouLibraryApi(api).tracks(session, playlist.id, offset, 30)
            Page(remember(page.items.map(::summary), generation),
                page.nextOffset.takeIf { it >= 0 }, page.total.takeIf { it >= 0 })
        }, safe(result))
    }
    fun search(keywords: String, result: (Result<List<SongSummary>>) -> Unit) {
        val generation = accounts.states.value.getValue(Platform.KUGOU).generation
        accounts.withKugouSession({ api, session ->
            remember(KugouLibraryApi(api).search(session, keywords).map(::summary), generation)
        }, safe(result))
    }
    /** The pinned vendor has no general song-detail module. Reuse known metadata only. */
    fun song(key: MediaKey, result: (Result<SongSummary>) -> Unit) {
        val generation = accounts.states.value.getValue(Platform.KUGOU).generation
        accounts.withKugouSession({ _, _ ->
            if (key.platform != Platform.KUGOU) throw LibraryFailure("identity")
            if (!accounts.isKugouGenerationConnected(generation)) throw LibraryFailure("account_changed")
            synchronized(metadata) {
                if (metadataGeneration != generation) throw LibraryFailure("unavailable")
                metadata[key] ?: throw LibraryFailure("unavailable")
            }
        }, safe(result))
    }
    fun queue(playlist: MediaKey, startSong: MediaKey, result: (Result<PlaybackQueue>) -> Unit) {
        require(playlist.platform == Platform.KUGOU && startSong.platform == Platform.KUGOU)
        val revision = queueRevision.incrementAndGet()
        val generation = accounts.states.value.getValue(Platform.KUGOU).generation
        accounts.withKugouSession({ api, session ->
            val library = KugouLibraryApi(api)
            val deadline = SystemClock.elapsedRealtime() + 60_000
            val songs = mutableListOf<SongSummary>()
            var offset = 0
            var expectedTotal: Int? = null
            while (true) {
                if (queueRevision.get() != revision || !accounts.isKugouGenerationConnected(generation)) throw LibraryFailure("account_changed")
                if (SystemClock.elapsedRealtime() >= deadline) throw LibraryFailure("timeout")
                val page = library.tracks(session, playlist.id, offset, 100)
                if (page.total >= 0) {
                    if (expectedTotal != null && expectedTotal != page.total) throw LibraryFailure("incomplete")
                    expectedTotal = page.total
                    if (page.total > 20_000) throw LibraryFailure("limit")
                }
                songs += page.items.map(::summary)
                if (songs.size > 20_000) throw LibraryFailure("limit")
                if (page.nextOffset < 0) break
                if (page.items.isEmpty() || page.nextOffset != offset + 100) throw LibraryFailure("incomplete")
                offset = page.nextOffset
            }
            if (expectedTotal != null && songs.size != expectedTotal) throw LibraryFailure("incomplete")
            if (SystemClock.elapsedRealtime() >= deadline) throw LibraryFailure("timeout")
            if (queueRevision.get() != revision) throw LibraryFailure("account_changed")
            val index = songs.indexOfFirst { it.key == startSong }
            if (index < 0) throw LibraryFailure("incomplete")
            remember(songs, generation)
            PlaybackQueue(songs, index, generation)
        }, safe(result))
    }
    fun resolveAudio(song: MediaKey, result: (Result<AudioCapability>) -> Unit) {
        require(song.platform == Platform.KUGOU)
        val generation = accounts.states.value.getValue(Platform.KUGOU).generation
        accounts.withKugouSession({ api, session ->
            val audio = KugouLibraryApi(api).audio(session, song.id, SystemClock.elapsedRealtime())
            AudioCapability(audio.url, audio.expiresAtElapsedMs) { accounts.isKugouGenerationConnected(generation) }
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
