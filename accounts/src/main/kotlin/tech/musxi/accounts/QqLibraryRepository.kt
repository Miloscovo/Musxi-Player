package tech.musxi.accounts

import android.os.SystemClock
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*
import tech.musxi.core.diagnostics.RequestOperation
import tech.musxi.qqloginpoc.QqMusicApi
import tech.musxi.qqloginpoc.QqLibraryApi

/** Official account-bound requests. No session or raw platform response reaches a page. */
class QqLibraryRepository(private val accounts: AccountRepository) : LibraryPageFetch {
    private fun summary(song: QqLibraryApi.Song) = SongSummary(
        MediaKey(Platform.QQ, song.id), song.name, song.artists, song.album, song.durationMs,
        ArtworkPolicy.sanitize(Platform.QQ, song.coverUrl), song.artistIds, song.albumId)
    private val queueRevision = java.util.concurrent.atomic.AtomicLong()
    fun cancelQueueLoading() { queueRevision.incrementAndGet() }
    class LibraryFailure(category: String, val diagnostic: String? = null) : LibraryPageFailure(category)
    private fun <T> safe(result: (Result<T>) -> Unit): (Result<T>) -> Unit = { response ->
        result(response.fold({ Result.success(it) }, {
            val code = (it as? LibraryFailure)?.category ?: (it as? QqMusicApi.ApiException)?.code
            val detail = (it as? QqMusicApi.ApiException)?.diagnostic?.takeIf { fixed -> fixed in setOf(
                "vkey_denied_104003", "vkey_denied_104004", "vkey_denied_104013", "vkey_denied_other",
                "filename_mismatch", "encrypted_audio", "preview_audio", "purl_path_mismatch") || fixed.matches(Regex("cgi_code_-?[0-9]{1,10}")) }
            Result.failure(LibraryFailure(if (code in setOf("expired", "network", "risk", "rate", "protocol", "identity", "limit", "cancelled",
                    "incomplete", "permission", "membership", "copyright", "preview", "unavailable", "endpoint", "platform", "http", "timeout", "source_expired",
                    "signature", "request", "account_changed")) code!! else "unclassified", detail))
        }))
    }
    override fun playlists(offset: Int, result: (Result<Page<PlaylistSummary>>) -> Unit) {
        val generation = accounts.states.value.getValue(Platform.QQ).generation
        accounts.withQqSession({ api, session ->
            val page = QqLibraryApi(api) { accounts.isQqGenerationConnected(generation) }.playlists(session, offset, 30)
            Page(page.items.map {
                val kind = when (it.kind) {
                    QqLibraryApi.Playlist.Kind.LIKED -> PlaylistKind.LIKED
                    QqLibraryApi.Playlist.Kind.CREATED -> PlaylistKind.CREATED
                    QqLibraryApi.Playlist.Kind.COLLECTED -> PlaylistKind.COLLECTED
                    QqLibraryApi.Playlist.Kind.OTHER -> PlaylistKind.OTHER
                }
                PlaylistSummary(MediaKey(Platform.QQ, it.id), it.name,
                    ArtworkPolicy.sanitize(Platform.QQ, it.coverUrl), it.trackCount, kind)
            },
                page.nextOffset.takeIf { it >= 0 }, page.total.takeIf { it >= 0 })
        }, safe(result), RequestOperation.PLAYLISTS)
    }
    override fun tracks(playlist: MediaKey, offset: Int, result: (Result<Page<SongSummary>>) -> Unit) {
        require(playlist.platform == Platform.QQ)
        val generation = accounts.states.value.getValue(Platform.QQ).generation
        accounts.withQqSession({ api, session ->
            val page = QqLibraryApi(api) { accounts.isQqGenerationConnected(generation) }.tracks(session, playlist.id, offset, 30)
            Page(page.items.map(::summary),
                page.nextOffset.takeIf { it >= 0 }, page.total.takeIf { it >= 0 })
        }, safe(result), RequestOperation.TRACKS)
    }
    fun artistTracks(key: MediaKey, offset: Int = 0, result: (Result<Page<SongSummary>>) -> Unit) {
        require(key.platform == Platform.QQ)
        val generation = accounts.states.value.getValue(Platform.QQ).generation
        accounts.withQqSession({ api, session ->
            val page = QqLibraryApi(api) { accounts.isQqGenerationConnected(generation) }
                .artistTracks(session, key.id, offset, 30)
            Page(page.items.map(::summary),
                page.nextOffset.takeIf { it >= 0 }, page.total.takeIf { it >= 0 })
        }, safe(result), RequestOperation.TRACKS)
    }
    fun albumTracks(key: MediaKey, offset: Int = 0, result: (Result<Page<SongSummary>>) -> Unit) {
        require(key.platform == Platform.QQ)
        val generation = accounts.states.value.getValue(Platform.QQ).generation
        accounts.withQqSession({ api, session ->
            val page = QqLibraryApi(api) { accounts.isQqGenerationConnected(generation) }
                .albumTracks(session, key.id, offset, 30)
            Page(page.items.map(::summary),
                page.nextOffset.takeIf { it >= 0 }, page.total.takeIf { it >= 0 })
        }, safe(result), RequestOperation.TRACKS)
    }
    fun search(keywords: String, result: (Result<List<SongSummary>>) -> Unit) = search(keywords, result, false)
    fun search(keywords: String, result: (Result<List<SongSummary>>) -> Unit,
        playbackSearch: Boolean, isCurrent: () -> Boolean = { true }) {
        val generation = accounts.states.value.getValue(Platform.QQ).generation
        accounts.withQqSession({ api, session ->
            if (!isCurrent()) throw LibraryFailure("cancelled")
            val songs = QqLibraryApi(api) { accounts.isQqGenerationConnected(generation) }.search(session, keywords)
                .take(10)
                .map(::summary)
            if (!isCurrent()) throw LibraryFailure("cancelled")
            songs
        }, safe(result), RequestOperation.SEARCH, playbackSearch)
    }
    fun searchPage(keywords: String, offset: Int, result: (Result<Page<SongSummary>>) -> Unit,
        isCurrent: () -> Boolean = { true }) {
        val generation = accounts.states.value.getValue(Platform.QQ).generation
        accounts.withQqSession({ api, session ->
            if (!isCurrent()) throw LibraryFailure("cancelled")
            if (offset !in 0..19_980 || offset % 30 != 0) throw LibraryFailure("limit")
            val page = QqLibraryApi(api) { accounts.isQqGenerationConnected(generation) }
                .search(session, keywords, offset, 30)
            if (!isCurrent()) throw LibraryFailure("cancelled")
            Page(page.items.map(::summary), page.nextOffset.takeIf { it >= 0 }, page.total.takeIf { it >= 0 })
        }, safe(result), RequestOperation.SEARCH)
    }
    fun song(key: MediaKey, result: (Result<SongSummary>) -> Unit) = song(key, result, false)
    fun song(key: MediaKey, result: (Result<SongSummary>) -> Unit,
        playbackSearch: Boolean, isCurrent: () -> Boolean = { true }) {
        require(key.platform == Platform.QQ)
        val generation = accounts.states.value.getValue(Platform.QQ).generation
        accounts.withQqSession({ api, session ->
            if (!isCurrent()) throw LibraryFailure("cancelled")
            val item = QqLibraryApi(api) { accounts.isQqGenerationConnected(generation) }.song(session, key.id)
            val song = summary(item)
            if (!isCurrent()) throw LibraryFailure("cancelled")
            song
        }, safe(result), RequestOperation.SONG, playbackSearch)
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
                songs += page.items.map(::summary)
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
        }, safe(result), RequestOperation.QUEUE)
    }

    fun queueHead(playlist: MediaKey, startSong: MediaKey, knownSongs: List<SongSummary>,
        result: (Result<PlaylistQueueHead>) -> Unit) {
        require(playlist.platform == Platform.QQ && startSong.platform == Platform.QQ)
        val window = playlistQueueWindow(knownSongs, startSong, 30)
        if (window == null) {
            queue(playlist, startSong) { response -> result(response.map { PlaylistQueueHead(it,
                QueueRemainder(emptyList(), emptyList(), { callback -> callback(Result.success(null)) }, {})) }) }
            return
        }
        if (knownSongs.size > 20_000 || knownSongs.any { it.key.platform != Platform.QQ }) {
            result(Result.failure(LibraryFailure("limit")))
            return
        }
        val revision = queueRevision.incrementAndGet()
        val generation = accounts.states.value.getValue(Platform.QQ).generation
        if (!accounts.isQqGenerationConnected(generation)) {
            result(Result.failure(LibraryFailure("account_changed")))
            return
        }
        val deadline = SystemClock.elapsedRealtime() + 60_000
        var offset = knownSongs.size
        var loadedCount = knownSongs.size
        var expectedTotal: Int? = null
        var finished = false
        val remainder = QueueRemainder(window.before, window.after, { callback ->
            if (finished) {
                callback(Result.success(null))
            } else {
                accounts.withQqSession({ api, session ->
                    if (queueRevision.get() != revision || !accounts.isQqGenerationConnected(generation))
                        throw LibraryFailure("cancelled")
                    if (SystemClock.elapsedRealtime() >= deadline) throw LibraryFailure("timeout")
                    val library = QqLibraryApi(api) { queueRevision.get() == revision && accounts.isQqGenerationConnected(generation) }
                    val request = playlistQueuePage(offset)
                    val page = library.tracks(session, playlist.id, request.offset, request.limit)
                    if (expectedTotal != null && expectedTotal != page.total) throw LibraryFailure("incomplete")
                    expectedTotal = page.total.takeIf { it >= 0 } ?: throw LibraryFailure("incomplete")
                    if (expectedTotal!! > 20_000 || offset > expectedTotal!!) throw LibraryFailure("limit")
                    if (queueRevision.get() != revision || !accounts.isQqGenerationConnected(generation))
                        throw LibraryFailure("cancelled")
                    val songs = request.unseen(page.items).map(::summary)
                    if (page.nextOffset >= 0 && (songs.isEmpty() || page.nextOffset <= offset))
                        throw LibraryFailure("incomplete")
                    loadedCount += songs.size
                    if (page.nextOffset < 0) {
                        if (loadedCount != expectedTotal) throw LibraryFailure("incomplete")
                        finished = true
                    } else offset = page.nextOffset
                    if (SystemClock.elapsedRealtime() >= deadline) throw LibraryFailure("timeout")
                    if (queueRevision.get() != revision) throw LibraryFailure("cancelled")
                    songs
                }, safe { response -> if (queueRevision.get() == revision) callback(response) }, RequestOperation.QUEUE)
            }
        }, { queueRevision.compareAndSet(revision, revision + 1) })
        result(Result.success(PlaylistQueueHead(PlaybackQueue(window.head, window.startIndex, generation), remainder)))
    }

    fun resolveAudio(song: MediaKey, result: (Result<AudioCapability>) -> Unit) {
        resolveAudio(song, null, result)
    }
    fun qualities(song: MediaKey, result: (Result<tech.musxi.core.playback.QualityOptions>) -> Unit) {
        require(song.platform == Platform.QQ)
        val generation = accounts.states.value.getValue(Platform.QQ).generation
        accounts.withQqSession({ api, session ->
            val options = QqLibraryApi(api) { accounts.isQqGenerationConnected(generation) }
                .qualities(session, song.id, SystemClock.elapsedRealtime())
            tech.musxi.core.playback.QualityOptions(options.map { tech.musxi.core.playback.QualityOption(it.id, it.name) }, null)
        }, safe(result), RequestOperation.OTHER)
    }
    fun resolveAudio(song: MediaKey, qualityId: String?, result: (Result<AudioCapability>) -> Unit) {
        require(song.platform == Platform.QQ)
        val generation = accounts.states.value.getValue(Platform.QQ).generation
        accounts.withQqSession({ api, session ->
            val library = QqLibraryApi(api) { accounts.isQqGenerationConnected(generation) }
            val audio = resolvePreferredQuality("qq", accounts.preferredQuality(Platform.QQ), qualityId,
                { (it as? tech.musxi.qqloginpoc.QqMusicApi.ApiException)?.code }) { id ->
                library.audio(session, song.id, SystemClock.elapsedRealtime(), id)
            }
            if (qualityId != null && audio.qualityId != qualityId) throw LibraryFailure("unavailable")
            AudioCapability(audio.url, audio.expiresAtElapsedMs, audio.qualityId) { accounts.isQqGenerationConnected(generation) }
        }, safe(result), RequestOperation.AUDIO)
    }
    class AudioCapability internal constructor(private val url: String, private val expiresAt: Long, val qualityId: String,
        private val current: () -> Boolean) {
        fun deliverToPlayer(deliver: (String) -> Unit) {
            if (!current()) throw LibraryFailure("account_changed")
            if (SystemClock.elapsedRealtime() >= expiresAt) throw LibraryFailure("source_expired")
            deliver(url)
        }
        override fun toString() = "AudioCapability(redacted)"
    }
}
