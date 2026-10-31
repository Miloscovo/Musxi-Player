package tech.musxi.player

import android.os.SystemClock
import androidx.core.content.edit
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*
import tech.musxi.core.playback.SavedPlaybackQueue
import tech.musxi.playback.QueueInsertAt

/** Shell-only fixed acceptance cases. Reports contain comparisons, never song IDs or streams. */
internal object BackendToolsAcceptanceRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var platform = Platform.KUGOU
    private var playlist: PlaylistSummary? = null
    private var known = emptyList<SongSummary>()
    private val saved = mutableMapOf<Platform, SavedPlaybackQueue?>()
    private val preferences = mutableMapOf<Platform, String?>()

    fun run(app: MusxiApplication, step: String): Boolean {
        if (step !in setOf("accept_prepare_kugou", "accept_prepare_qq", "accept_prepare_netease",
                "accept_queue", "accept_queue_edit", "accept_batch", "accept_cancel", "accept_quality",
                "accept_quality_scan", "accept_source_cancel", "accept_restore")) return false
        if (job?.isActive == true) return true
        val report = JSONObject().put("step", step).put("timestamp", System.currentTimeMillis()).put("status", "running")
        fun write() { File(app.filesDir, "$step.json").writeText(report.toString()) }
        write()
        job = scope.launch {
            try {
                withTimeout(120_000) {
                    when {
                        step.startsWith("accept_prepare_") -> {
                            platform = Platform.valueOf(step.substringAfterLast('_').uppercase())
                            playlist = null; known = emptyList()
                            withTimeout(20_000) { until {
                                app.accounts.playbackIdentity(platform)?.verified == true &&
                                    !app.playbackJournal.state.value.getValue(platform).loading
                            } }
                            if (!saved.containsKey(platform)) {
                                saved[platform] = app.playbackJournal.state.value.getValue(platform).queue
                                preferences[platform] = app.accounts.preferredQuality(platform)
                            }
                            val page = await<Page<PlaylistSummary>> { cb -> when (platform) {
                                Platform.KUGOU -> app.kugouLibrary.playlists(result = cb)
                                Platform.QQ -> app.qqLibrary.playlists(result = cb)
                                Platform.NETEASE -> app.library.playlists(result = cb)
                            } }
                            playlist = page.items.first { it.trackCount > 0 }
                            var offset: Int? = 0
                            repeat(3) {
                                val next = offset ?: return@repeat
                                val tracks = await<Page<SongSummary>> { cb -> when (platform) {
                                    Platform.KUGOU -> app.kugouLibrary.tracks(playlist!!.key, next, cb)
                                    Platform.QQ -> app.qqLibrary.tracks(playlist!!.key, next, cb)
                                    Platform.NETEASE -> app.library.tracks(playlist!!.key, next, cb)
                                } }
                                known = known + tracks.items
                                offset = tracks.nextOffset
                            }
                            report.put("knownCount", known.size).put("total", playlist!!.trackCount)
                        }
                        step == "accept_queue" || step == "accept_queue_edit" -> queue(app, report, step.endsWith("_edit"))
                        step == "accept_batch" -> batch(app, report)
                        step == "accept_cancel" -> cancel(app, report)
                        step == "accept_source_cancel" -> sourceCancel(app, report)
                        step == "accept_quality" -> quality(app, report)
                        step == "accept_quality_scan" -> {
                            val scanned = JSONArray()
                            report.put("songs", scanned)
                            for (song in known.take(6)) {
                                val response = awaitResult<tech.musxi.core.playback.QualityOptions> { cb -> when (platform) {
                                    Platform.KUGOU -> app.kugouLibrary.qualities(song.key, cb)
                                    Platform.QQ -> app.qqLibrary.qualities(song.key, cb)
                                    Platform.NETEASE -> app.library.qualities(song.key, cb)
                                } }
                                val row = JSONObject().put("index", scanned.length())
                                    .put("success", response.isSuccess)
                                    .put("options", JSONArray(response.getOrNull()?.options?.map { it.id }.orEmpty()))
                                scanned.put(row)
                                if ((response.getOrNull()?.options?.size ?: 0) > 1) {
                                    app.playback.pause(); delay(250); app.queuePlayback.playSong(song)
                                    until { !app.queuePlayback.state.value.loading }
                                    delay(500)
                                    until { app.playback.state.value.playing && app.playback.state.value.sourceKey == song.key }
                                    quality(app, report)
                                    break
                                }
                            }
                            if (!report.has("switchSuccess")) report.put("switch", "no_confirmed_alternate_in_sample")
                        }
                        step == "accept_restore" -> {
                            app.playback.pause(); app.queuePlayback.cancelPending()
                            preferences.forEach { (p, value) ->
                                // The exact original value (including absence) is restored in the app sandbox.
                                app.getSharedPreferences("playback-preferences-v1", 0).edit(commit = true) {
                                    if (value == null) remove("quality.${p.name.lowercase()}")
                                    else putString("quality.${p.name.lowercase()}", value)
                                }
                            }
                            saved.forEach { (p, value) -> app.accounts.playbackIdentity(p)?.let { identity ->
                                app.playbackJournal.save(p, identity.generation, value)
                            } }
                            val original = saved[Platform.KUGOU] ?: saved[platform]
                            if (original != null) await<Unit> { app.queuePlayback.restore(original, it) }
                            app.playback.pause()
                            report.put("restoredCount", app.playback.queueState.value.songs.size)
                                .put("paused", !app.playback.state.value.playWhenReady)
                        }
                    }
                }
                report.put("status", "complete")
            } catch (e: Exception) {
                report.put("status", "failed").put("errorType", e.javaClass.simpleName)
                report.put("category", (e as? LibraryPageFailure)?.category ?: "check_or_timeout")
            }
            report.put("platform", platform.name).put("finished", System.currentTimeMillis()); write()
        }
        return true
    }

    private suspend fun queue(app: MusxiApplication, report: JSONObject, edit: Boolean) {
        app.playback.pause(); delay(150)
        val selected = known[minOf(35, known.lastIndex)]
        val oldEpoch = app.playback.queueState.value.queueEpoch
        val start = SystemClock.elapsedRealtime()
        app.queuePlayback.playPlaylist(playlist!!.key, selected.key, known)
        until { app.playback.queueState.value.queueEpoch != oldEpoch }
        report.put("firstQueueMs", SystemClock.elapsedRealtime() - start)
            .put("firstQueueCount", app.playback.queueState.value.songs.size)
        val firstTicket = app.playback.currentQueueTicket()
        val removed = if (edit) app.playback.queueState.value.songs.first() else null
        var moved: SongSummary? = null
        if (edit) {
            val q = app.playback.queueState.value
            val accepted = awaitResult<Unit> { app.playback.removeQueueItem(0, q.revision, it) }
            report.put("removeAccepted", accepted.isSuccess)
            if (accepted.isFailure) throw IllegalStateException("remove_rejected")
            val next = app.playback.queueState.value
            moved = next.songs.first()
            val move = awaitResult<Unit> { app.playback.moveQueueItem(0, 1, next.revision, it) }
            report.put("moveAccepted", move.isSuccess)
            if (move.isFailure) throw IllegalStateException("move_rejected")
        }
        var firstPlaying: Long? = null
        var seeked = false
        var backwards = false
        var observedSource: MediaKey? = null
        var sourceChanged = false
        var previousPosition = 0L
        until {
            val s = app.playback.state.value
            if (s.playing && firstPlaying == null) firstPlaying = SystemClock.elapsedRealtime() - start
            if (s.playing && !seeked) { app.playback.seekTo(12_000); seeked = true }
            if (observedSource != null && s.sourceKey != observedSource) sourceChanged = true
            if (seeked && previousPosition > 10_000 && s.positionMs < previousPosition - 1000 && s.sourceKey == observedSource) backwards = true
            observedSource = s.sourceKey
            previousPosition = s.positionMs
            !app.queuePlayback.state.value.loading
        }
        report.put("completionMs", SystemClock.elapsedRealtime() - start)
        // Playback readiness can lag the final page on a fast connection.
        withTimeout(30_000) { until { app.playback.state.value.playing } }
        if (firstPlaying == null) firstPlaying = SystemClock.elapsedRealtime() - start
        report.put("playingMs", firstPlaying)
            .put("loadError", app.queuePlayback.state.value.error ?: JSONObject.NULL)
        val full = await<PlaybackQueue> { cb -> when (platform) {
            Platform.KUGOU -> app.kugouLibrary.queue(playlist!!.key, selected.key, cb)
            Platform.QQ -> app.qqLibrary.queue(playlist!!.key, selected.key, cb)
            Platform.NETEASE -> app.library.queue(playlist!!.key, selected.key, cb)
        } }
        val expected = full.songs.toMutableList()
        if (removed != null) expected.removeAt(expected.indexOfFirst { it.key == removed.key })
        if (moved != null) {
            val index = expected.indexOfFirst { it.key == moved.key }
            val value = expected.removeAt(index); expected.add(index + 1, value)
        }
        val q = app.playback.queueState.value
        val s = app.playback.state.value
        report.put("fullReadCount", full.songs.size).put("queueCount", q.songs.size)
            .put("orderMatches", expected.map { it.key } == q.songs.map { it.key })
            .put("slotMatches", q.songs.getOrNull(s.queueIndex)?.key == selected.key)
            .put("ticketStable", firstTicket == app.playback.currentQueueTicket())
            .put("positionBackwards", backwards).put("positionMs", s.positionMs)
            .put("sourceChangedWhileLoading", sourceChanged).put("sourcePlatform", s.sourcePlatform?.name)
            .put("playerCountMatches", s.queueSize == q.songs.size)
        check(report.getBoolean("orderMatches") && report.getBoolean("slotMatches") &&
            report.getBoolean("ticketStable") && !backwards && report.getBoolean("playerCountMatches"))
    }

    private suspend fun sourceCancel(app: MusxiApplication, report: JSONObject) {
        val p = app.playback
        val selected = known[minOf(35, known.lastIndex)]
        suspend fun search() {
            p.pause(); delay(150)
            app.queuePlayback.playSong(selected)
            withTimeout(20_000) { until {
                app.sourceSelection.state.value.phase == tech.musxi.core.playback.SourceSelectionPhase.SEARCHING
            } }
        }
        search()
        p.pause()
        delay(5_000)
        report.put("pauseFenced", !p.state.value.playing && !p.state.value.playWhenReady &&
            app.sourceSelection.state.value.phase == tech.musxi.core.playback.SourceSelectionPhase.NONE)
        check(report.getBoolean("pauseFenced"))
        search()
        p.stop()
        withTimeout(5_000) { until { p.queueState.value.songs.isEmpty() } }
        delay(15_000)
        report.put("stopFenced", p.queueState.value.songs.isEmpty() && !p.state.value.playing &&
            !p.state.value.playWhenReady &&
            app.sourceSelection.state.value.phase == tech.musxi.core.playback.SourceSelectionPhase.NONE)
        check(report.getBoolean("stopFenced"))
    }

    private suspend fun cancel(app: MusxiApplication, report: JSONObject) {
        val p = app.playback
        p.pause(); delay(100)
        val before = p.queueState.value.queueEpoch
        app.queuePlayback.playPlaylist(playlist!!.key, known[35].key, known)
        until { p.queueState.value.queueEpoch != before }
        val oldEpoch = p.queueState.value.queueEpoch
        app.queuePlayback.playSong(known.first())
        until { !app.queuePlayback.state.value.loading && p.queueState.value.queueEpoch != oldEpoch }
        delay(1500)
        report.put("replacementFenced", p.queueState.value.songs.map { it.key } == listOf(known.first().key))
        val stale = awaitResult<Unit> { p.enqueueBatch(known.take(2), QueueInsertAt.TAIL, "cancel-stale", oldEpoch, it) }
        report.put("oldEpochRejected", stale.isFailure && p.queueState.value.songs.size == 1)
        val replacementEpoch = p.queueState.value.queueEpoch
        app.queuePlayback.playPlaylist(playlist!!.key, known[35].key, known)
        until { p.queueState.value.queueEpoch != replacementEpoch }
        p.stop()
        until { p.queueState.value.songs.isEmpty() }
        delay(1500)
        report.put("stopFenced", p.queueState.value.songs.isEmpty() && !p.state.value.playWhenReady && !app.queuePlayback.state.value.loading)
        check(report.getBoolean("replacementFenced") && report.getBoolean("oldEpochRejected") && report.getBoolean("stopFenced"))
    }

    private suspend fun batch(app: MusxiApplication, report: JSONObject) {
        app.queuePlayback.cancelPending(); app.playback.pause()
        val p = app.playback
        val q = p.queueState.value
        val songs = known.take(2)
        val id = "debug-fixed-" + SystemClock.elapsedRealtime()
        await<Unit> { p.enqueueBatch(songs, QueueInsertAt.HEAD, id, q.queueEpoch, it) }
        val once = p.queueState.value
        await<Unit> { p.enqueueBatch(songs, QueueInsertAt.HEAD, id, q.queueEpoch, it) }
        report.put("idempotent", once == p.queueState.value)
        val before = p.queueState.value
        val stale = awaitResult<Unit> { p.enqueueBatch(songs, QueueInsertAt.TAIL, id + "old", "expired-epoch", it) }
        report.put("staleRejected", stale.isFailure && before == p.queueState.value)
        val cross = songs + known.first().copy(key = MediaKey(if (platform == Platform.QQ) Platform.KUGOU else Platform.QQ, "invalid"))
        val invalid = awaitResult<Unit> { p.enqueueBatch(cross, QueueInsertAt.TAIL, id + "cross", q.queueEpoch, it) }
        report.put("crossPlatformAtomic", invalid.isFailure && before == p.queueState.value)
        val staleEdit = awaitResult<Unit> { p.removeQueueItem(0, q.revision, it) }
        report.put("staleEditRejected", staleEdit.isFailure && before == p.queueState.value)
        val overflow = awaitResult<Unit> { p.enqueueBatch(List(20_001) { songs.first() }, QueueInsertAt.TAIL, id + "limit", q.queueEpoch, it) }
        report.put("overflowAtomic", overflow.isFailure && before == p.queueState.value)
        report.put("checksPass", listOf("idempotent", "staleRejected", "crossPlatformAtomic", "staleEditRejected", "overflowAtomic").all(report::getBoolean))
        check(report.getBoolean("checksPass"))
    }

    private suspend fun quality(app: MusxiApplication, report: JSONObject) {
        val p = app.playback
        p.resume(); until { p.state.value.playing }
        p.seekTo(20_000); delay(500)
        val options = await<tech.musxi.core.playback.QualityOptions> { p.qualities(callback = it) }
        report.put("options", JSONArray(options.options.map { it.id })).put("current", options.current)
            .put("sourcePlatform", p.state.value.sourcePlatform?.name)
        val original = options.current ?: error("unknown_current")
        val alternate = options.options.lastOrNull { it.id != original }
        if (alternate == null) { report.put("switch", "no_confirmed_alternate"); return }
        val ticket = p.currentQueueTicket(); val q = p.queueState.value; val source = p.state.value.sourceKey
        val before = p.state.value.positionMs; val start = SystemClock.elapsedRealtime()
        val changed = awaitResult<Unit> { p.setQuality(alternate.id, it) }
        val switchMs = SystemClock.elapsedRealtime() - start
        if (changed.isSuccess) withTimeout(10_000) { until { p.state.value.playing } }
        val queueAfter = p.queueState.value
        val queueStable = q.queueEpoch == queueAfter.queueEpoch && q.songs.map { it.key } == queueAfter.songs.map { it.key }
        report.put("switchSuccess", changed.isSuccess).put("switchMs", switchMs)
            .put("positionBefore", before).put("positionAfter", p.state.value.positionMs)
            .put("qualityName", p.state.value.qualityName).put("playing", p.state.value.playing)
            .put("playWhenReady", p.state.value.playWhenReady)
            .put("ticketStable", ticket == p.currentQueueTicket()).put("queueStable", queueStable)
            .put("metadataRefreshed", q.songs != queueAfter.songs)
            .put("sourceStable", source == p.state.value.sourceKey)
            .put("preferenceCommitted", app.accounts.preferredQuality(p.state.value.sourcePlatform ?: platform) == alternate.id)
        check(changed.isSuccess && p.state.value.positionMs >= before - 1000 &&
            queueStable && ticket == p.currentQueueTicket() && source == p.state.value.sourceKey)
        val invalid = awaitResult<Unit> { p.setQuality("invalid:quality", it) }
        report.put("invalidRejected", invalid.isFailure).put("invalidPreservesQuality", p.state.value.qualityName == alternate.name)
        p.pause(); delay(250)
        val pausedPosition = p.state.value.positionMs
        await<Unit> { p.setQuality(original, it) }
        report.put("pausedSwitch", !p.state.value.playWhenReady && kotlin.math.abs(p.state.value.positionMs - pausedPosition) <= 1000)
            .put("restoredQuality", p.state.value.qualityName)
        check(report.getBoolean("invalidRejected") && report.getBoolean("invalidPreservesQuality") && report.getBoolean("pausedSwitch"))
    }

    private suspend fun until(condition: () -> Boolean) { while (!condition()) delay(20) }
    private suspend fun <T> await(call: ((Result<T>) -> Unit) -> Unit): T = awaitResult(call).getOrThrow()
    private suspend fun <T> awaitResult(call: ((Result<T>) -> Unit) -> Unit): Result<T> =
        suspendCancellableCoroutine { continuation -> call { if (continuation.isActive) continuation.resume(it) } }
}
