package tech.musxi.player

import android.content.Context
import tech.musxi.core.account.Platform
import tech.musxi.core.diagnostics.RequestOperation
import tech.musxi.core.library.*
import tech.musxi.core.playback.SavedPlaybackQueue

/** Two distinct, actually resolvable Kugou items; fixed query, at most six probes. */
internal object BackendBackgroundValidationRunner {
    private var saved: SavedPlaybackQueue? = null
    private var second: SongSummary? = null
    private var phase = "NOT_RUN"
    private var token = 0L
    private var tried = 0
    private var baselineResolves = -1
    private var baselineAudio = -1L
    private var marked = false

    fun run(app: MusxiApplication, step: String): Boolean {
        if (step !in setOf("bg_setup", "bg_play", "bg_mark", "bg_snapshot", "bg_restore")) return false
        when (step) {
            "bg_setup" -> {
                val p = app.playback.state.value
                val songs = app.playback.queueState.value.songs
                if (p.playing || songs.size != 1 || songs.single().key.platform != Platform.KUGOU) {
                    phase = "PRECONDITION"; snapshot(app); return true
                }
                saved = SavedPlaybackQueue(songs.toList(), p.queueIndex, p.positionMs, p.repeatMode.name,
                    p.shuffleEnabled, System.currentTimeMillis())
                second = null; tried = 0; marked = false
                val epoch = ++token
                phase = "SEARCHING"
                app.kugouLibrary.search("Amazing Grace") { response ->
                    if (epoch != token) return@search
                    val rows = response.getOrNull()?.take(6)?.filter { it.key != songs.single().key && it.durationMs > 15_000 }
                    if (rows == null) { phase = "SEARCH_FAILED"; snapshot(app) }
                    else probe(app, epoch, rows)
                }
            }
            "bg_play" -> {
                val original = saved?.songs?.singleOrNull()
                val next = second
                val owner = app.accounts.playbackIdentity(Platform.KUGOU)
                if (original == null || next == null || owner?.verified != true) {
                    phase = "PRECONDITION"; snapshot(app); return true
                }
                app.playback.playQueue(PlaybackQueue(listOf(original, next), 0, owner.generation)) {
                    phase = if (it.isSuccess) "QUEUE_ACCEPTED" else "QUEUE_FAILED"; snapshot(app)
                }
            }
            "bg_mark" -> {
                val p = app.playback.state.value
                marked = p.playing && !p.buffering && p.queueIndex == 0 &&
                    p.sourceKey == saved?.songs?.singleOrNull()?.key && p.durationMs - p.positionMs in 1..10_000
                baselineResolves = (app as DebugMusxiApplication).validationSources.resolveCount
                baselineAudio = audioCount(app)
                phase = if (marked) "MARKED_BEFORE_TRANSITION" else "MARK_PRECONDITION"
            }
            "bg_restore" -> {
                ++token
                val previous = saved
                if (previous == null) { phase = "PRECONDITION"; snapshot(app); return true }
                app.queuePlayback.restore(previous) { phase = if (it.isSuccess) "RESTORED_PAUSED" else "RESTORE_FAILED"; snapshot(app) }
            }
        }
        snapshot(app)
        return true
    }

    private fun probe(app: MusxiApplication, epoch: Long, rows: List<SongSummary>) {
        if (epoch != token) return
        val song = rows.getOrNull(tried++)
        if (song == null) { phase = "NO_SECOND_PLAYABLE"; snapshot(app); return }
        phase = "PROBING_AUDIO"; snapshot(app)
        app.kugouLibrary.resolveAudio(song.key) {
            if (epoch != token) return@resolveAudio
            if (it.isSuccess) { second = song; phase = "READY"; snapshot(app) }
            else probe(app, epoch, rows)
        }
    }

    private fun audioCount(app: MusxiApplication) = app.accounts.requestTimingSnapshot()
        .firstOrNull { it.platform == Platform.KUGOU && it.operation == RequestOperation.AUDIO }?.count ?: 0L

    private fun snapshot(app: MusxiApplication) {
        val p = app.playback.state.value
        val songs = app.playback.queueState.value.songs
        val distinct = saved?.songs?.singleOrNull()?.key?.let { it != second?.key } == true && second != null
        val nextPlaying = distinct && p.playing && p.queueIndex == 1 && p.sourceKey == second?.key &&
            p.sourcePlatform == Platform.KUGOU && songs.getOrNull(1)?.key == second?.key
        val resolves = (app as DebugMusxiApplication).validationSources.resolveCount - baselineResolves
        val audio = audioCount(app) - baselineAudio
        app.getSharedPreferences("backend-background-validation", Context.MODE_PRIVATE).edit().clear()
            .putString("phase", phase).putBoolean("distinctSongs", distinct).putInt("probes", tried)
            .putBoolean("markedBeforeTransition", marked).putBoolean("playing", p.playing)
            .putBoolean("buffering", p.buffering).putInt("queueIndex", p.queueIndex).putInt("queueSize", p.queueSize)
            .putLong("positionMs", p.positionMs).putLong("durationMs", p.durationMs)
            .putString("sourcePlatform", p.sourcePlatform?.name ?: "NONE")
            .putString("failure", p.failure?.name ?: "NONE")
            .putBoolean("actualSecondPlaying", nextPlaying).putInt("newResolvesAfterMark", resolves)
            .putLong("newKugouAudioAfterMark", audio)
            .putBoolean("pass", marked && nextPlaying && resolves > 0 && audio > 0)
            .putBoolean("restored", phase == "RESTORED_PAUSED" && !p.playing && !p.playWhenReady && songs == saved?.songs)
            .apply()
    }
}
