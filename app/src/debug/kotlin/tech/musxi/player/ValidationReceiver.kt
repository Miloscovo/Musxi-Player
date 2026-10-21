package tech.musxi.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tech.musxi.core.library.*
import tech.musxi.core.account.*
import tech.musxi.accounts.NeteaseLibraryRepository
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import tech.musxi.playback.MusxiPlaybackService
import java.util.concurrent.Executor

/** Debug variant only. DUMP permission limits invocation to shell/system, no credential inputs. */
class ValidationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as MusxiApplication
        val step = intent.getStringExtra("step").orEmpty()
        if (!P2ValidationRunner.run(app, step) && !P1DataValidationRunner.run(app, step) && !P1ValidationRunner.run(app, step) && !P0ValidationRunner.run(app, step)) ValidationRunner.run(app, step)
    }
}

private object ValidationRunner {
    private var playlist: PlaylistSummary? = null
    private var songs: List<SongSummary> = emptyList()
    private var index = 0
    private var kugouPlaylist: PlaylistSummary? = null
    private var kugouSongs: List<SongSummary> = emptyList()
    private var qqPlaylist: PlaylistSummary? = null
    private var qqSongs: List<SongSummary> = emptyList()

    fun run(app: MusxiApplication, step: String) {
        val diag = app.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        diag.edit().remove("kugouAudioSchema").remove("kugouCdnHost").apply()
        fun status(value: String) {
            diag.edit().putString("validation", value).remove("validationError").remove("validationDiagnostic").apply()
        }
        fun failure(error: Throwable) {
            val code = (error as? LibraryPageFailure)?.category
            val safe = if (code in setOf("expired", "network", "risk", "rate", "protocol", "identity", "limit",
                    "incomplete", "permission", "unavailable", "endpoint", "platform", "http", "timeout")) code else "unclassified"
            diag.edit().putString("validation", "failed").putString("validationError", safe)
                .putString("validationDiagnostic", (error as? tech.musxi.accounts.KugouLibraryRepository.LibraryFailure)?.diagnostic
                    ?: (error as? tech.musxi.accounts.QqLibraryRepository.LibraryFailure)?.diagnostic ?: "NONE").apply()
        }
        if (step in setOf("playlists", "tracks", "audio", "audio_second")) {
            when (app.accounts.states.value.getValue(Platform.NETEASE).phase) {
                AccountPhase.REVALIDATING -> return status("restoring_account")
                AccountPhase.CONNECTED -> Unit
                else -> return status("account_not_connected")
            }
        }
        when (step) {
            "system_play_current", "system_stop_current" -> {
                val future = MediaController.Builder(app, SessionToken(app,
                    ComponentName(app, MusxiPlaybackService::class.java))).buildAsync()
                future.addListener({
                    try {
                        val controller = future.get()
                        if (step == "system_play_current") controller.play() else controller.stop()
                        Handler(Looper.getMainLooper()).postDelayed({ controller.release() }, 1000)
                        status("system_command_requested")
                    } catch (_: Exception) { status("system_command_failed") }
                }, Executor { Handler(Looper.getMainLooper()).post(it) })
            }
            "sources_qq_search_aizo", "sources_netease_search_aizo", "sources_kugou_search_aizo" -> {
                status("source_search_requested")
                val platform = when (step) {
                    "sources_qq_search_aizo" -> Platform.QQ
                    "sources_netease_search_aizo" -> Platform.NETEASE
                    else -> Platform.KUGOU
                }
                val callback: (Result<List<SongSummary>>) -> Unit = { result -> result.onSuccess { found ->
                    diag.edit().putInt("sourceSearchCount", found.size)
                        .putString("sourceSearchPlatform", platform.name)
                        .putInt("sourceSearchSameTitleCount", found.count {
                            tech.musxi.core.playback.SourceSelection.sameTitle("AIZO", it.title)
                        }).apply()
                    status("source_search_ok")
                }.onFailure(::failure) }
                when (platform) {
                    Platform.QQ -> app.qqLibrary.search("AIZO", callback)
                    Platform.NETEASE -> app.library.search("AIZO", callback)
                    Platform.KUGOU -> app.kugouLibrary.search("AIZO", callback)
                }
            }
            "sources_choose_first", "sources_choose_second" -> {
                val state = app.sourceSelection.state.value
                val accepted = app.sourceSelection.select(state.revision, if (step == "sources_choose_second") 1 else 0)
                status(if (accepted) "source_selection_accepted" else "source_selection_rejected")
            }
            "sources_cancel" -> {
                app.sourceSelection.cancel(app.sourceSelection.state.value.revision)
                status("source_selection_canceled")
            }
            "sources_search_aizo" -> {
                status("source_search_requested")
                val original = SongSummary(MediaKey(Platform.QQ, "A0000000000001"), "AIZO", emptyList(), "", 0)
                app.findAlternativeSources(original) { result -> result.onSuccess { found ->
                    diag.edit().putInt("sourceSearchCount", found.candidates.size)
                        .putBoolean("sourceSearchPartial", found.partial)
                        .putInt("sourceSearchSameTitleCount", found.candidates.count {
                            tech.musxi.core.playback.SourceSelection.sameTitle(found.original.title, it.song.title)
                        }).apply()
                    status("source_search_ok")
                }.onFailure(::failure) }
            }
            "qq_audio_aizo" -> {
                val selected = qqPlaylist ?: return status("qq_select_playlists_first")
                val song = qqSongs.firstOrNull { tech.musxi.core.playback.SourceSelection.sameTitle("AIZO", it.title) }
                    ?: return status("qq_no_aizo_in_first_page")
                app.queuePlayback.playPlaylist(selected.key, song.key)
                status("qq_queue_requested")
            }
            "focus_transient", "focus_permanent" -> {
                ValidationFocusActivity.launch(app, permanent = step == "focus_permanent")
                status("focus_probe_requested")
            }
            "focus_release" -> { ValidationFocusActivity.release(); status("focus_release_requested") }
            "kugou_connect" -> {
                app.accounts.connectOfficial(Platform.KUGOU) { target ->
                    try {
                        app.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        status("kugou_login_opened")
                    } catch (_: Exception) { status("kugou_open_failed") }
                }
                status("kugou_login_requested")
            }
            "kugou_check" -> { app.accounts.checkNow(Platform.KUGOU); status("kugou_check_requested") }
            "kugou_restore" -> { app.accounts.restore(Platform.KUGOU); status("kugou_restore_requested") }
            "qq_connect" -> {
                app.accounts.connectOfficial(Platform.QQ) { target ->
                    try {
                        app.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        status("qq_login_opened")
                    } catch (_: Exception) { status("qq_open_failed") }
                }
                status("qq_login_requested")
            }
            "qq_check" -> { app.accounts.checkNow(Platform.QQ); status("qq_check_requested") }
            "qq_restore" -> { app.accounts.restore(Platform.QQ); status("qq_restore_requested") }
            "netease_restore" -> { app.accounts.restore(Platform.NETEASE); status("netease_restore_requested") }
            "kugou_playlists" -> {
                kugouPlaylist = null; kugouSongs = emptyList()
                status("kugou_reading_playlists")
                app.kugouLibrary.playlists { result -> result.onSuccess { page ->
                    kugouPlaylist = page.items.firstOrNull { it.trackCount > 0 }
                    diag.edit().putInt("kugouPlaylistCount", page.items.size).apply()
                    status(if (kugouPlaylist != null) "kugou_playlists_ok" else "kugou_no_nonempty_playlist")
                }.onFailure(::failure) }
            }
            "kugou_tracks" -> {
                val selected = kugouPlaylist ?: return status("kugou_select_playlists_first")
                status("kugou_reading_tracks")
                app.kugouLibrary.tracks(selected.key) { result -> result.onSuccess { page ->
                    kugouSongs = page.items
                    diag.edit().putInt("kugouTrackCount", page.items.size).putInt("kugouTotalTracks", page.total ?: -1).apply()
                    status("kugou_tracks_ok")
                }.onFailure(::failure) }
            }
            "kugou_audio", "kugou_audio_second" -> {
                val selected = kugouPlaylist ?: return status("kugou_select_playlists_first")
                val song = kugouSongs.getOrNull(if (step == "kugou_audio_second") 1 else 0) ?: return status("kugou_no_selected_song")
                app.queuePlayback.playPlaylist(selected.key, song.key)
                status("kugou_queue_requested")
            }
            "kugou_source_probe" -> {
                val song = kugouSongs.firstOrNull() ?: return status("kugou_no_selected_song")
                status("kugou_source_requested")
                app.kugouLibrary.resolveAudio(song.key) { response ->
                    response.onSuccess { capability ->
                        capability.deliverToPlayer { url ->
                            diag.edit().putBoolean("kugouAudioUsesHttp", url.startsWith("http://")).apply()
                        }
                        status("kugou_source_ok")
                    }.onFailure(::failure)
                }
            }
            "qq_playlists" -> {
                qqPlaylist = null; qqSongs = emptyList()
                status("qq_reading_playlists")
                app.qqLibrary.playlists { result -> result.onSuccess { page ->
                    qqPlaylist = page.items.firstOrNull { it.trackCount > 0 }
                    diag.edit().putInt("qqPlaylistCount", page.items.size).apply()
                    status(if (qqPlaylist != null) "qq_playlists_ok" else "qq_no_nonempty_playlist")
                }.onFailure(::failure) }
            }
            "qq_tracks" -> {
                val selected = qqPlaylist ?: return status("qq_select_playlists_first")
                status("qq_reading_tracks")
                app.qqLibrary.tracks(selected.key) { result -> result.onSuccess { page ->
                    qqSongs = page.items
                    diag.edit().putInt("qqTrackCount", page.items.size).putInt("qqTotalTracks", page.total ?: -1).apply()
                    status("qq_tracks_ok")
                }.onFailure(::failure) }
            }
            "qq_audio", "qq_audio_second" -> {
                val selected = qqPlaylist ?: return status("qq_select_playlists_first")
                val song = qqSongs.getOrNull(if (step == "qq_audio_second") 1 else 0) ?: return status("qq_no_selected_song")
                app.queuePlayback.playPlaylist(selected.key, song.key)
                status("qq_queue_requested")
            }
            "qq_source_probe" -> {
                val song = qqSongs.firstOrNull() ?: return status("qq_no_selected_song")
                status("qq_source_requested")
                app.qqLibrary.resolveAudio(song.key) { response ->
                    response.onSuccess { capability ->
                        capability.deliverToPlayer { url ->
                            diag.edit().putBoolean("qqAudioUsesHttp", url.startsWith("http://")).apply()
                        }
                        status("qq_source_ok")
                    }.onFailure(::failure)
                }
            }
            "cache_network_off", "cache_network_on" -> {
                (app as DebugMusxiApplication).validationLibrary.offline = step == "cache_network_off"
                status("cache_network_configured")
            }
            "cache_playlists", "cache_playlists_only" -> {
                app.libraryBrowser.playlists(refresh = step != "cache_playlists_only")
                status("cache_read_requested")
            }
            "cache_tracks", "cache_tracks_only" -> {
                val selected = (app.libraryBrowser.state.value.content as? LibraryContent.Playlists)
                    ?.page?.items?.firstOrNull { it.trackCount > 0 } ?: playlist
                    ?: return status("select_playlists_first")
                playlist = selected
                app.libraryBrowser.tracks(selected.key, refresh = step != "cache_tracks_only")
                status("cache_read_requested")
            }
            "cache_refresh" -> { app.libraryBrowser.refresh(); status("cache_read_requested") }
            "cache_snapshot" -> {
                val state = app.libraryBrowser.state.value
                val count: Int
                val total: Int?
                val next: Int?
                when (val content = state.content) {
                    is LibraryContent.Playlists -> { count = content.page.items.size; total = content.page.total; next = content.page.nextOffset }
                    is LibraryContent.Tracks -> { count = content.page.items.size; total = content.page.total; next = content.page.nextOffset }
                    null -> { count = 0; total = null; next = null }
                }
                diag.edit().putString("cacheSource", state.source?.name ?: "NONE")
                    .putBoolean("cacheHasContent", state.content != null).putInt("cacheItemCount", count)
                    .putInt("cacheTotal", total ?: -1).putInt("cacheNextOffset", next ?: -1)
                    .putInt("cacheOffset", state.query.offset).putBoolean("cacheTracks", state.query.playlist != null)
                    .putBoolean("cacheRefreshing", state.refreshing).putBoolean("cacheAccountVerified", state.accountVerified)
                    .putBoolean("cacheStorageError", state.storageError).putString("cacheError", state.error ?: "NONE")
                    .putBoolean("cacheHasTimestamp", state.fetchedAtEpochMs != null)
                    .putBoolean("cacheWholeCollection", state.content != null && state.query.offset == 0 && next == null)
                    .putInt("cacheNetworkRequests", (app as DebugMusxiApplication).validationLibrary.requests).apply()
                status("cache_snapshot_ok")
            }
            "kugou_cache_network_off", "kugou_cache_network_on" -> {
                (app as DebugMusxiApplication).validationKugouLibrary.offline = step == "kugou_cache_network_off"
                status("kugou_cache_network_configured")
            }
            "kugou_cache_playlists", "kugou_cache_playlists_only" -> {
                app.kugouLibraryBrowser.playlists(refresh = step != "kugou_cache_playlists_only")
                status("kugou_cache_read_requested")
            }
            "kugou_cache_tracks", "kugou_cache_tracks_only" -> {
                val selected = (app.kugouLibraryBrowser.state.value.content as? LibraryContent.Playlists)
                    ?.page?.items?.firstOrNull { it.trackCount > 0 } ?: kugouPlaylist
                    ?: return status("select_playlists_first")
                kugouPlaylist = selected
                app.kugouLibraryBrowser.tracks(selected.key, refresh = step != "kugou_cache_tracks_only")
                status("kugou_cache_read_requested")
            }
            "kugou_cache_next" -> {
                val state = app.kugouLibraryBrowser.state.value
                val next = when (val content = state.content) {
                    is LibraryContent.Playlists -> content.page.nextOffset
                    is LibraryContent.Tracks -> content.page.nextOffset
                    null -> null
                } ?: return status("kugou_cache_no_next")
                val selected = state.query.playlist
                if (selected == null) app.kugouLibraryBrowser.playlists(next)
                else app.kugouLibraryBrowser.tracks(selected, next)
                status("kugou_cache_read_requested")
            }
            "kugou_cache_refresh" -> { app.kugouLibraryBrowser.refresh(); status("kugou_cache_read_requested") }
            "kugou_cache_snapshot" -> {
                val state = app.kugouLibraryBrowser.state.value
                val count: Int
                val total: Int?
                val next: Int?
                when (val content = state.content) {
                    is LibraryContent.Playlists -> { count = content.page.items.size; total = content.page.total; next = content.page.nextOffset }
                    is LibraryContent.Tracks -> { count = content.page.items.size; total = content.page.total; next = content.page.nextOffset }
                    null -> { count = 0; total = null; next = null }
                }
                diag.edit().putString("kugouCacheSource", state.source?.name ?: "NONE")
                    .putBoolean("kugouCacheHasContent", state.content != null).putInt("kugouCacheItemCount", count)
                    .putInt("kugouCacheTotal", total ?: -1).putInt("kugouCacheNextOffset", next ?: -1)
                    .putInt("kugouCacheOffset", state.query.offset).putBoolean("kugouCacheTracks", state.query.playlist != null)
                    .putBoolean("kugouCacheRefreshing", state.refreshing).putBoolean("kugouCacheAccountVerified", state.accountVerified)
                    .putBoolean("kugouCacheStorageError", state.storageError).putString("kugouCacheError", state.error ?: "NONE")
                    .putBoolean("kugouCacheHasTimestamp", state.fetchedAtEpochMs != null)
                    .putBoolean("kugouCacheWholeCollection", state.content != null && state.query.offset == 0 && next == null)
                    .putInt("kugouCacheNetworkRequests", (app as DebugMusxiApplication).validationKugouLibrary.requests).apply()
                status("kugou_cache_snapshot_ok")
            }
            "qq_cache_network_off", "qq_cache_network_on" -> {
                (app as DebugMusxiApplication).validationQqLibrary.offline = step == "qq_cache_network_off"
                status("qq_cache_network_configured")
            }
            "qq_cache_playlists", "qq_cache_playlists_only" -> {
                app.qqLibraryBrowser.playlists(refresh = step != "qq_cache_playlists_only")
                status("qq_cache_read_requested")
            }
            "qq_cache_tracks", "qq_cache_tracks_only" -> {
                val selected = (app.qqLibraryBrowser.state.value.content as? LibraryContent.Playlists)
                    ?.page?.items?.firstOrNull { it.trackCount > 0 } ?: qqPlaylist
                    ?: return status("select_playlists_first")
                qqPlaylist = selected
                app.qqLibraryBrowser.tracks(selected.key, refresh = step != "qq_cache_tracks_only")
                status("qq_cache_read_requested")
            }
            "qq_cache_next" -> {
                val state = app.qqLibraryBrowser.state.value
                val next = when (val content = state.content) {
                    is LibraryContent.Playlists -> content.page.nextOffset
                    is LibraryContent.Tracks -> content.page.nextOffset
                    null -> null
                } ?: return status("qq_cache_no_next")
                val selected = state.query.playlist
                if (selected == null) app.qqLibraryBrowser.playlists(next)
                else app.qqLibraryBrowser.tracks(selected, next)
                status("qq_cache_read_requested")
            }
            "qq_cache_refresh" -> { app.qqLibraryBrowser.refresh(); status("qq_cache_read_requested") }
            "qq_cache_snapshot" -> {
                val state = app.qqLibraryBrowser.state.value
                val count: Int
                val total: Int?
                val next: Int?
                when (val content = state.content) {
                    is LibraryContent.Playlists -> { count = content.page.items.size; total = content.page.total; next = content.page.nextOffset }
                    is LibraryContent.Tracks -> { count = content.page.items.size; total = content.page.total; next = content.page.nextOffset }
                    null -> { count = 0; total = null; next = null }
                }
                diag.edit().putString("qqCacheSource", state.source?.name ?: "NONE")
                    .putBoolean("qqCacheHasContent", state.content != null).putInt("qqCacheItemCount", count)
                    .putInt("qqCacheTotal", total ?: -1).putInt("qqCacheNextOffset", next ?: -1)
                    .putInt("qqCacheOffset", state.query.offset).putBoolean("qqCacheTracks", state.query.playlist != null)
                    .putBoolean("qqCacheRefreshing", state.refreshing).putBoolean("qqCacheAccountVerified", state.accountVerified)
                    .putBoolean("qqCacheStorageError", state.storageError).putString("qqCacheError", state.error ?: "NONE")
                    .putBoolean("qqCacheHasTimestamp", state.fetchedAtEpochMs != null)
                    .putBoolean("qqCacheWholeCollection", state.content != null && state.query.offset == 0 && next == null)
                    .putInt("qqCacheNetworkRequests", (app as DebugMusxiApplication).validationQqLibrary.requests).apply()
                status("qq_cache_snapshot_ok")
            }
            "reject_stale_queue" -> {
                val generation = app.playbackSources.currentGeneration() ?: return status("account_not_connected")
                if (songs.isEmpty()) return status("no_selected_song")
                app.playback.playQueue(PlaybackQueue(songs, 0, generation - 1)) {
                    status(if (it.isFailure) "stale_queue_rejected" else "unexpected_queue_acceptance")
                }
            }
            "system_stop_then_play", "system_stop_interleaved_play" -> {
                val generation = app.playbackSources.currentGeneration() ?: return status("account_not_connected")
                if (songs.size < 2) return status("need_two_selected_songs")
                val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, MusxiPlaybackService::class.java))).buildAsync()
                future.addListener({
                    try {
                        val controller = future.get()
                        fun playSecond() {
                            app.playback.playQueue(PlaybackQueue(songs, 1, generation)) {
                                status(if (it.isSuccess) "post_stop_play_accepted" else "post_stop_play_rejected")
                            }
                        }
                        if (step == "system_stop_interleaved_play")
                            (app as DebugMusxiApplication).afterStopRequestOnce = { playSecond() }
                        controller.stop()
                        if (step == "system_stop_then_play") Handler(Looper.getMainLooper()).postDelayed({ playSecond() }, 50)
                        Handler(Looper.getMainLooper()).postDelayed({ controller.release() }, 1000)
                    } catch (_: Exception) { status("system_stop_failed") }
                }, Executor { Handler(Looper.getMainLooper()).post(it) })
            }
            "immediate_play_stop", "rapid_replace" -> {
                val generation = app.playbackSources.currentGeneration() ?: return status("account_not_connected")
                if (songs.size < 2) return status("need_two_selected_songs")
                app.queuePlayback.cancelPending()
                app.playback.playQueue(PlaybackQueue(songs, 0, generation))
                app.playback.stop()
                if (step == "rapid_replace") app.playback.playQueue(PlaybackQueue(songs, 1, generation))
                status("ordering_test_requested")
            }
            "system_stop_loading" -> {
                val selected = playlist ?: return status("select_playlists_first")
                val song = songs.firstOrNull() ?: return status("no_selected_song")
                val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, MusxiPlaybackService::class.java))).buildAsync()
                future.addListener({
                    try {
                        val controller = future.get()
                        app.queuePlayback.playPlaylist(selected.key, song.key)
                        controller.stop() // Ordinary MediaSession STOP, not the app's private clear command.
                        Handler(Looper.getMainLooper()).postDelayed({ controller.release() }, 1000)
                        status("system_stop_requested")
                    } catch (_: Exception) { status("system_stop_failed") }
                }, Executor { Handler(Looper.getMainLooper()).post(it) })
            }
            "fault_expired_once", "fault_expired_twice", "fault_permission_once", "fault_permission_delayed", "fault_permission_chain", "fault_network_once", "fault_clear" -> {
                (app as DebugMusxiApplication).validationSources.arm(step); status("fault_configured")
            }
            "playlists" -> {
                playlist = null; songs = emptyList(); index = 0
                status("reading_playlists")
                app.library.playlists { result ->
                    result.onSuccess { page ->
                        playlist = page.items.firstOrNull { it.trackCount > 0 }
                        diag.edit().putInt("validationPlaylistCount", page.items.size).apply()
                        status(if (playlist != null) "playlists_ok" else "no_nonempty_playlist")
                    }.onFailure(::failure)
                }
            }
            "tracks" -> {
                val selected = playlist ?: return status("select_playlists_first")
                status("reading_tracks")
                app.library.tracks(selected.key) { result ->
                    result.onSuccess { page ->
                        songs = page.items; index = 0
                        diag.edit().putInt("validationTrackCount", page.items.size).apply()
                        status("tracks_ok")
                    }.onFailure(::failure)
                }
            }
            "audio", "audio_second" -> {
                index = if (step == "audio_second") 1 else 0
                val song = songs.getOrNull(index) ?: return status("no_selected_song")
                val selected = playlist ?: return status("select_playlists_first")
                app.queuePlayback.playPlaylist(selected.key, song.key)
                status("queue_requested")
            }
            "next", "next_audio" -> { app.playback.next(); status("next_requested") }
            "previous" -> { app.playback.previous(); status("previous_requested") }
            "last" -> { app.playback.select(app.playback.state.value.queueSize - 1); status("last_requested") }
            "finish" -> {
                val duration = app.playback.state.value.durationMs
                if (duration < 2000) return status("duration_not_ready")
                app.playback.seekTo(duration - 1000); status("finish_requested")
            }
            "pause" -> { app.playback.pause(); status("pause_requested") }
            "toggle" -> { app.playback.toggle(); status("toggle_requested") }
            "resume" -> { app.playback.resume(); status("resume_requested") }
            "seek" -> { app.playback.seekTo(15_000); status("seek_requested") }
            "seek_late" -> {
                val duration = app.playback.state.value.durationMs
                if (duration < 10_000) return status("duration_not_ready")
                app.playback.seekTo(duration * 3 / 4); status("seek_late_requested")
            }
            "recreate_page" -> {
                val activity = (app as DebugMusxiApplication).validationActivity
                    ?: return status("activity_not_started")
                activity.recreate(); status("recreate_requested")
            }
            "stop" -> { app.queuePlayback.stop(); status("stop_requested") }
            "snapshot" -> {
                val playback = app.playback.state.value
                val selection = app.sourceSelection.state.value
                val accountSnapshot = app.accounts.states.value
                diag.edit().putBoolean("playing", playback.playing).putBoolean("playbackConnected", playback.connected)
                    .putString("sourceSelectionPhase", selection.phase.name)
                    .putInt("sourceCandidateCount", selection.candidates.size)
                    .putString("sourceOriginalPlatform", selection.original?.key?.platform?.name ?: "NONE")
                    .putString("sourceSelectedPlatform", selection.selected?.song?.key?.platform?.name ?: "NONE")
                    .putInt("sourceSelectedIndex", selection.selected?.let { selection.candidates.indexOf(it) } ?: -1)
                    .putBoolean("sourceSelectedSameTitle", selection.selected?.let {
                        tech.musxi.core.playback.SourceSelection.sameTitle(selection.original?.title.orEmpty(), it.song.title)
                    } == true)
                    .putInt("sourceAutomaticIndex", selection.automaticIndex ?: -1)
                    .putLong("sourceDeadlineRemainingMs", selection.deadlineElapsedMs?.let {
                        (it - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0)
                    } ?: -1)
                    .putString("sourceSelectionError", selection.error ?: "NONE")
                    .putString("KUGOU", accountSnapshot.getValue(Platform.KUGOU).phase.name)
                    .putString("QQ", accountSnapshot.getValue(Platform.QQ).phase.name)
                    .putString("NETEASE", accountSnapshot.getValue(Platform.NETEASE).phase.name)
                    .putBoolean("playbackError", playback.error != null).putLong("positionMs", playback.positionMs)
                    .putLong("durationMs", playback.durationMs).putInt("queueSize", playback.queueSize)
                    .putInt("queueIndex", playback.queueIndex).putBoolean("hasNext", playback.hasNext).putBoolean("hasPrevious", playback.hasPrevious)
                    .putBoolean("ended", playback.ended).putBoolean("buffering", playback.buffering)
                    .putBoolean("playWhenReady", playback.playWhenReady)
                    .putString("playbackSuppression", playback.suppression.name)
                    .putBoolean("deviceInteractive", (app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive)
                    .putBoolean("keyguardLocked", (app.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked)
                    .putInt("automaticTransitions", playback.automaticTransitions).putInt("skippedCount", playback.skippedCount)
                    .putInt("sourceRefreshCount", playback.sourceRefreshCount)
                    .putString("playbackFailure", playback.failure?.name ?: "NONE")
                    .putBoolean("queueLoading", app.queuePlayback.state.value.loading)
                    .putString("queueLoadError", app.queuePlayback.state.value.error ?: "NONE")
                    .putInt("injectedFailures", (app as DebugMusxiApplication).validationSources.injectedCount)
                    .putInt("sourceResolveCalls", app.validationSources.resolveCount)
                    .putString("sourceLastResolvedPlatform", app.validationSources.lastPlatform)
                    .putBoolean("matchesFirstSong", playback.mediaId == songs.firstOrNull()?.key?.stableId)
                    .putBoolean("matchesSecondSong", playback.mediaId == songs.getOrNull(1)?.key?.stableId)
                    .putBoolean("isKugouPlayback", playback.mediaId?.startsWith("kugou:") == true)
                    .putBoolean("isQqPlayback", playback.mediaId?.startsWith("qq:") == true)
                    .putBoolean("matchesQqFirstSong", playback.mediaId == qqSongs.firstOrNull()?.key?.stableId)
                    .putBoolean("matchesQqSecondSong", playback.mediaId == qqSongs.getOrNull(1)?.key?.stableId)
                    .putBoolean("matchesKugouFirstSong", playback.mediaId == kugouSongs.firstOrNull()?.key?.stableId)
                    .putBoolean("matchesKugouSecondSong", playback.mediaId == kugouSongs.getOrNull(1)?.key?.stableId)
                    .putInt("distinctSelectedSongs", songs.map { it.key }.distinct().size).apply()
                status("snapshot_ok")
            }
            else -> status("unsupported_step")
        }
    }
}
