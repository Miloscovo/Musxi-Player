package tech.musxi.player

import android.content.Context
import tech.musxi.accounts.LyricsRepository
import tech.musxi.core.library.SongSummary
import tech.musxi.core.library.LibraryPageFailure

/** Bounded search for a playable bilingual sample. No changes to source authorization. */
internal object KugouPlaybackValidationRunner {
    private var revision = 0L
    private var selected: SongSummary? = null
    private var candidates = emptyList<SongSummary>()
    private var tried = 0
    private val failures = linkedMapOf<String, Int>()

    fun run(app: MusxiApplication, step: String): Boolean {
        if (!step.startsWith("kg_")) return false
        val diag = app.getSharedPreferences("kugou-playback-validation", Context.MODE_PRIVATE)
        if (step == "kg_play") { selected?.let { app.queuePlayback.playSong(it) }; return true }
        if (step == "kg_cancel") { ++revision; return true }
        val query = when (step) {
            "kg_find_lemon" -> "Lemon"
            "kg_find_grace" -> "Amazing Grace"
            "kg_find_yesterday" -> "Yesterday"
            "kg_find_mylove" -> "My Love"
            else -> return true
        }
        val token = ++revision
        selected = null; candidates = emptyList(); tried = 0; failures.clear()
        diag.edit().clear().putString("step", step).putString("status", "SEARCHING").apply()
        app.kugouLibrary.search(query) { response ->
            if (token != revision) return@search
            response.onSuccess { rows ->
                candidates = rows.take(6)
                diag.edit().putInt("candidates", candidates.size).apply()
                next(app, token)
            }.onFailure { diag.edit().putString("status", "SEARCH_FAILED").apply() }
        }
        return true
    }

    private fun next(app: MusxiApplication, token: Long) {
        if (token != revision) return
        val diag = app.getSharedPreferences("kugou-playback-validation", Context.MODE_PRIVATE)
        val song = candidates.getOrNull(tried++)
        if (song == null) { diag.edit().putString("status", "NO_PLAYABLE_BILINGUAL_SAMPLE").apply(); return }
        diag.edit().putString("status", "CHECKING_AUDIO").putInt("tried", tried).apply()
        app.kugouLibrary.resolveAudio(song.key) { audio ->
            if (token != revision) return@resolveAudio
            if (audio.isFailure) {
                val category = (audio.exceptionOrNull() as? LibraryPageFailure)?.category?.takeIf {
                    it in setOf("permission", "network", "timeout", "expired", "protocol", "unavailable", "platform", "http", "source_expired", "risk", "rate")
                } ?: "OTHER"
                failures[category] = (failures[category] ?: 0) + 1
                diag.edit().putInt("failed_$category", failures.getValue(category)).apply()
                next(app, token)
            } else LyricsRepository(app.accounts).fetch(song.key) { lyrics ->
                if (token != revision) return@fetch
                val parsed = lyrics.getOrNull()
                val count = parsed?.lines?.count { !it.translation.isNullOrBlank() } ?: 0
                if (count > 0) {
                    selected = song
                    diag.edit().putString("status", "READY").putInt("lines", parsed!!.lines.size)
                        .putInt("translated", count).putBoolean("audioResolved", true).apply()
                } else {
                    diag.edit().putInt("lastLines", parsed?.lines?.size ?: 0).putInt("lastTranslated", count).apply()
                    next(app, token)
                }
            }
        }
    }
}
