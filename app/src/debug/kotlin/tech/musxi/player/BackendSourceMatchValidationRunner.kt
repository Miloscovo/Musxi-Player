package tech.musxi.player

import android.content.Context
import tech.musxi.core.account.Platform
import tech.musxi.core.playback.*
import tech.musxi.core.library.SongSummary

/** Read-only real search, assessed in an isolated selection; never starts playback or its timer. */
internal object BackendSourceMatchValidationRunner {
    private var revision = 0L
    private var phase = "NOT_RUN"
    private var original: SongSummary? = null
    private var candidates = emptyList<SourceCandidate>()
    private var automaticIndex: Int? = null
    private var partial = false

    fun run(app: MusxiApplication, step: String): Boolean {
        if (step !in setOf("b4_probe_aizo", "b4_snapshot", "b4_cancel")) return false
        if (step == "b4_cancel") { ++revision; phase = "CANCELLED" }
        if (step == "b4_probe_aizo") {
            val token = ++revision
            phase = "SEARCH_ORIGINAL"; original = null; candidates = emptyList(); automaticIndex = null; partial = false
            app.qqLibrary.search("AIZO") { response ->
                if (token != revision) return@search
                val song = response.getOrNull()?.firstOrNull { SourceSelection.sameTitle("AIZO", it.title) }
                if (song == null) { phase = "ORIGINAL_UNAVAILABLE"; publish(app); return@search }
                original = song; phase = "SEARCH_ALTERNATIVES"; publish(app)
                app.findAlternativeSources(song) { result ->
                    if (token != revision) return@findAlternativeSources
                    result.onSuccess { sources ->
                        val selection = SourceSelection { 0L }
                        val key = selection.begin(song, {}, {})
                        selection.offer(key, sources)
                        candidates = selection.state.value.candidates
                        automaticIndex = selection.state.value.automaticIndex
                        partial = sources.partial
                        phase = "COMPLETE"
                        publish(app)
                    }.onFailure { phase = "SEARCH_FAILED"; publish(app) }
                }
            }
        }
        publish(app)
        return true
    }

    private fun publish(app: MusxiApplication) {
        val song = original
        val reasons = if (song == null) emptyList() else candidates.map { SourceMatchPolicy.assess(song, it.song) }
        val edit = app.getSharedPreferences("backend-source-match-validation", Context.MODE_PRIVATE).edit().clear()
            .putLong("revision", revision).putString("phase", phase).putBoolean("readOnly", true)
            .putInt("candidateCount", candidates.size).putBoolean("partial", partial)
            .putInt("automaticIndex", automaticIndex ?: -1)
            .putBoolean("fiveSecondTimerEligible", automaticIndex != null)
            .putBoolean("originalArtistsKnown", song?.artists?.isNotEmpty() == true)
            .putBoolean("originalDurationKnown", (song?.durationMs ?: 0) > 0)
        SourceMatchReason.entries.forEach { edit.putInt("reason_${it.name}", reasons.count { reason -> reason == it }) }
        Platform.entries.forEach { platform -> edit.putInt("candidates_${platform.name}", candidates.count { it.song.key.platform == platform }) }
        edit.apply()
    }
}
