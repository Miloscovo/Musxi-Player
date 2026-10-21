package tech.musxi.accounts

import android.os.SystemClock
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*
import tech.musxi.neteaseloginpoc.NeteaseLibraryApi
import tech.musxi.neteaseloginpoc.NeteaseApi

/** Read-only library facade. UI receives DTOs and fixed failure codes, never platform sessions. */
class NeteaseLibraryRepository(private val accounts: AccountRepository) : LibraryPageFetch {
    private fun summary(song: NeteaseLibraryApi.Track) = SongSummary(
        MediaKey(Platform.NETEASE, song.id), song.name, song.artists, song.album, song.durationMs,
        ArtworkPolicy.sanitize(Platform.NETEASE, song.coverUrl))
    class LibraryFailure(category: String) : LibraryPageFailure(category)
    private fun <T> safeCallback(result: (Result<T>) -> Unit): (Result<T>) -> Unit = { response ->
        result(response.fold({ Result.success(it) }, { error ->
            val code = (error as? LibraryFailure)?.category ?: (error as? NeteaseApi.ApiException)?.code
            val safe = if (code in setOf("expired", "network", "risk", "rate", "protocol", "identity", "limit",
                    "incomplete", "permission", "unavailable", "endpoint", "platform", "http", "timeout", "request")) code!! else "unclassified"
            Result.failure(LibraryFailure(safe))
        }))
    }
    override fun playlists(offset: Int, result: (Result<Page<PlaylistSummary>>) -> Unit) {
        accounts.withNeteaseSession({ api, session ->
            val page = NeteaseLibraryApi(api).playlists(session, offset, 30)
            Page(page.items.map { PlaylistSummary(MediaKey(Platform.NETEASE, it.id), it.name,
                ArtworkPolicy.sanitize(Platform.NETEASE, it.coverUrl), it.trackCount) },
                page.nextOffset.takeIf { it >= 0 }, page.total.takeIf { it >= 0 })
        }, safeCallback(result))
    }

    override fun tracks(playlist: MediaKey, offset: Int, result: (Result<Page<SongSummary>>) -> Unit) {
        require(playlist.platform == Platform.NETEASE)
        accounts.withNeteaseSession({ api, session ->
            val page = NeteaseLibraryApi(api).tracks(session, playlist.id, offset, 50)
            Page(page.items.map(::summary),
                page.nextOffset.takeIf { it >= 0 }, page.total.takeIf { it >= 0 })
        }, safeCallback(result))
    }

    fun search(keywords: String, result: (Result<List<SongSummary>>) -> Unit) {
        accounts.withNeteaseSession({ api, session ->
            NeteaseLibraryApi(api).search(session, keywords).map(::summary)
        }, safeCallback(result))
    }

    fun searchPage(keywords: String, offset: Int, result: (Result<Page<SongSummary>>) -> Unit,
        isCurrent: () -> Boolean = { true }) {
        accounts.withNeteaseSession({ api, session ->
            if (!isCurrent()) throw LibraryFailure("cancelled")
            if (offset !in 0..19_980 || offset % 30 != 0) throw LibraryFailure("limit")
            val page = NeteaseLibraryApi(api).search(session, keywords, offset, 30)
            if (!isCurrent()) throw LibraryFailure("cancelled")
            Page(page.items.map(::summary), page.nextOffset.takeIf { it >= 0 }, page.total.takeIf { it >= 0 })
        }, safeCallback(result))
    }

    fun song(key: MediaKey, result: (Result<SongSummary>) -> Unit) {
        require(key.platform == Platform.NETEASE)
        accounts.withNeteaseSession({ api, session ->
            val track = NeteaseLibraryApi(api).song(session, key.id)
            summary(track)
        }, safeCallback(result))
    }

    fun queue(playlist: MediaKey, startSong: MediaKey, result: (Result<PlaybackQueue>) -> Unit) {
        require(playlist.platform == Platform.NETEASE && startSong.platform == Platform.NETEASE)
        val generation = accounts.states.value.getValue(Platform.NETEASE).generation
        accounts.withNeteaseSession({ api, session ->
            val ids = NeteaseLibraryApi(api).trackIds(session, playlist.id)
            val index = ids.indexOf(startSong.id)
            if (index < 0) throw LibraryFailure("incomplete")
            PlaybackQueue(ids.map { SongSummary(MediaKey(Platform.NETEASE, it), "待加载歌曲", emptyList(), "", 0) }, index, generation)
        }, safeCallback(result))
    }

    /** Internal playback handoff: capability URL is transient and should not enter UI state/cache. */
    fun resolveAudio(song: MediaKey, result: (Result<AudioCapability>) -> Unit) {
        require(song.platform == Platform.NETEASE)
        val generation = accounts.states.value.getValue(Platform.NETEASE).generation
        accounts.withNeteaseSession({ api, session ->
            val library = NeteaseLibraryApi(api)
            val track = library.song(session, song.id)
            val audio = library.audio(session, song.id, SystemClock.elapsedRealtime())
            AudioCapability(song, summary(track), audio.url, audio.expiresAtElapsedMs) {
                accounts.isNeteaseGenerationConnected(generation)
            }
        }, safeCallback(result))
    }

    class AudioCapability internal constructor(val song: MediaKey, val metadata: SongSummary, private val url: String,
        private val expiresAt: Long, private val isCurrent: () -> Boolean) {
        fun deliverToPlayer(deliver: (String) -> Unit) {
            if (!isCurrent()) throw LibraryFailure("account_changed")
            if (SystemClock.elapsedRealtime() >= expiresAt) throw LibraryFailure("source_expired")
            deliver(url)
        }
        override fun toString() = "AudioCapability(redacted)"
    }
}
