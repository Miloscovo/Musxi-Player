package tech.musxi.player

import android.content.Context
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*
import tech.musxi.core.playback.*
import tech.musxi.playback.*

/** Fixed real-stream cases; use live_prepare/live_restore to preserve account-owned journals. */
internal object PlaybackRequestValidationRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = false
    fun run(app: MusxiApplication, step: String): Boolean {
        val platform = when (step) {
            "request_play_netease" -> Platform.NETEASE
            "request_play_qq" -> Platform.QQ
            "request_play_kugou" -> Platform.KUGOU
            else -> return false
        }
        if (running) return true
        running = true
        scope.launch {
            val report = JSONObject().put("platform", platform.name).put("status", "running")
                .put("startedAtEpochMs", System.currentTimeMillis()).put("naturalCdnExpiryTested", false)
            val file = File(app.filesDir, "$step.json")
            fun flush() = file.writeText(report.toString(2))
            val prefs = app.getSharedPreferences("playback-preferences-v1", Context.MODE_PRIVATE)
            val prefKey = "quality.${platform.name.lowercase()}"
            val originalQuality = prefs.getString(prefKey, null)
            flush()
            try {
                withTimeout(150_000) {
                    check(File(app.filesDir, "backend-live-baseline/active-platform").exists())
                    check(app.playback.state.value.connected && !app.playback.state.value.playing)
                    val saved = app.playbackJournal.state.value.getValue(platform).queue
                    val song = saved?.songs?.firstOrNull() ?: run {
                        check(platform == Platform.QQ)
                        val lists = await<Page<PlaylistSummary>> { app.qqLibrary.playlists(result = it) }
                        val playlist = requireNotNull(lists.items.firstOrNull { it.trackCount > 0 })
                        val tracks = await<Page<SongSummary>> { app.qqLibrary.tracks(playlist.key, result = it) }
                        requireNotNull(tracks.items.firstOrNull())
                    }
                    report.put("sampleFrom", if (saved == null) "FIRST_NONEMPTY_PLAYLIST" else "SAVED_QUEUE")
                    report.put("stage", "REAL_PLAY"); flush()
                    app.accounts.rememberPreferredQuality(platform, when (platform) {
                        Platform.KUGOU -> "kugou:128"
                        Platform.QQ -> "qq:standard"
                        Platform.NETEASE -> "netease:standard"
                    })
                    app.queuePlayback.playSong(song)
                    until { app.playback.state.value.let { it.playing && it.sourceKey == song.key } }
                    val p0 = app.playback.state.value.positionMs
                    delay(2200)
                    report.put("realPlaying", app.playback.state.value.playing)
                        .put("positionAdvanced", app.playback.state.value.positionMs > p0 + 500)
                    check(report.getBoolean("realPlaying") && report.getBoolean("positionAdvanced"))
                    app.playback.pause(); delay(300)
                    val ticket = app.playback.currentQueueTicket()
                    val options = await<QualityOptions> { app.playback.qualities(callback = it) }
                    report.put("qualities", JSONArray(options.options.map { it.id }))
                    val selected = options.options.firstOrNull { it.id != options.current }
                    if (selected != null) {
                        report.put("stage", "QUALITY_SWITCH"); flush()
                        val position = app.playback.state.value.positionMs
                        await<Unit> { app.playback.setQuality(selected.id, it) }
                        delay(500)
                        report.put("selectedQuality", selected.id)
                            .put("qualitySwitchPaused", !app.playback.state.value.playWhenReady)
                            .put("qualityTicketStable", ticket == app.playback.currentQueueTicket())
                            .put("qualityPositionPreserved", kotlin.math.abs(app.playback.state.value.positionMs - position) < 1500)
                        check(report.getBoolean("qualitySwitchPaused") && report.getBoolean("qualityTicketStable") &&
                            report.getBoolean("qualityPositionPreserved"))
                        app.playback.resume()
                        until { app.playback.state.value.playing }
                        delay(2000)
                        report.put("qualityRealPlaying", app.playback.state.value.playing)
                        check(report.getBoolean("qualityRealPlaying"))
                        app.playback.pause()
                    } else report.put("qualitySwitch", "NO_SECOND_AVAILABLE_QUALITY")
                    // Service recovery budget under an injected source error, followed by a real platform stream.
                    val debug = app as DebugMusxiApplication
                    report.put("stage", "INJECTED_EXPIRY"); flush()
                    app.playback.stop(); delay(400)
                    debug.validationSources.arm("fault_expired_once")
                    app.queuePlayback.playSong(song)
                    until { app.playback.state.value.let { it.playing && it.sourceKey == song.key } }
                    delay(1500)
                    report.put("injectedExpiryCount", debug.validationSources.injectedCount)
                        .put("expiryResolveCount", debug.validationSources.resolveCount)
                        .put("expiryRecoveryRealPlaying", app.playback.state.value.playing)
                        .put("expiryServiceRefreshCount", app.playback.state.value.sourceRefreshCount)
                    app.playback.pause(); app.playback.stop(); delay(400)
                    debug.validationSources.arm("fault_permission_delayed")
                    app.queuePlayback.playSong(song)
                    until { debug.validationSources.resolveCount > 0 }
                    app.playback.stop()
                    delay(3500)
                    report.put("cancelNoLatePlayback", !app.playback.state.value.playing && !app.playback.state.value.playWhenReady)
                        .put("cancelQueueEmpty", app.playback.queueState.value.songs.isEmpty())
                        .put("cancelCase", "STOP_DURING_INJECTED_DELAY")
                    check(report.getBoolean("cancelNoLatePlayback") && report.getBoolean("cancelQueueEmpty"))
                    check(report.getInt("injectedExpiryCount") == 1 && report.getBoolean("expiryRecoveryRealPlaying"))
                    check(report.getInt("expiryResolveCount") == 2 && report.getInt("expiryServiceRefreshCount") == 1)
                }
                report.put("status", "complete")
            } catch (e: Exception) {
                report.put("status", "failed").put("errorType", e.javaClass.simpleName)
                    .put("failure", app.playback.state.value.failure?.name)
                    .put("category", (e as? LibraryPageFailure)?.category)
            } finally {
                (app as DebugMusxiApplication).validationSources.arm("reset")
                app.playback.pause()
                prefs.edit().apply { if (originalQuality == null) remove(prefKey) else putString(prefKey, originalQuality) }.commit()
                report.put("finishedAtEpochMs", System.currentTimeMillis()); flush(); running = false
            }
        }
        return true
    }
    private suspend fun until(predicate: () -> Boolean) = withTimeout(30_000) { while (!predicate()) delay(100) }
    private suspend fun <T> await(block: ((Result<T>) -> Unit) -> Unit): T =
        suspendCancellableCoroutine { c -> block { if (c.isActive) c.resumeWith(it) } }
}
