package tech.musxi.player

import java.io.File
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import tech.musxi.core.account.*
import tech.musxi.core.library.*
import tech.musxi.core.playback.*

/** Fixed read-only platform cases; journals/active queue must be preserved with live_prepare. */
internal object QueueConsistencyValidationRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = false
    fun run(app: MusxiApplication, step: String): Boolean {
        val platform = when (step.substringAfterLast('_')) {
            "netease" -> Platform.NETEASE; "qq" -> Platform.QQ; "kugou" -> Platform.KUGOU
            else -> null
        }
        if (step !in setOf("batch2_read_netease", "batch2_read_qq", "batch2_read_kugou",
                "batch2_stop_kugou", "batch2_restore_prepare", "batch2_restore_check", "batch2_restore_race")) return false
        if (running) return true
        running = true
        scope.launch {
            val report = JSONObject().put("status", "running").put("startedAt", System.currentTimeMillis())
            val file = File(app.filesDir, "$step.json")
            fun flush() = file.writeText(report.toString(2))
            flush()
            try {
                withTimeout(240_000) {
                    check(File(app.filesDir, "backend-live-baseline/active-platform").exists())
                    until { app.playback.state.value.connected && app.accounts.states.value.values.none { it.phase == AccountPhase.REVALIDATING } }
                    when (step) {
                        "batch2_restore_prepare" -> prepareRestore(app, report)
                        "batch2_restore_check" -> checkRestore(app, report)
                        "batch2_restore_race" -> race(app, report)
                        "batch2_stop_kugou" -> stopDuringFill(app, report)
                        else -> read(app, requireNotNull(platform), report, ::flush)
                    }
                }
                report.put("status", "complete")
            } catch (error: Exception) {
                report.put("status", "failed").put("errorType", error.javaClass.simpleName)
                    .put("category", (error as? LibraryPageFailure)?.category)
                    .put("queueError", app.queuePlayback.state.value.error)
            } finally {
                app.playback.pause()
                report.put("finishedAt", System.currentTimeMillis()); flush(); running = false
            }
        }
        return true
    }

    private fun fetch(app: MusxiApplication, p: Platform): LibraryPageFetch = when (p) {
        Platform.NETEASE -> app.library; Platform.QQ -> app.qqLibrary; Platform.KUGOU -> app.kugouLibrary
    }
    private suspend fun read(app: MusxiApplication, p: Platform, r: JSONObject, flush: () -> Unit) {
        check(app.accounts.states.value.getValue(p).phase == AccountPhase.CONNECTED)
        val api = fetch(app, p)
        r.put("platform", p.name).put("stage", "DIRECTORY"); flush()
        val lists = mutableListOf<PlaylistSummary>()
        var offset = 0
        do {
            val page = await<Page<PlaylistSummary>> { api.playlists(offset, it) }
            lists += page.items
            val next = page.nextOffset ?: break
            check(next > offset && next <= 20_000); offset = next
        } while (true)
        val selected = requireNotNull(lists.filter { it.trackCount in 1..5_000 }.maxByOrNull { it.trackCount })
        val known = await<Page<SongSummary>> { api.tracks(selected.key, 0, it) }.items
        check(known.isNotEmpty())
        r.put("directoryCount", lists.size).put("advertisedTracks", selected.trackCount)
            .put("knownCount", known.size).put("stage", "HEAD_AND_REMAINDER"); flush()
        val head = await<PlaylistQueueHead> { cb -> when (p) {
            Platform.NETEASE -> app.library.queueHead(selected.key, known.first().key, known, cb)
            Platform.QQ -> app.qqLibrary.queueHead(selected.key, known.first().key, known, cb)
            Platform.KUGOU -> app.kugouLibrary.queueHead(selected.key, known.first().key, known, cb)
        } }
        val assembled = (head.remainder.before + head.queue.songs + head.remainder.after).toMutableList()
        var requests = 0
        try {
            while (true) {
                val page = await<List<SongSummary>?> { head.remainder.loadNext(it) } ?: break
                assembled += page; requests++
                check(requests <= 201)
            }
        } finally { head.remainder.cancel() }
        r.put("assembledCount", assembled.size).put("remainderCallbacks", requests).put("stage", "FULL_REFERENCE"); flush()
        val full = await<PlaybackQueue> { cb -> when (p) {
            Platform.NETEASE -> app.library.queue(selected.key, known.first().key, cb)
            Platform.QQ -> app.qqLibrary.queue(selected.key, known.first().key, cb)
            Platform.KUGOU -> app.kugouLibrary.queue(selected.key, known.first().key, cb)
        } }
        check(assembled.map { it.key } == full.songs.map { it.key })
        r.put("exactOrderMatchesIndependentRead", true)
            .put("duplicateOccurrences", assembled.size - assembled.map { it.key }.distinct().size)
            .put("stage", "PRODUCTION_PLAYLIST"); flush()
        app.queuePlayback.playPlaylist(selected.key, known.first().key, known)
        until { !app.queuePlayback.state.value.loading }
        check(app.queuePlayback.state.value.error == null)
        until { app.playback.queueState.value.songs.size == assembled.size }
        check(app.playback.queueState.value.songs.map { it.key } == assembled.map { it.key })
        app.playback.pause()
        r.put("productionQueueExact", true).put("productionCount", app.playback.queueState.value.songs.size)
            .put("stage", "CLOSED_INVALIDATION"); flush()
        val catalog = app.playlists.catalog(p)
        catalog.open(); until { catalog.state.value.items.isNotEmpty() && !catalog.state.value.loading }
        val displayed = catalog.state.value.items
        check(displayed.isNotEmpty())
        catalog.cancel(); catalog.invalidate(requireNotNull(app.accounts.playbackIdentity(p)))
        check(catalog.state.value.items == displayed && !catalog.state.value.complete && catalog.state.value.nextOffset == null)
        r.put("closedInvalidationRetainsDisplay", true)
        catalog.open()
        check(catalog.state.value.items == displayed)
        until { !catalog.state.value.loading }
        check(catalog.state.value.error == null && catalog.state.value.items.isNotEmpty())
        catalog.cancel()
        r.put("reopenRefreshSucceeded", true)
    }

    private fun marker(app: MusxiApplication) = File(app.filesDir, "batch2-restore-marker.json")
    private suspend fun stopDuringFill(app: MusxiApplication, r: JSONObject) {
        val lists = await<Page<PlaylistSummary>> { app.kugouLibrary.playlists(0, it) }
        val selected = requireNotNull(lists.items.filter { it.trackCount > 100 }.maxByOrNull { it.trackCount })
        val known = await<Page<SongSummary>> { app.kugouLibrary.tracks(selected.key, 0, it) }.items
        app.queuePlayback.playPlaylist(selected.key, known.first().key, known)
        until { app.playback.queueState.value.songs.map { it.key }.contains(known.first().key) }
        val observedCount = app.playback.queueState.value.songs.size
        check(app.queuePlayback.state.value.loading && observedCount < selected.trackCount)
        app.queuePlayback.stop()
        until { app.playback.queueState.value.songs.isEmpty() }
        delay(6000)
        check(app.playback.queueState.value.songs.isEmpty() && !app.playback.state.value.playWhenReady &&
            !app.queuePlayback.state.value.loading)
        r.put("realNetworkPagination", true).put("countAtStop", observedCount)
            .put("advertisedTotal", selected.trackCount).put("noLateAppendOrPlayback", true)
    }
    private suspend fun sample(app: MusxiApplication): Pair<Platform, SavedPlaybackQueue> {
        until { app.playbackJournal.state.value.values.none { it.loading } }
        val p = Platform.entries.first { app.accounts.playbackIdentity(it)?.verified == true &&
            (app.playbackJournal.state.value.getValue(it).queue?.songs?.size ?: 0) >= 3 }
        val songs = requireNotNull(app.playbackJournal.state.value.getValue(p).queue).songs.take(3)
        return p to SavedPlaybackQueue(songs, 1, 12_345, "ALL", true, System.currentTimeMillis())
    }
    private suspend fun prepareRestore(app: MusxiApplication, r: JSONObject) {
        val (p, saved) = sample(app)
        await<Unit> { app.queuePlayback.restore(saved, it) }
        delay(1600)
        await<Unit> { app.playbackJournal.save(p, requireNotNull(app.accounts.playbackIdentity(p)).generation, saved, completed = it) }
        marker(app).writeText(JSONObject().put("platform", p.name).put("ids", JSONArray(saved.songs.map { it.key.id }))
            .put("index", saved.index).put("positionMs", saved.positionMs).toString())
        r.put("platform", p.name).put("sampleSaved", true).put("paused", !app.playback.state.value.playWhenReady)
    }
    private suspend fun checkRestore(app: MusxiApplication, r: JSONObject) {
        val m = JSONObject(marker(app).readText()); val p = Platform.valueOf(m.getString("platform"))
        until { !app.playbackJournal.state.value.getValue(p).loading }
        val stored = requireNotNull(app.playbackJournal.state.value.getValue(p).queue)
        val ids = m.getJSONArray("ids"); check(stored.songs.map { it.key.id } == List(ids.length()) { ids.getString(it) })
        check(stored.index == 1 && stored.positionMs == 12_345L && stored.repeatMode == "ALL" && stored.shuffleEnabled)
        app.playbackJournal.restore(p)
        until { !app.playbackJournal.state.value.getValue(p).restoring }
        delay(500)
        check(app.playbackJournal.state.value.getValue(p).error == null)
        val state = app.playback.state.value
        check(app.playback.queueState.value.songs.map { it.key } == stored.songs.map { it.key })
        check(state.queueIndex == 1 && kotlin.math.abs(state.positionMs - 12_345) < 500 &&
            state.repeatMode.name == "ALL" && state.shuffleEnabled && !state.playWhenReady)
        r.put("persistedAcrossProcess", true).put("restoredPaused", true).put("indexPositionModesExact", true)
    }
    private suspend fun race(app: MusxiApplication, r: JSONObject) {
        val (_, saved) = sample(app)
        fun delayAck() {
            val method = app.playback.javaClass.declaredMethods.single { it.name.startsWith("validationLateAckDisconnect") }
            method.isAccessible = true; check(method.invoke(app.playback) == true)
        }
        delayAck()
        var obsoleteCallbacks = 0
        app.queuePlayback.restore(saved) { obsoleteCallbacks++ }
        app.queuePlayback.stop()
        delay(4200)
        check(app.playback.queueState.value.songs.isEmpty() && !app.playback.state.value.playWhenReady && obsoleteCallbacks == 0)
        r.put("delayedRestoreAckCannotUndoStop", true)
        delayAck()
        app.queuePlayback.restore(saved) { obsoleteCallbacks++ }
        app.queuePlayback.playSong(saved.songs.last())
        until { !app.queuePlayback.state.value.loading }
        app.playback.pause(); delay(4200)
        check(app.playback.queueState.value.songs.map { it.key } == listOf(saved.songs.last().key) && obsoleteCallbacks == 0)
        r.put("delayedRestoreAckCannotReplaceNewPlay", true).put("raceMode", "DELAYED_CONTROLLER_ACK_REAL_SERVICE")
        app.queuePlayback.stop()
    }

    private suspend fun until(predicate: () -> Boolean) = withTimeout(90_000) { while (!predicate()) delay(100) }
    private suspend fun <T> await(block: ((Result<T>) -> Unit) -> Unit): T =
        suspendCancellableCoroutine { c -> block { if (c.isActive) c.resumeWith(it) } }
}
