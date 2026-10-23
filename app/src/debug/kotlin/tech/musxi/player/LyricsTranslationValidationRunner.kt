package tech.musxi.player

import android.content.Context
import tech.musxi.accounts.LyricsRepository
import tech.musxi.core.account.Platform
import tech.musxi.core.library.LibraryPageFailure
import tech.musxi.core.library.SongSummary
import tech.musxi.core.lyrics.LrcParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Fixed read-only lyric probes. No keys, text, identities or capabilities enter diagnostics. */
internal object LyricsTranslationValidationRunner {
    private var revision = 0L
    private var selected: SongSummary? = null
    private var observer: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val events = java.util.ArrayDeque<String>()
    private var originalRows: List<Pair<Long, String>>? = null

    fun run(app: MusxiApplication, step: String): Boolean {
        if (!step.startsWith("lt_")) return false
        val diag = app.getSharedPreferences("lyrics-translation-validation", Context.MODE_PRIVATE)
        if (step == "lt_snapshot") { snapshot(app); return true }
        if (step in setOf("lt_staged_delay", "lt_translation_refresh", "lt_translation_fail_refresh")) {
            val debug = app as DebugMusxiApplication
            observer?.cancel(); events.clear(); originalRows = null
            val started = android.os.SystemClock.elapsedRealtime()
            diag.edit().clear().putString("step", step).apply()
            observer = scope.launch {
                app.lyrics.state.collect { state ->
                    if (originalRows == null && state.lines.isNotEmpty() &&
                        !(step == "lt_staged_delay" && state.translation.lookup.status != tech.musxi.core.lyrics.TranslationLookupStatus.PENDING))
                        originalRows = state.lines.map { it.timeMs to it.text }
                    val elapsed = android.os.SystemClock.elapsedRealtime() - started
                    val event = "$elapsed:${state.status}:${state.translation.lookup.status}:${state.lines.size}:${state.lines.count { it.translation != null }}"
                    if (events.lastOrNull()?.substringAfter(':') != event.substringAfter(':')) {
                        events.addLast(event); while (events.size > 16) events.removeFirst()
                    }
                    diag.edit().putString("stagedEvents", events.joinToString(";")).putBoolean("originalUnchanged",
                        originalRows != null && originalRows == state.lines.map { it.timeMs to it.text }).apply()
                    snapshot(app)
                }
            }
            if (step == "lt_staged_delay") { debug.translationDelayOnce = true; app.lyrics.refresh() }
            else {
                debug.translationDelayOnce = true
                debug.translationFailureOnce = step == "lt_translation_fail_refresh"
                app.lyrics.refreshTranslation()
            }
            // Bounded observer, even if the phone/user leaves this probe.
            val activeObserver = observer
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                activeObserver?.cancel(); if (observer === activeObserver) observer = null
            }, 20_000)
            return true
        }
        if (step == "lt_krc_inspect") {
            val song = selected?.takeIf { it.key.platform == Platform.KUGOU } ?: return true
            val token = revision
            tech.musxi.accounts.KrcValidationProbe(app.accounts).fetch(song.key.id) { response -> response.onSuccess { doc ->
                if (token == revision) diag.edit().putString("krcDiagnostic", doc.diagnostic).putString("krcStatus", doc.status)
                    .putInt("krcRows", doc.rows).putString("step", step).apply()
            } }
            return true
        }
        if (step == "lt_timings_clear") {
            app.accounts.clearRequestTimings()
            diag.edit().clear().putString("step", step).apply()
            snapshot(app)
            return true
        }
        if (step == "lt_play_sample") {
            selected?.let { app.queuePlayback.playSong(it) }
            diag.edit().putString("step", step).putBoolean("samplePresent", selected != null).apply()
            return true
        }
        if (step == "lt_pause") { app.playback.pause(); return true }
        if (step == "lt_lyrics_refresh") { app.lyrics.refresh(); return true }
        val query = when (step) {
            "lt_probe_lemon", "lt_probe_qq_lemon", "lt_probe_kugou_lemon" -> "Lemon 米津玄師" to "Lemon"
            "lt_probe_hello", "lt_probe_qq_hello", "lt_probe_kugou_hello" -> "Hello Adele" to "Hello"
            "lt_probe_qing", "lt_probe_qq_qing", "lt_probe_kugou_qing" -> "周杰伦 晴天" to "晴天"
            "lt_probe_kugou_mylove" -> "My Love Westlife" to "My Love"
            else -> return true
        }
        val token = ++revision
        val platform = when {
            step.startsWith("lt_probe_qq_") -> Platform.QQ
            step.startsWith("lt_probe_kugou_") -> Platform.KUGOU
            else -> Platform.NETEASE
        }
        selected = null
        diag.edit().clear().putString("step", step).putString("probePlatform", platform.name)
            .putString("probeStatus", "SEARCHING").apply()
        val callback: (Result<List<SongSummary>>) -> Unit = search@{ response ->
            if (token != revision) return@search
            response.onSuccess { songs ->
                val song = songs.firstOrNull { it.title.equals(query.second, ignoreCase = true) }
                selected = song
                diag.edit().putInt("searchCount", songs.size).putBoolean("exactTitle", song != null)
                    .putString("probeStatus", if (song == null) "NO_SAMPLE" else "LOADING").apply()
                if (song != null) LyricsRepository(app.accounts).fetch(song.key) lyrics@{ result ->
                    if (token != revision) return@lyrics
                    result.onSuccess { parsed ->
                        diag.edit().putString("probeStatus", "SUCCESS").putInt("probeLines", parsed.lines.size)
                            .putInt("probeTranslatedLines", parsed.lines.count { !it.translation.isNullOrBlank() })
                            .putString("probeTranslation", parsed.translation.availability.name)
                            .putString("probeLookup", parsed.translation.lookup.status.name)
                            .putString("probeReason", parsed.translation.lookup.reason?.name ?: "NONE")
                            .putBoolean("probeTimelineOrdered", parsed.lines.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs })
                            .putBoolean("probeTranslationPresent", parsed.lines.any { !it.translation.isNullOrBlank() }).apply()
                        snapshot(app)
                    }.onFailure { failure(app, it) }
                }
            }.onFailure { failure(app, it) }
        }
        if (platform == Platform.QQ) app.qqLibrary.search(query.first, callback)
        else if (platform == Platform.KUGOU) app.kugouLibrary.search(query.first, callback)
        else app.library.search(query.first, callback)
        return true
    }

    private fun failure(app: MusxiApplication, error: Throwable) {
        val category = (error as? LibraryPageFailure)?.category?.takeIf {
            it in setOf("network", "timeout", "http", "expired", "protocol", "limit", "unavailable", "account_changed")
        } ?: "OTHER"
        app.getSharedPreferences("lyrics-translation-validation", Context.MODE_PRIVATE).edit()
            .putString("probeStatus", "ERROR").putString("probeError", category).apply()
        snapshot(app)
    }

    private fun snapshot(app: MusxiApplication) {
        val playback = app.playback.state.value
        val lyrics = app.lyrics.state.value
        val edit = app.getSharedPreferences("lyrics-translation-validation", Context.MODE_PRIVATE).edit()
            .putString("connectionPhase", playback.connectionPhase.name)
            .putInt("connectionAttempt", playback.connectionAttempt)
            .putBoolean("connected", playback.connected).putBoolean("playing", playback.playing)
            .putBoolean("wanted", playback.playWhenReady).putBoolean("buffering", playback.buffering)
            .putLong("positionMs", playback.positionMs).putInt("queueSize", playback.queueSize)
            .putString("failure", playback.failure?.name ?: "NONE")
            .putString("queuePlatform", playback.queuePlatform?.name ?: "NONE")
            .putString("sourcePlatform", playback.sourcePlatform?.name ?: "NONE")
            .putInt("sourceResolveCalls", (app as DebugMusxiApplication).validationSources.resolveCount)
            .putInt("injectedFailures", app.validationSources.injectedCount)
            .putString("lyricsStatus", lyrics.status.name).putString("lyricsError", lyrics.error ?: "NONE")
            .putInt("lyricLines", lyrics.lines.size).putInt("translatedLines", lyrics.lines.count { !it.translation.isNullOrBlank() })
            .putInt("activeIndex", lyrics.activeIndex)
            .putBoolean("activeTranslationPresent", lyrics.lines.getOrNull(lyrics.activeIndex)?.translation != null)
            .putString("translation", lyrics.translation.availability.name)
            .putString("translationLookup", lyrics.translation.lookup.status.name)
            .putString("translationReason", lyrics.translation.lookup.reason?.name ?: "NONE")
            .putBoolean("canRefreshTranslation", lyrics.canRefreshTranslation)
            .putBoolean("lyricsSourceMatches", lyrics.sourceKey != null && lyrics.sourceKey == playback.sourceKey)
            .putBoolean("lyricIndexMatches", lyrics.activeIndex == LrcParser.activeIndex(lyrics.lines, playback.positionMs))
        Platform.entries.forEach { edit.putString("${it.name}_phase", app.accounts.states.value.getValue(it).phase.name) }
        app.accounts.requestTimingSnapshot().forEach { metric ->
            val prefix = "${metric.platform.name}_${metric.operation.name}"
            edit.putLong("${prefix}_count", metric.count).putLong("${prefix}_completed", metric.completed)
                .putLong("${prefix}_success", metric.completedSuccess).putLong("${prefix}_failed", metric.completedFailed)
                .putLong("${prefix}_rejected", metric.completedRejected).putLong("${prefix}_stale", metric.completedStale)
                .putLong("${prefix}_queueTotal", metric.queueTotalMs).putLong("${prefix}_queueMax", metric.queueMaxMs)
                .putLong("${prefix}_workTotal", metric.workTotalMs).putLong("${prefix}_workMax", metric.workMaxMs)
                .putLong("${prefix}_deliveryTotal", metric.deliveryTotalMs).putLong("${prefix}_deliveryMax", metric.deliveryMaxMs)
        }
        edit.apply()
    }
}
