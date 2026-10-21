package tech.musxi.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*

/** Fixed debug operations; only counts, enums and comparisons reach diagnostics. */
internal object P0ValidationRunner {
    private val main = Handler(Looper.getMainLooper())
    private var libraryPlatform = Platform.NETEASE
    private var firstSearchIds = emptySet<MediaKey>()
    private var firstSearchPlatform: Platform? = null
    private var expectedSong: SongSummary? = null
    private var expectedQuery: SearchQuery? = null
    private var canceledState: SearchBrowseState? = null
    private var probeRevision = 0L
    private val query = "周杰伦"

    private fun browser(app: MusxiApplication, platform: Platform = libraryPlatform): LibraryBrowser = when (platform) {
        Platform.NETEASE -> app.libraryBrowser
        Platform.QQ -> app.qqLibraryBrowser
        Platform.KUGOU -> app.kugouLibraryBrowser
    }

    fun run(app: MusxiApplication, step: String): Boolean {
        if (!step.startsWith("p0_")) return false
        val diag = app.getSharedPreferences("p0-validation", Context.MODE_PRIVATE)
        if (step != "p0_snapshot") {
            diag.edit().putString("operation", step).putString("operationResult", "requested").apply()
        }
        fun result(value: String) { diag.edit().putString("operationResult", value).apply() }
        val platform = when {
            step.startsWith("p0_netease_") -> Platform.NETEASE
            step.startsWith("p0_qq_") -> Platform.QQ
            step.startsWith("p0_kugou_") -> Platform.KUGOU
            else -> null
        }
        if (platform != null) {
            val action = step.removePrefix("p0_${platform.name.lowercase()}_")
            when (action) {
                "search", "search_empty" -> {
                    firstSearchIds = emptySet(); firstSearchPlatform = platform; canceledState = null
                    expectedQuery = SearchQuery(platform, if (action == "search_empty") "musxi_no_result_20261020_6f8e" else query)
                    app.searchBrowser.search(platform, expectedQuery!!.keywords)
                }
                "search_next" -> {
                    val current = app.searchBrowser.state.value
                    val page = current.page
                    val currentQuery = current.query
                    if (currentQuery?.platform == platform && page?.nextOffset != null) {
                        firstSearchIds = page.items.map { it.key }.toSet(); firstSearchPlatform = platform
                        expectedQuery = currentQuery.copy(offset = page.nextOffset!!)
                        app.searchBrowser.next()
                    } else result("no_next_page")
                }
                "playlists", "playlists_only" -> {
                    libraryPlatform = platform
                    browser(app).playlists(refresh = action == "playlists")
                }
                "tracks", "tracks_only" -> {
                    libraryPlatform = platform
                    val current = browser(app).state.value
                    val key = (current.content as? LibraryContent.Playlists)?.page?.items?.firstOrNull { it.trackCount > 0 }?.key
                        ?: current.query.playlist
                    if (key == null) result("select_playlists_first")
                    else browser(app).tracks(key, refresh = action == "tracks")
                }
                "cache_off", "cache_on" -> {
                    val debug = app as DebugMusxiApplication
                    val fetch = when (platform) {
                        Platform.NETEASE -> debug.validationLibrary
                        Platform.QQ -> debug.validationQqLibrary
                        Platform.KUGOU -> debug.validationKugouLibrary
                    }
                    fetch.offline = action == "cache_off"
                    result("configured")
                }
                "restore" -> { app.accounts.restore(platform); result("restore_requested") }
                else -> result("unknown_step")
            }
        } else when (step) {
            "p0_snapshot" -> Unit
            "p0_search_refresh" -> app.searchBrowser.refresh()
            "p0_search_aizo" -> {
                canceledState = null; firstSearchIds = emptySet()
                expectedQuery = SearchQuery(Platform.QQ, "AIZO")
                app.searchBrowser.search(Platform.QQ, "AIZO")
            }
            "p0_search_replace" -> {
                canceledState = null; firstSearchIds = emptySet()
                app.searchBrowser.search(Platform.NETEASE, "AIZO")
                expectedQuery = SearchQuery(Platform.QQ, query)
                app.searchBrowser.search(Platform.QQ, query)
            }
            "p0_search_cancel", "p0_search_cancel_active" -> {
                val token = ++probeRevision
                canceledState = null
                app.searchBrowser.search(Platform.NETEASE, "AIZO")
                val cancel = Runnable {
                    if (token == probeRevision) {
                        diag.edit().putBoolean("cancelWasLoading", app.searchBrowser.state.value.loading).apply()
                        app.searchBrowser.cancel()
                        canceledState = app.searchBrowser.state.value
                        snapshot(app)
                    }
                }
                if (step == "p0_search_cancel_active") main.postDelayed(cancel, 250) else cancel.run()
            }
            "p0_play_search", "p0_play_search_second", "p0_play_search_third" -> {
                val index = when (step) { "p0_play_search_second" -> 1; "p0_play_search_third" -> 2; else -> 0 }
                expectedSong = app.searchBrowser.state.value.page?.items?.getOrNull(index)
                expectedSong?.let { app.queuePlayback.playSong(it) } ?: result("search_song_missing")
            }
            "p0_play_library", "p0_play_library_second" -> {
                val current = browser(app).state.value
                expectedSong = (current.content as? LibraryContent.Tracks)?.page?.items?.getOrNull(
                    if (step == "p0_play_library_second") 1 else 0)
                if (expectedSong == null || current.query.playlist == null) result("select_tracks_first")
                else app.queuePlayback.playPlaylist(current.query.playlist!!, expectedSong!!.key)
            }
            "p0_verify_metadata" -> {
                val player = app.playback.state.value
                val selected = app.sourceSelection.state.value.selected?.song
                val original = (browser(app).state.value.content as? LibraryContent.Tracks)?.page?.items
                    ?.firstOrNull { it.key.stableId == player.mediaId }
                    ?: expectedSong?.takeIf { it.key.stableId == player.mediaId }
                val key = selected?.takeIf { it.key.platform == player.sourcePlatform }?.key ?: original?.key
                if (key == null || key.platform != player.sourcePlatform) result("metadata_key_missing")
                else {
                    val generations = app.accounts.states.value.mapValues { it.value.generation }
                    result("metadata_pending")
                    val callback: (Result<SongSummary>) -> Unit = { response ->
                        val now = app.playback.state.value
                        if (now.mediaId != player.mediaId || now.queueIndex != player.queueIndex ||
                            now.sourcePlatform != player.sourcePlatform ||
                            app.accounts.states.value.mapValues { it.value.generation } != generations) result("metadata_stale")
                        else response.onSuccess { song ->
                            val title = now.title == song.title
                            val artist = now.artist == song.artists.joinToString(" / ")
                            val album = now.album == song.album
                            val cover = now.coverUrl == song.coverUrl
                            val currentImage = now.coverUrl?.let { java.net.URI(it) }
                            val detailImage = song.coverUrl?.let { java.net.URI(it) }
                            diag.edit().putBoolean("detailTitleMatches", title).putBoolean("detailArtistMatches", artist)
                                .putBoolean("detailAlbumMatches", album).putBoolean("detailCoverMatches", cover)
                                .putBoolean("detailCoverPathMatches", currentImage != null && detailImage != null &&
                                    currentImage.rawPath == detailImage.rawPath)
                                .putBoolean("detailCoverHostMatches", currentImage != null && detailImage != null &&
                                    currentImage.host == detailImage.host)
                                .putBoolean("detailMetadataMatches", song.key == key && title && artist && album && cover).apply()
                            result("metadata_verified")
                        }.onFailure { result("metadata_request_failed") }
                    }
                    when (key.platform) {
                        Platform.NETEASE -> app.library.song(key, callback)
                        Platform.QQ -> app.qqLibrary.song(key, callback)
                        Platform.KUGOU -> app.kugouLibrary.song(key, callback)
                    }
                }
            }
            "p0_artwork_search", "p0_artwork_library", "p0_artwork_playback" -> {
                val pair = when (step) {
                    "p0_artwork_search" -> app.searchBrowser.state.value.page?.items?.firstOrNull { it.coverUrl != null }
                        ?.let { it.key.platform to it.coverUrl }
                    "p0_artwork_playback" -> app.playback.state.value.let { value -> value.sourcePlatform?.let { it to value.coverUrl } }
                    else -> when (val content = browser(app).state.value.content) {
                        is LibraryContent.Playlists -> content.page.items.firstOrNull { it.coverUrl != null }?.let { it.key.platform to it.coverUrl }
                        is LibraryContent.Tracks -> content.page.items.firstOrNull { it.coverUrl != null }?.let { it.key.platform to it.coverUrl }
                        else -> null
                    }
                }
                if (pair == null) { diag.edit().putString("artworkResult", "absent").apply(); result("finished") }
                else {
                    diag.edit().putString("artworkResult", "pending").putString("artworkPlatform", pair.first.name).apply()
                    ValidationArtworkProbe.check(pair.first, pair.second) {
                        diag.edit().putString("artworkResult", it).apply(); result("finished")
                    }
                }
            }
            else -> result("unknown_step")
        }
        snapshot(app)
        return true
    }

    private fun snapshot(app: MusxiApplication) {
        val diag = app.getSharedPreferences("p0-validation", Context.MODE_PRIVATE).edit()
        app.accounts.states.value.forEach { (platform, state) -> diag.putString("account${platform.name}", state.phase.name) }
        val search = app.searchBrowser.state.value
        diag.putString("searchPlatform", search.query?.platform?.name ?: "NONE")
            .putInt("searchOffset", search.query?.offset ?: -1).putBoolean("searchExpectedQuery", search.query == expectedQuery)
            .putBoolean("searchLoading", search.loading).putString("searchError", search.error ?: "NONE")
            .putInt("searchCount", search.page?.items?.size ?: -1).putInt("searchNext", search.page?.nextOffset ?: -1)
            .putInt("searchTotal", search.page?.total ?: -1)
            .putInt("searchCovers", search.page?.items?.count { it.coverUrl != null } ?: -1)
            .putBoolean("searchOnlySelectedPlatform", search.page?.items?.all { it.key.platform == search.query?.platform } == true)
            .putBoolean("searchPageChanged", firstSearchPlatform == search.query?.platform && firstSearchIds.isNotEmpty() &&
                search.page?.items?.isNotEmpty() == true && search.page!!.items.none { it.key in firstSearchIds })
            .putBoolean("cancelStable", canceledState != null && canceledState == search)
        val library = browser(app).state.value
        val items = when (val content = library.content) {
            is LibraryContent.Playlists -> content.page.items.size to content.page.items.count { it.coverUrl != null }
            is LibraryContent.Tracks -> content.page.items.size to content.page.items.count { it.coverUrl != null }
            else -> -1 to -1
        }
        diag.putString("libraryPlatform", libraryPlatform.name).putBoolean("libraryTracks", library.query.playlist != null)
            .putString("librarySource", library.source?.name ?: "NONE").putBoolean("libraryRefreshing", library.refreshing)
            .putString("libraryError", library.error ?: "NONE").putBoolean("libraryStorageError", library.storageError)
            .putBoolean("libraryVerified", library.accountVerified).putInt("libraryCount", items.first).putInt("libraryCovers", items.second)
        val player = app.playback.state.value
        val selection = app.sourceSelection.state.value
        val selected = selection.selected
        val expected = if (selected != null && player.sourcePlatform == selected.song.key.platform)
            selected.song else expectedSong
        val librarySong = (library.content as? LibraryContent.Tracks)?.page?.items
            ?.firstOrNull { it.key.stableId == player.mediaId }
        val libraryExpected = if (selected != null && player.sourcePlatform == selected.song.key.platform)
            selected.song else librarySong
        diag.putBoolean("connected", player.connected).putBoolean("playing", player.playing).putBoolean("wanted", player.playWhenReady)
            .putBoolean("ended", player.ended)
            .putBoolean("titleIsQingTian", player.title == "晴天")
            .putBoolean("buffering", player.buffering).putString("failure", player.failure?.name ?: "NONE")
            .putString("suppression", player.suppression.name).putLong("position", player.positionMs).putLong("duration", player.durationMs)
            .putInt("queueSize", player.queueSize).putInt("queueIndex", player.queueIndex)
            .putString("queuePlatform", player.queuePlatform?.name ?: "NONE").putString("sourcePlatform", player.sourcePlatform?.name ?: "NONE")
            .putBoolean("albumPresent", player.album.isNotBlank()).putBoolean("coverPresent", player.coverUrl != null)
            .putBoolean("metadataMatches", expected != null && player.title == expected.title &&
                player.artist == expected.artists.joinToString(" / ") && player.album == expected.album && player.coverUrl == expected.coverUrl)
            .putBoolean("queueKeyMatches", expectedSong != null && player.mediaId == expectedSong!!.key.stableId)
            .putBoolean("libraryQueueKeyMatches", librarySong != null)
            .putBoolean("libraryMetadataMatches", libraryExpected != null && player.title == libraryExpected.title &&
                player.artist == libraryExpected.artists.joinToString(" / ") && player.album == libraryExpected.album &&
                player.coverUrl == libraryExpected.coverUrl)
            .putString("loadError", app.queuePlayback.state.value.error ?: "NONE").putBoolean("queueLoading", app.queuePlayback.state.value.loading)
            .putString("selectionPhase", selection.phase.name).putInt("candidateCount", selection.candidates.size)
            .putBoolean("candidateSameTitle", selection.automaticIndex != null)
            .putInt("automaticTransitions", player.automaticTransitions)
        (app as? DebugMusxiApplication)?.validationSources?.let {
            diag.putString("resolverPlatform", it.lastPlatform).putInt("resolveCalls", it.resolveCount).putInt("injectedFailures", it.injectedCount)
            val resolved = it.lastMetadata
            val sourceKey = selected?.song?.key ?: librarySong?.key ?: expectedSong?.key
            diag.putBoolean("resolvedKeyMatches", resolved != null && resolved.key == sourceKey &&
                resolved.key.platform == player.sourcePlatform)
                .putBoolean("resolvedMetadataMatches", resolved != null && resolved.key == sourceKey &&
                    resolved.key.platform == player.sourcePlatform && player.title == resolved.title &&
                    player.artist == resolved.artists.joinToString(" / ") && player.album == resolved.album &&
                    player.coverUrl == resolved.coverUrl)
        }
        diag.apply()
    }
}
