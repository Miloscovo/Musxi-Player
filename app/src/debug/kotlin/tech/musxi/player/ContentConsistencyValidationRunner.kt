package tech.musxi.player

import java.io.File
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*

/** Fixed, read-only content checks. No text, media keys, credentials or URLs in diagnostics. */
internal object ContentConsistencyValidationRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = false

    fun run(app: MusxiApplication, step: String): Boolean {
        if (step != "content_consistency") return false
        if (running) return true
        running = true
        scope.launch {
            val report = JSONObject().put("status", "running").put("started", System.currentTimeMillis())
            val file = File(app.filesDir, "$step.json")
            fun flush() = file.writeText(report.toString(2))
            val cases = JSONArray(); report.put("cases", cases); flush()
            try {
                check(File(app.filesDir, "backend-live-baseline/active-platform").exists())
                until { app.accounts.states.value.values.none { it.phase == AccountPhase.REVALIDATING } }
                until { app.playback.state.value.connected }
                val queue = app.playback.queueState.value
                val owners = Platform.entries.associateWith { app.accounts.playbackIdentity(it) }
                for (p in Platform.entries) {
                    val row = JSONObject().put("platform", p.name).put("stage", "SEARCH")
                    cases.put(row); flush()
                    try {
                        withTimeout(150_000) {
                            check(app.accounts.states.value.getValue(p).phase == AccountPhase.CONNECTED)
                            app.searchBrowser.search(p, "Da Capo")
                            app.searchBrowser.search(p, "SAKURA")
                            until { !app.searchBrowser.state.value.loading }
                            val search = app.searchBrowser.state.value
                            check(search.error == null && search.query == SearchQuery(p, "SAKURA"))
                            val songs = requireNotNull(search.page).items
                            check(songs.isNotEmpty() && songs.all { it.key.platform == p })
                            row.put("searchCount", songs.size).put("latestSearchAccepted", true)
                                .put("searchPlatformConsistent", true)
                            app.searchBrowser.search(p, "Da Capo")
                            app.searchBrowser.cancel()
                            val cancelled = app.searchBrowser.state.value
                            delay(700)
                            check(app.searchBrowser.state.value == cancelled && !cancelled.loading)
                            row.put("searchCancelStable", true).put("stage", "LYRICS_MATCH"); flush()

                            app.lyricsMatch.follow(songs.first().key)
                            app.lyricsMatch.search(p, "SAKURA", 1)
                            until { !app.lyricsMatch.state.value.searching }
                            val match = app.lyricsMatch.state.value
                            check(match.error == null && match.candidates.isNotEmpty() &&
                                match.candidates.all { it.key.platform == p })
                            row.put("candidateCount", match.candidates.size).put("candidatePlatformConsistent", true)
                            val candidate = match.candidates.first().key
                            app.lyricsMatch.preview(candidate)
                            until { !app.lyricsMatch.state.value.previewing }
                            val preview = app.lyricsMatch.state.value
                            row.put("previewError", preview.error ?: "NONE")
                            check(preview.error == null && preview.preview != null && preview.previewKey == candidate)
                            row.put("previewSourceConsistent", true).put("lyricRows", preview.preview!!.lines.size)
                            app.lyricsMatch.search(p, "Da Capo", 1)
                            app.lyricsMatch.cancel(); delay(700)
                            check(app.lyricsMatch.state.value.originalKey == null && !app.lyricsMatch.state.value.loading)
                            row.put("lyricsCancelStable", true).put("stage", "RECOMMENDATIONS"); flush()

                            row.put("recommendationsEnabled", app.settings.value.recommendationsEnabled)
                            if (app.settings.value.recommendationsEnabled) {
                                app.recommendations.open(p)
                                until { app.recommendations.state.value.getValue(p).let { !it.loading && !it.refreshing } }
                                val rec = app.recommendations.state.value.getValue(p)
                                row.put("recommendationError", rec.error ?: "NONE")
                                    .put("recommendationSource", rec.source?.name ?: "NONE")
                                val recommended = requireNotNull(rec.snapshot).songs
                                check(rec.error == null && recommended.isNotEmpty() && recommended.all { it.key.platform == p })
                                row.put("recommendationCount", recommended.size).put("recommendationPlatformConsistent", true)
                                app.recommendations.cancel(p)
                            }
                            row.put("stage", "COMPLETE")
                        }
                    } catch (e: Exception) {
                        row.put("stage", "FAILED").put("errorType", e.javaClass.simpleName)
                            .put("searchError", app.searchBrowser.state.value.error ?: "NONE")
                            .put("lyricsError", app.lyricsMatch.state.value.error ?: "NONE")
                    } finally {
                        app.searchBrowser.cancel(); app.lyricsMatch.cancel(); app.recommendations.cancel(p); flush()
                    }
                }
                check(queue == app.playback.queueState.value)
                check(owners.all { (p, owner) -> owner == app.accounts.playbackIdentity(p) })
                report.put("queueUnchanged", true).put("accountAttemptsUnchanged", true)
                report.put("status", if ((0 until cases.length()).all {
                    cases.getJSONObject(it).getString("stage") == "COMPLETE"
                }) "complete" else "failed")
            } catch (e: Exception) {
                report.put("status", "failed").put("errorType", e.javaClass.simpleName)
            } finally {
                app.playback.queueState.value.let { it.songs.getOrNull(it.currentIndex)?.key }?.let(app.lyricsMatch::follow)
                report.put("finished", System.currentTimeMillis()); flush(); running = false
            }
        }
        return true
    }

    private suspend fun until(predicate: () -> Boolean) = withTimeout(60_000) {
        while (!predicate()) delay(100)
    }
}
