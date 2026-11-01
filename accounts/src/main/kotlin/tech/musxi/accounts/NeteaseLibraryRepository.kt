package tech.musxi.accounts

import android.os.SystemClock
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*
import tech.musxi.core.diagnostics.RequestOperation
import tech.musxi.neteaseloginpoc.NeteaseLibraryApi
import tech.musxi.neteaseloginpoc.NeteaseApi

/** Read-only library facade. UI receives DTOs and fixed failure codes, never platform sessions. */
class NeteaseLibraryRepository(private val accounts: AccountRepository) : LibraryPageFetch {
    private val queueRevision = java.util.concurrent.atomic.AtomicLong()
    private fun summary(song: NeteaseLibraryApi.Track) = SongSummary(
        MediaKey(Platform.NETEASE, song.id), song.name, song.artists, song.album, song.durationMs,
        ArtworkPolicy.sanitize(Platform.NETEASE, song.coverUrl))
    class LibraryFailure(category: String) : LibraryPageFailure(category)
    private fun <T> safeCallback(result: (Result<T>) -> Unit): (Result<T>) -> Unit = { response ->
        result(response.fold({ Result.success(it) }, { error ->
            val code = (error as? LibraryFailure)?.category ?: (error as? NeteaseApi.ApiException)?.code
            val safe = if (code in setOf("expired", "network", "risk", "rate", "protocol", "identity", "limit", "cancelled",
                    "incomplete", "permission", "unavailable", "endpoint", "platform", "http", "timeout", "request", "account_changed")) code!! else "unclassified"
            Result.failure(LibraryFailure(safe))
        }))
    }
    override fun playlists(offset: Int, result: (Result<Page<PlaylistSummary>>) -> Unit) {
        accounts.withNeteaseSession({ api, session ->
            val page = NeteaseLibraryApi(api).playlists(session, offset, 30)
            Page(page.items.map { PlaylistSummary(MediaKey(Platform.NETEASE, it.id), it.name,
                ArtworkPolicy.sanitize(Platform.NETEASE, it.coverUrl), it.trackCount) },
                page.nextOffset.takeIf { it >= 0 }, page.total.takeIf { it >= 0 })
        }, safeCallback(result), RequestOperation.PLAYLISTS)
    }

    override fun tracks(playlist: MediaKey, offset: Int, result: (Result<Page<SongSummary>>) -> Unit) {
        require(playlist.platform == Platform.NETEASE)
        accounts.withNeteaseSession({ api, session ->
            val page = NeteaseLibraryApi(api).tracks(session, playlist.id, offset, 50)
            Page(page.items.map(::summary),
                page.nextOffset.takeIf { it >= 0 }, page.total.takeIf { it >= 0 })
        }, safeCallback(result), RequestOperation.TRACKS)
    }

    fun search(keywords: String, result: (Result<List<SongSummary>>) -> Unit) = search(keywords, result, false)
    fun search(keywords: String, result: (Result<List<SongSummary>>) -> Unit,
        playbackSearch: Boolean, isCurrent: () -> Boolean = { true }) {
        accounts.withNeteaseSession({ api, session ->
            if (!isCurrent()) throw LibraryFailure("cancelled")
            val songs = NeteaseLibraryApi(api).search(session, keywords).map(::summary)
            if (!isCurrent()) throw LibraryFailure("cancelled")
            songs
        }, safeCallback(result), RequestOperation.SEARCH, playbackSearch)
    }

    fun searchPage(keywords: String, offset: Int, result: (Result<Page<SongSummary>>) -> Unit,
        isCurrent: () -> Boolean = { true }) {
        accounts.withNeteaseSession({ api, session ->
            if (!isCurrent()) throw LibraryFailure("cancelled")
            if (offset !in 0..19_980 || offset % 30 != 0) throw LibraryFailure("limit")
            val page = NeteaseLibraryApi(api).search(session, keywords, offset, 30)
            if (!isCurrent()) throw LibraryFailure("cancelled")
            Page(page.items.map(::summary), page.nextOffset.takeIf { it >= 0 }, page.total.takeIf { it >= 0 })
        }, safeCallback(result), RequestOperation.SEARCH)
    }

    fun song(key: MediaKey, result: (Result<SongSummary>) -> Unit) = song(key, result, false)
    fun song(key: MediaKey, result: (Result<SongSummary>) -> Unit,
        playbackSearch: Boolean, isCurrent: () -> Boolean = { true }) {
        require(key.platform == Platform.NETEASE)
        accounts.withNeteaseSession({ api, session ->
            if (!isCurrent()) throw LibraryFailure("cancelled")
            val track = NeteaseLibraryApi(api).song(session, key.id)
            val song = summary(track)
            if (!isCurrent()) throw LibraryFailure("cancelled")
            song
        }, safeCallback(result), RequestOperation.SONG, playbackSearch)
    }

    fun queue(playlist: MediaKey, startSong: MediaKey, result: (Result<PlaybackQueue>) -> Unit) {
        require(playlist.platform == Platform.NETEASE && startSong.platform == Platform.NETEASE)
        val revision = queueRevision.incrementAndGet()
        val generation = accounts.states.value.getValue(Platform.NETEASE).generation
        accounts.withNeteaseSession({ api, session ->
            if (queueRevision.get() != revision) throw LibraryFailure("cancelled")
            val ids = NeteaseLibraryApi(api).trackIds(session, playlist.id)
            if (queueRevision.get() != revision || !accounts.isNeteaseGenerationConnected(generation))
                throw LibraryFailure("account_changed")
            if (ids.size > 20_000) throw LibraryFailure("limit")
            val index = ids.indexOf(startSong.id)
            if (index < 0) throw LibraryFailure("incomplete")
            PlaybackQueue(ids.map { SongSummary(MediaKey(Platform.NETEASE, it), "待加载歌曲", emptyList(), "", 0) }, index, generation)
        }, safeCallback { response -> if (queueRevision.get() == revision) result(response) }, RequestOperation.QUEUE)
    }

    fun cancelQueueLoading() { queueRevision.incrementAndGet() }

    fun queueHead(playlist: MediaKey, startSong: MediaKey, knownSongs: List<SongSummary>,
        result: (Result<PlaylistQueueHead>) -> Unit) {
        require(playlist.platform == Platform.NETEASE && startSong.platform == Platform.NETEASE)
        val revision = queueRevision.incrementAndGet()
        val generation = accounts.states.value.getValue(Platform.NETEASE).generation
        accounts.withNeteaseSession({ api, session ->
            if (queueRevision.get() != revision) throw LibraryFailure("cancelled")
            val ids = NeteaseLibraryApi(api).trackIds(session, playlist.id)
            if (queueRevision.get() != revision || !accounts.isNeteaseGenerationConnected(generation))
                throw LibraryFailure("account_changed")
            if (ids.isEmpty() || ids.size > 20_000) throw LibraryFailure("limit")
            val selectedIndex = ids.indexOf(startSong.id)
            if (selectedIndex < 0) throw LibraryFailure("incomplete")
            val validPrefix = knownSongs.size <= ids.size && knownSongs.withIndex().all { (index, song) ->
                song.key.platform == Platform.NETEASE && ids[index] == song.key.id
            }
            val pageSize = 50
            val pageStart = selectedIndex / pageSize * pageSize
            val pageEnd = minOf(pageStart + pageSize, ids.size)
            fun summaryAt(index: Int): SongSummary {
                val known = knownSongs.getOrNull(index).takeIf { validPrefix }
                return known ?: SongSummary(MediaKey(Platform.NETEASE, ids[index]), "待加载歌曲", emptyList(), "", 0)
            }
            val head = (pageStart until pageEnd).map(::summaryAt)
            val before = (0 until pageStart).map(::summaryAt)
            val after = (pageEnd until ids.size).map(::summaryAt)
            val remainder = QueueRemainder(before, after, { callback -> callback(Result.success(null)) }, {
                queueRevision.compareAndSet(revision, revision + 1)
            })
            PlaylistQueueHead(PlaybackQueue(head, selectedIndex - pageStart, generation), remainder)
        }, { response ->
            if (queueRevision.get() == revision) result(response.fold({ Result.success(it) }, { error ->
                val code = (error as? LibraryFailure)?.category ?: (error as? NeteaseApi.ApiException)?.code
                Result.failure(LibraryFailure(code?.takeIf { it in setOf("expired", "network", "risk", "rate", "protocol",
                    "identity", "limit", "cancelled", "incomplete", "permission", "unavailable", "endpoint", "platform",
                    "http", "timeout", "request", "account_changed") } ?: "unclassified"))
            }))
        }, RequestOperation.QUEUE)
    }

    /** Internal playback handoff: capability URL is transient and should not enter UI state/cache. */
    fun resolveAudio(song: MediaKey, result: (Result<AudioCapability>) -> Unit) {
        resolveAudio(song, null, result)
    }
    fun qualities(song: MediaKey, result: (Result<tech.musxi.core.playback.QualityOptions>) -> Unit) {
        require(song.platform == Platform.NETEASE)
        accounts.withNeteaseSession({ api, session ->
            val options = NeteaseLibraryApi(api).qualities(session, song.id, SystemClock.elapsedRealtime())
            tech.musxi.core.playback.QualityOptions(options.map { tech.musxi.core.playback.QualityOption(it.id, it.name) }, null)
        }, safeCallback(result), RequestOperation.OTHER)
    }
    fun resolveAudio(song: MediaKey, qualityId: String?, result: (Result<AudioCapability>) -> Unit) {
        require(song.platform == Platform.NETEASE)
        val generation = accounts.states.value.getValue(Platform.NETEASE).generation
        accounts.withNeteaseSession({ api, session ->
            val library = NeteaseLibraryApi(api)
            val track = library.song(session, song.id)
            val audio = resolvePreferredQuality("netease", accounts.preferredQuality(Platform.NETEASE), qualityId,
                { (it as? NeteaseApi.ApiException)?.code }) { id ->
                library.audio(session, song.id, SystemClock.elapsedRealtime(), id)
            }
            if (qualityId != null && audio.qualityId != qualityId) throw LibraryFailure("unavailable")
            AudioCapability(song, summary(track), audio.url, audio.expiresAtElapsedMs, audio.qualityId) {
                accounts.isNeteaseGenerationConnected(generation)
            }
        }, safeCallback(result), RequestOperation.AUDIO)
    }

    class AudioCapability internal constructor(val song: MediaKey, val metadata: SongSummary, private val url: String,
        private val expiresAt: Long, val qualityId: String, private val isCurrent: () -> Boolean) {
        fun deliverToPlayer(deliver: (String) -> Unit) {
            if (!isCurrent()) throw LibraryFailure("account_changed")
            if (SystemClock.elapsedRealtime() >= expiresAt) throw LibraryFailure("source_expired")
            deliver(url)
        }
        override fun toString() = "AudioCapability(redacted)"
    }
}
