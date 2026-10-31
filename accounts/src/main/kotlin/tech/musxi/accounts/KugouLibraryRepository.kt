package tech.musxi.accounts

import android.os.SystemClock
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*
import tech.musxi.core.diagnostics.RequestOperation
import tech.musxi.loginpoc.KugouApi
import tech.musxi.loginpoc.KugouLibraryApi

/** Official account-bound requests. No session or raw platform response reaches a page. */
class KugouLibraryRepository(private val accounts: AccountRepository) : LibraryPageFetch {
    private val queueRevision = java.util.concurrent.atomic.AtomicLong()
    private val metadata = linkedMapOf<MediaKey, SongSummary>()
    private var metadataGeneration = -1L
    private val playlistPages = java.util.TreeMap<Int, List<String>>()
    private val playlistRoutes = linkedMapOf<String, KugouLibraryApi.Playlist>()
    private var playlistGeneration = -1L
    private fun summary(song: KugouLibraryApi.Song) =
        SongSummary(MediaKey(Platform.KUGOU, song.id), song.name, song.artists, song.album, song.durationMs,
            ArtworkPolicy.sanitize(Platform.KUGOU, song.coverUrl))
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
            Result.failure(LibraryFailure(if (code in setOf("expired", "network", "risk", "rate", "protocol", "identity", "limit", "cancelled",
                    "incomplete", "permission", "unavailable", "endpoint", "platform", "http", "timeout", "source_expired", "account_changed")) code!! else "unclassified", detail))
        }))
    }
    override fun playlists(offset: Int, result: (Result<Page<PlaylistSummary>>) -> Unit) {
        val generation = accounts.states.value.getValue(Platform.KUGOU).generation
        accounts.withKugouSession({ api, session ->
            val library = KugouLibraryApi(api)
            val page = library.playlists(session, offset, 30)
            if (!accounts.isKugouGenerationConnected(generation)) throw LibraryFailure("account_changed")
            val items = synchronized(playlistPages) {
                if (playlistGeneration != generation) {
                    playlistGeneration = generation
                    playlistPages.clear()
                    playlistRoutes.clear()
                }
                playlistPages.tailMap(offset, true).clear()
                val prior = playlistPages.values.flatten().toSet()
                val current = page.items.distinctBy { it.id }
                playlistPages[offset] = current.map { it.id }
                current.forEach { playlistRoutes[it.id] = it }
                current.filter { it.id !in prior }
            }
            // Kugou leaves the default and liked lists without artwork. Like the PC build, borrow the
            // cover of the song the page shows first (newest first). Bounded and best-effort: a failed
            // lookup keeps the placeholder and never fails the playlist page.
            var lookups = 0
            Page(items.map { item ->
                val own = ArtworkPolicy.sanitize(Platform.KUGOU, item.coverUrl)
                val cover = own ?: if (item.trackCount > 0 && lookups < 6) {
                    lookups++
                    val lookedUp = try {
                        library.tracksNewestFirst(session, item, 0, 1).items.firstOrNull()?.coverUrl
                    } catch (e: java.io.IOException) {
                        null
                    } catch (e: KugouApi.ApiException) {
                        null
                    }
                    lookedUp?.let { ArtworkPolicy.sanitize(Platform.KUGOU, it) }
                } else null
                PlaylistSummary(MediaKey(Platform.KUGOU, item.id), item.name, cover, item.trackCount)
            }, page.nextOffset.takeIf { it >= 0 }, page.total.takeIf { it >= 0 })
        }, safe(result), RequestOperation.PLAYLISTS)
    }
    override fun tracks(playlist: MediaKey, offset: Int, result: (Result<Page<SongSummary>>) -> Unit) {
        require(playlist.platform == Platform.KUGOU)
        val generation = accounts.states.value.getValue(Platform.KUGOU).generation
        accounts.withKugouSession({ api, session ->
            val route = playlistRoute(api, session, playlist.id, generation)
            // Editable cloud lists are newest first. Collected source lists retain their published order.
            val page = KugouLibraryApi(api).tracksNewestFirst(session, route, offset, 30)
            Page(remember(page.items.map(::summary), generation),
                page.nextOffset.takeIf { it >= 0 }, page.total.takeIf { it >= 0 })
        }, safe(result), RequestOperation.TRACKS)
    }
    private fun playlistRoute(api: KugouApi, session: KugouApi.Session, id: String, generation: Long): KugouLibraryApi.Playlist {
        synchronized(playlistPages) {
            if (playlistGeneration == generation) playlistRoutes[id]?.let { return it }
        }
        // Cached page keys survive process death; resolve the source GID again before opening details.
        val library = KugouLibraryApi(api)
        val deadline = SystemClock.elapsedRealtime() + 60_000
        var offset = 0
        while (true) {
            if (!accounts.isKugouGenerationConnected(generation)) throw LibraryFailure("account_changed")
            if (SystemClock.elapsedRealtime() >= deadline) throw LibraryFailure("timeout")
            val page = library.playlists(session, offset, 100)
            if (!accounts.isKugouGenerationConnected(generation)) throw LibraryFailure("account_changed")
            synchronized(playlistPages) {
                if (playlistGeneration != generation) {
                    playlistGeneration = generation; playlistPages.clear(); playlistRoutes.clear()
                }
                page.items.forEach { playlistRoutes[it.id] = it }
                playlistRoutes[id]?.let { return it }
            }
            if (page.nextOffset < 0) throw LibraryFailure("unavailable")
            if (page.nextOffset <= offset || page.nextOffset > 20_000) throw LibraryFailure("limit")
            offset = page.nextOffset
        }
    }
    fun search(keywords: String, result: (Result<List<SongSummary>>) -> Unit) = search(keywords, result, false)
    fun search(keywords: String, result: (Result<List<SongSummary>>) -> Unit,
        playbackSearch: Boolean, isCurrent: () -> Boolean = { true }) {
        val generation = accounts.states.value.getValue(Platform.KUGOU).generation
        accounts.withKugouSession({ api, session ->
            if (!isCurrent()) throw LibraryFailure("cancelled")
            val songs = KugouLibraryApi(api).search(session, keywords).map(::summary)
            if (!isCurrent()) throw LibraryFailure("cancelled")
            remember(songs, generation)
        }, safe(result), RequestOperation.SEARCH, playbackSearch)
    }
    fun searchPage(keywords: String, offset: Int, result: (Result<Page<SongSummary>>) -> Unit,
        isCurrent: () -> Boolean = { true }) {
        val generation = accounts.states.value.getValue(Platform.KUGOU).generation
        accounts.withKugouSession({ api, session ->
            if (!isCurrent()) throw LibraryFailure("cancelled")
            if (offset !in 0..19_980 || offset % 30 != 0) throw LibraryFailure("limit")
            val page = KugouLibraryApi(api).search(session, keywords, offset, 30)
            if (!isCurrent()) throw LibraryFailure("cancelled")
            Page(remember(page.items.map(::summary), generation),
                page.nextOffset.takeIf { it >= 0 }, page.total.takeIf { it >= 0 })
        }, safe(result), RequestOperation.SEARCH)
    }
    /** The pinned vendor has no general song-detail module. Reuse known metadata only. */
    fun song(key: MediaKey, result: (Result<SongSummary>) -> Unit) = song(key, result, false)
    fun song(key: MediaKey, result: (Result<SongSummary>) -> Unit,
        playbackSearch: Boolean, isCurrent: () -> Boolean = { true }) {
        val generation = accounts.states.value.getValue(Platform.KUGOU).generation
        accounts.withKugouSession({ _, _ ->
            if (!isCurrent()) throw LibraryFailure("cancelled")
            if (key.platform != Platform.KUGOU) throw LibraryFailure("identity")
            if (!accounts.isKugouGenerationConnected(generation)) throw LibraryFailure("account_changed")
            val song = synchronized(metadata) {
                if (metadataGeneration != generation) throw LibraryFailure("unavailable")
                metadata[key] ?: throw LibraryFailure("unavailable")
            }
            if (!isCurrent()) throw LibraryFailure("cancelled")
            song
        }, safe(result), RequestOperation.SONG, playbackSearch)
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
                val route = playlistRoute(api, session, playlist.id, generation)
                val page = library.tracks(session, route, offset, 100)
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
            val route = playlistRoute(api, session, playlist.id, generation)
            if (route.sourceGlobalId == null) {
                // Editable lists are displayed newest first; source lists keep their published order.
                songs.reverse()
            }
            val index = songs.indexOfFirst { it.key == startSong }
            if (index < 0) throw LibraryFailure("incomplete")
            remember(songs, generation)
            PlaybackQueue(songs, index, generation)
        }, safe(result), RequestOperation.QUEUE)
    }

    fun queueHead(playlist: MediaKey, startSong: MediaKey, knownSongs: List<SongSummary>,
        result: (Result<PlaylistQueueHead>) -> Unit) {
        require(playlist.platform == Platform.KUGOU && startSong.platform == Platform.KUGOU)
        val window = playlistQueueWindow(knownSongs, startSong, 30)
        if (window == null) {
            queue(playlist, startSong) { response -> result(response.map { PlaylistQueueHead(it,
                QueueRemainder(emptyList(), emptyList(), { callback -> callback(Result.success(null)) }, {})) }) }
            return
        }
        if (knownSongs.size > 20_000 || knownSongs.any { it.key.platform != Platform.KUGOU }) {
            result(Result.failure(LibraryFailure("limit")))
            return
        }
        val revision = queueRevision.incrementAndGet()
        val generation = accounts.states.value.getValue(Platform.KUGOU).generation
        if (!accounts.isKugouGenerationConnected(generation)) {
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
                accounts.withKugouSession({ api, session ->
                    if (queueRevision.get() != revision || !accounts.isKugouGenerationConnected(generation))
                        throw LibraryFailure("cancelled")
                    if (SystemClock.elapsedRealtime() >= deadline) throw LibraryFailure("timeout")
                    val request = playlistQueuePage(offset)
                    val route = playlistRoute(api, session, playlist.id, generation)
                    val page = KugouLibraryApi(api).tracksNewestFirst(session, route, request.offset, request.limit)
                    if (expectedTotal != null && expectedTotal != page.total) throw LibraryFailure("incomplete")
                    expectedTotal = page.total.takeIf { it >= 0 } ?: throw LibraryFailure("incomplete")
                    if (expectedTotal!! > 20_000 || offset > expectedTotal!!) throw LibraryFailure("limit")
                    if (queueRevision.get() != revision || !accounts.isKugouGenerationConnected(generation))
                        throw LibraryFailure("cancelled")
                    val songs = remember(request.unseen(page.items).map(::summary), generation)
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
        require(song.platform == Platform.KUGOU)
        accounts.withKugouSession({ api, session ->
            val options = KugouLibraryApi(api).qualities(session, song.id, SystemClock.elapsedRealtime())
            tech.musxi.core.playback.QualityOptions(options.map { tech.musxi.core.playback.QualityOption(it.id, it.name) }, null)
        }, safe(result), RequestOperation.OTHER)
    }
    fun resolveAudio(song: MediaKey, qualityId: String?, result: (Result<AudioCapability>) -> Unit) {
        require(song.platform == Platform.KUGOU)
        val generation = accounts.states.value.getValue(Platform.KUGOU).generation
        accounts.withKugouSession({ api, session ->
            val library = KugouLibraryApi(api)
            val audio = resolvePreferredQuality("kugou", accounts.preferredQuality(Platform.KUGOU), qualityId,
                { (it as? KugouApi.ApiException)?.code }) { id ->
                library.audio(session, song.id, SystemClock.elapsedRealtime(), id)
            }
            if (qualityId != null && audio.qualityId != qualityId) throw LibraryFailure("unavailable")
            AudioCapability(audio.url, audio.expiresAtElapsedMs, audio.qualityId) { accounts.isKugouGenerationConnected(generation) }
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
