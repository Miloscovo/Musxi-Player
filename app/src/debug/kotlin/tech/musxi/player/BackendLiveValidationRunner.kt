package tech.musxi.player

import android.os.SystemClock
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import tech.musxi.core.account.*
import tech.musxi.core.library.*
import tech.musxi.core.playback.*

/** Fixed shell cases behind ValidationReceiver's DUMP permission. Real network, no URLs or sessions in reports. */
internal object BackendLiveValidationRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = false
    private fun home(app: MusxiApplication) = File(app.filesDir, "backend-live-baseline")
    private fun store(app: MusxiApplication, p: Platform) = FilePlaybackJournalStore(File(home(app), p.name), p)
    private fun write(app: MusxiApplication, step: String, report: JSONObject) {
        File(app.filesDir, "$step.json").writeText(report.toString(2))
    }

    fun run(app: MusxiApplication, step: String): Boolean {
        if (step !in setOf("live_snapshot", "live_prepare", "live_favorites", "live_cleanup",
                "live_read", "live_manual", "live_power_prepare", "live_play", "live_transition", "live_restore")) return false
        if (running) return true
        running = true
        scope.launch {
            val report = JSONObject().put("step", step).put("status", "running")
                .put("started", System.currentTimeMillis())
            fun flush() = write(app, step, report)
            flush()
            try {
                withTimeout(240_000) {
                    until { app.accounts.states.value.values.none { it.phase == AccountPhase.REVALIDATING } }
                    until { app.playback.state.value.connected }
                    when (step) {
                        "live_prepare" -> prepare(app, report)
                        "live_favorites" -> favorites(app, report, ::flush)
                        "live_cleanup" -> cleanup(app, report)
                        "live_read" -> read(app, report, ::flush)
                        "live_manual" -> manual(app, report, ::flush)
                        "live_power_prepare" -> powerPrepare(app, report, ::flush)
                        "live_play" -> {
                            app.playback.resume()
                            until { app.playback.state.value.playing }
                        }
                        "live_transition" -> {
                            check(app.playback.state.value.playing && app.playback.state.value.hasNext)
                            app.playback.seekTo((app.playback.state.value.durationMs - 12_000).coerceAtLeast(0))
                        }
                        "live_restore" -> restore(app, report)
                    }
                    snapshot(app, report)
                }
                report.put("status", "complete")
            } catch (e: Exception) {
                report.put("status", "failed").put("errorType", e.javaClass.simpleName)
                    .put("category", (e as? LibraryPageFailure)?.category ?: "check_or_timeout")
            } finally {
                report.put("finished", System.currentTimeMillis()); flush(); running = false
            }
        }
        return true
    }

    private fun snapshot(app: MusxiApplication, r: JSONObject) {
        val accounts = JSONArray()
        Platform.entries.forEach { p ->
            val a = app.accounts.states.value.getValue(p)
            val j = app.playbackJournal.state.value.getValue(p)
            val f = app.favorites.state.value.getValue(p)
            accounts.put(JSONObject().put("platform", p.name).put("phase", a.phase.name)
                .put("profilePresent", a.profile != null).put("savedQueueCount", j.queue?.songs?.size ?: 0)
                .put("favoritesSynced", f.synced).put("favoritesCount", f.keys.size).put("favoriteError", f.error))
        }
        val p = app.playback.state.value
        r.put("accounts", accounts).put("queueCount", app.playback.queueState.value.songs.size)
            .put("queueIndex", p.queueIndex).put("positionMs", p.positionMs).put("durationMs", p.durationMs)
            .put("playing", p.playing).put("wanted", p.playWhenReady).put("sourcePlatform", p.sourcePlatform?.name)
            .put("failure", p.failure?.name).put("transitions", p.automaticTransitions)
    }

    private suspend fun prepare(app: MusxiApplication, r: JSONObject) {
        check(!home(app).exists()) // Never replace an unfinished cleanup baseline.
        until { app.playbackJournal.state.value.values.none { it.loading } }
        home(app).mkdirs()
        Platform.entries.forEach { p -> app.accounts.playbackIdentity(p)?.let { identity ->
            val journal = await<PlaybackJournal> { app.accounts.playbackJournal(p, identity, null, it) }
            store(app, p).write(identity.owner, journal)
        } }
        val current = app.playback.state.value
        val songs = app.playback.queueState.value.songs
        val saved = if (songs.isNotEmpty()) SavedPlaybackQueue(songs, current.queueIndex, current.positionMs,
            current.repeatMode.name, current.shuffleEnabled, System.currentTimeMillis())
        else app.playbackJournal.state.value.values.mapNotNull { it.queue }.maxByOrNull { it.savedAtEpochMs }
        check(saved != null)
        val platform = saved.songs.first().key.platform
        val owner = requireNotNull(app.accounts.playbackIdentity(platform)).owner
        FilePlaybackJournalStore(File(home(app), "active"), platform).write(owner, PlaybackJournal(saved))
        File(home(app), "active-platform").writeText(platform.name)
        app.playback.pause()
        await<Unit> { app.queuePlayback.restore(saved, it) }
        app.playback.pause()
        r.put("restoredLatestSaved", songs.isEmpty()).put("baselineCount", saved.songs.size)
    }

    private suspend fun refresh(app: MusxiApplication, p: Platform) {
        until { app.favorites.state.value.getValue(p).let { !it.loading && it.pending == null && it.queuedKeys.isEmpty() } }
        app.favorites.refresh(p)
        until { !app.favorites.state.value.getValue(p).loading }
        val state = app.favorites.state.value.getValue(p)
        check(state.synced && state.error == null && state.canWrite)
    }

    private suspend fun favorites(app: MusxiApplication, r: JSONObject, flush: () -> Unit) {
        val p = Platform.KUGOU
        check(home(app).exists())
        check(!File(home(app), "favorites.json").exists())
        refresh(app, p)
        val identity = requireNotNull(app.accounts.playbackIdentity(p))
        val original = app.favorites.state.value.getValue(p).keys.toList()
        val found = await<List<SongSummary>> { app.kugouLibrary.search("周杰伦", it) }
        val songs = found.distinctBy { it.key }.filter { it.key !in original }.take(3)
        check(songs.size == 3)
        val journal = JSONObject().put("owner", identity.owner).put("original", JSONArray(original.map { it.id }))
            .put("songs", JSONArray(songs.map { song -> JSONObject().put("id", song.key.id)
                .put("title", song.title).put("duration", song.durationMs) }))
        File(home(app), "favorites.json").writeText(journal.toString()) // Durable cleanup before the first write.
        r.put("baselineCount", original.size).put("stage", "enqueue"); flush()
        app.accounts.clearRequestTimings()
        val started = SystemClock.elapsedRealtime()
        val responses = songs.mapIndexed { index, song ->
            val result = CompletableDeferred<Result<Unit>>()
            app.favorites.enqueueLiked(song, true) {
                r.put("confirm${index}Ms", SystemClock.elapsedRealtime() - started)
                    .put("confirm${index}Success", it.isSuccess)
                    .put("confirm${index}Error", (it.exceptionOrNull() as? LibraryPageFailure)?.category)
                flush(); result.complete(it)
            }
            result
        }
        r.put("enqueueMs", SystemClock.elapsedRealtime() - started)
            .put("waitingAfterEnqueue", app.favorites.state.value.getValue(p).queuedKeys.size); flush()
        try {
            responses.forEach { it.await().getOrThrow() }
            check(songs.all { it.key in app.favorites.state.value.getValue(p).keys })
            r.put("allThreeConfirmed", true)
            val metrics = JSONArray()
            app.accounts.requestTimingSnapshot().filter { it.platform == p }.forEach {
                metrics.put(JSONObject().put("operation", it.operation.name).put("count", it.count)
                    .put("success", it.completedSuccess).put("workTotalMs", it.workTotalMs)
                    .put("workMaxMs", it.workMaxMs).put("queueMaxMs", it.queueMaxMs))
            }
            r.put("metricsBeforeCleanup", metrics)
        } finally {
            r.put("stage", "cleanup"); flush()
            withContext(NonCancellable) { withTimeout(120_000) { cleanup(app, r) } }
        }
    }

    private suspend fun cleanup(app: MusxiApplication, r: JSONObject) {
        val file = File(home(app), "favorites.json")
        if (!file.exists()) { r.put("cleanup", "none_pending"); return }
        val saved = JSONObject(file.readText())
        val p = Platform.KUGOU
        check(app.accounts.playbackIdentity(p)?.owner == saved.getString("owner"))
        refresh(app, p)
        val array = saved.getJSONArray("songs")
        repeat(array.length()) { i ->
            val row = array.getJSONObject(i)
            val song = SongSummary(MediaKey(p, row.getString("id")), row.getString("title"), emptyList(), "", row.getLong("duration"))
            if (song.key in app.favorites.state.value.getValue(p).keys)
                await<Unit> { app.favorites.enqueueLiked(song, false, it) }
        }
        refresh(app, p)
        val baseline = saved.getJSONArray("original").let { list -> List(list.length()) { list.getString(it) } }
        val actual = app.favorites.state.value.getValue(p).keys.map { it.id }
        r.put("cleanupCount", actual.size).put("favoriteSetRestored", actual.toSet() == baseline.toSet())
            .put("favoriteOrderRestored", actual == baseline)
        check(actual == baseline)
        check(file.renameTo(File(home(app), "favorites-restored.json")))
    }

    private suspend fun read(app: MusxiApplication, r: JSONObject, flush: () -> Unit) {
        val lists = JSONArray(); r.put("writablePlaylists", lists)
        var kugouWritable: Set<MediaKey>? = null
        for (p in Platform.entries) {
            val response = awaitResult<List<EditablePlaylist>> { app.playlists.listWritable(p, it) }
            if (p == Platform.KUGOU) kugouWritable = response.getOrNull()?.map { it.playlist.key }?.toSet()
            lists.put(JSONObject().put("platform", p.name).put("success", response.isSuccess)
                .put("count", response.getOrNull()?.size ?: 0)
                .put("favoriteCount", response.getOrNull()?.count { it.favorite } ?: 0)
                .put("error", (response.exceptionOrNull() as? LibraryPageFailure)?.category)); flush()
        }
        val writable = requireNotNull(kugouWritable)
        val page = await<Page<PlaylistSummary>> { app.kugouLibrary.playlists(result = it) }
        val external = page.items.filter { it.key !in writable && it.trackCount > 0 }
        val collected = JSONObject().put("nonWritableCount", external.size)
        external.firstOrNull()?.let { playlist ->
            val tracks = await<Page<SongSummary>> { app.kugouLibrary.tracks(playlist.key, result = it) }
            collected.put("readCount", tracks.items.size).put("total", tracks.total)
        }
        r.put("collectedPlaylist", collected)
    }

    private suspend fun manual(app: MusxiApplication, r: JSONObject, flush: () -> Unit) {
        app.playback.resume(); until { app.playback.state.value.playing }
        app.playback.seekTo(30_000); delay(800)
        val queue = app.playback.queueState.value.songs.map { it.key }
        val ticket = app.playback.currentQueueTicket()
        val key = app.playback.state.value.sourceKey
        val beforePosition = app.playback.state.value.positionMs
        val progress = JSONArray(); r.put("progress", progress)
        await<Unit> { app.playback.findAlternativeSources(it) }
        until {
            val s = app.sourceSelection.state.value
            val snapshot = JSONObject().put("phase", s.phase.name).put("platforms", JSONArray(s.platformProgress.map {
                JSONObject().put("platform", it.platform.name).put("status", it.status.name).put("error", it.error)
            }))
            if (progress.length() == 0 || progress.getJSONObject(progress.length() - 1).toString() != snapshot.toString()) {
                progress.put(snapshot); flush()
            }
            s.phase != SourceSelectionPhase.SEARCHING
        }
        var selection = app.sourceSelection.state.value
        r.put("mode", selection.mode.name).put("candidateCount", selection.candidates.size)
            .put("searchKeptPlaying", app.playback.state.value.playing && app.playback.state.value.sourceKey == key)
            .put("searchKeptPosition", app.playback.state.value.positionMs >= beforePosition)
        flush()
        if (selection.candidates.isNotEmpty()) {
            val quality = awaitResult<QualityOptions> { app.inspectAlternativeSource(selection.revision, 0, it) }
            selection = app.sourceSelection.state.value
            r.put("qualitySuccess", quality.isSuccess).put("qualityCount", quality.getOrNull()?.options?.size ?: 0)
                .put("qualityLabel", selection.candidates.first().audio.quality.name)
            flush()
            check(app.sourceSelection.cancel(selection.revision))
            r.put("cancelKeptPlaying", app.playback.state.value.playing)
                .put("staleSelectionRejected", !app.sourceSelection.select(selection.revision, 0))
            flush()
            await<Unit> { app.playback.findAlternativeSources(it) }
            until { app.sourceSelection.state.value.phase != SourceSelectionPhase.SEARCHING }
            selection = app.sourceSelection.state.value
            if (selection.candidates.isNotEmpty()) {
                val chosen = selection.candidates.first().song.key
                val pos = app.playback.state.value.positionMs
                check(app.sourceSelection.select(selection.revision, 0))
                withTimeout(30_000) { until { app.playback.state.value.let {
                    (it.playing && it.sourceKey == chosen) || it.failure != null
                } } }
                r.put("selectionPlayed", app.playback.state.value.playing && app.playback.state.value.sourceKey == chosen)
                    .put("selectionFailure", app.playback.state.value.failure?.name)
                    .put("selectionKeptPosition", app.playback.state.value.positionMs >= pos - 1500)
                val failures = JSONObject()
                app.sourceSelection.state.value.playbackFailures.forEach { (p, f) -> failures.put(p.name, f.name) }
                r.put("playbackFailures", failures)
            }
        }
        r.put("queueStable", queue == app.playback.queueState.value.songs.map { it.key })
            .put("ticketStable", ticket == app.playback.currentQueueTicket())
        check(r.getBoolean("queueStable") && r.getBoolean("ticketStable"))
    }

    private suspend fun restore(app: MusxiApplication, r: JSONObject) {
        check(!File(home(app), "favorites.json").exists())
        app.playback.pause(); app.queuePlayback.cancelPending()
        val p = Platform.valueOf(File(home(app), "active-platform").readText())
        val identity = requireNotNull(app.accounts.playbackIdentity(p))
        val saved = requireNotNull(FilePlaybackJournalStore(File(home(app), "active"), p).read(identity.owner).queue)
        await<Unit> { app.queuePlayback.restore(saved, it) }
        app.playback.pause(); delay(1500)
        Platform.entries.forEach { platform -> app.accounts.playbackIdentity(platform)?.let { owner ->
            val baseline = store(app, platform).read(owner.owner)
            await<PlaybackJournal> { cb -> app.accounts.playbackJournal(platform, owner, { baseline }, cb) }
            app.playbackJournal.refresh(platform)
        } }
        r.put("originalQueueRestored", app.playback.queueState.value.songs.map { it.key } == saved.songs.map { it.key })
            .put("originalIndexRestored", app.playback.state.value.queueIndex == saved.index)
            .put("originalPositionRestored", kotlin.math.abs(app.playback.state.value.positionMs - saved.positionMs) < 1500)
            .put("repeatRestored", app.playback.state.value.repeatMode.name == saved.repeatMode)
            .put("shuffleRestored", app.playback.state.value.shuffleEnabled == saved.shuffleEnabled)
        check(!app.playback.state.value.playWhenReady && r.getBoolean("originalQueueRestored"))
    }

    private suspend fun powerPrepare(app: MusxiApplication, r: JSONObject, flush: () -> Unit) {
        val p = Platform.valueOf(File(home(app), "active-platform").readText())
        val identity = requireNotNull(app.accounts.playbackIdentity(p))
        val saved = requireNotNull(FilePlaybackJournalStore(File(home(app), "active"), p).read(identity.owner).queue)
        val playable = mutableListOf<SongSummary>()
        val scans = JSONArray(); r.put("qualityScan", scans)
        for (song in saved.songs.take(30)) {
            val options = awaitResult<QualityOptions> { cb -> when (p) {
                Platform.KUGOU -> app.kugouLibrary.qualities(song.key, cb)
                Platform.QQ -> app.qqLibrary.qualities(song.key, cb)
                Platform.NETEASE -> app.library.qualities(song.key, cb)
            } }
            scans.put(JSONObject().put("index", scans.length()).put("success", options.isSuccess)
                .put("options", options.getOrNull()?.options?.size ?: 0)); flush()
            if (options.getOrNull()?.options?.isNotEmpty() == true) playable += song
            if (playable.size == 3) break
        }
        check(playable.size >= 2)
        app.playback.pause()
        val testQueue = SavedPlaybackQueue(playable, 0, 0, "ALL", false, System.currentTimeMillis())
        await<Unit> { app.queuePlayback.restore(testQueue, it) }
        app.playback.resume()
        until { app.playback.state.value.playing }
        delay(10_000)
        check(app.playback.state.value.playing && app.playback.state.value.failure == null)
        r.put("testQueueCount", playable.size)
    }

    private suspend fun until(predicate: () -> Boolean) { while (!predicate()) delay(100) }
    private suspend fun <T> awaitResult(block: ((Result<T>) -> Unit) -> Unit): Result<T> =
        suspendCancellableCoroutine { cont -> block { if (cont.isActive) cont.resume(it) } }
    private suspend fun <T> await(block: ((Result<T>) -> Unit) -> Unit): T = awaitResult(block).getOrThrow()
}
