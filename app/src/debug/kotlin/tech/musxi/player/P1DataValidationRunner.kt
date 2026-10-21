package tech.musxi.player

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.util.concurrent.Executor
import tech.musxi.core.account.Platform
import tech.musxi.core.lyrics.LrcParser
import tech.musxi.playback.MusxiPlaybackService

/** Fixed P1 data checks. Never serializes song keys, lyrics, account data or media URLs. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object P1DataValidationRunner {
    private val main = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var revision = 0L
    private var probeRevision = 0L
    private var probePlatform = "NONE"
    private var probeStatus = "IDLE"
    private var probeLines = 0
    private var probeError = "NONE"
    private var probeDiagnostic = "NONE"

    fun run(app: MusxiApplication, step: String): Boolean {
        if (!step.startsWith("p1data_")) return false
        val token = ++revision
        val diag = app.getSharedPreferences("p1-data-validation", Context.MODE_PRIVATE)
        fun capture(player: MediaController) {
            if (token != revision) return
            when (step) {
                "p1data_probe_lyrics" -> {
                    val key = app.searchBrowser.state.value.page?.items?.firstOrNull()?.key
                    val probeToken = ++probeRevision
                    probePlatform = key?.platform?.name ?: "NONE"
                    probeStatus = if (key == null) "MISSING_SEARCH" else "LOADING"
                    probeLines = 0; probeError = "NONE"; probeDiagnostic = "NONE"
                    if (key != null) tech.musxi.accounts.LyricsRepository(app.accounts).fetch(key) { response ->
                        if (probeToken == probeRevision) response.onSuccess {
                            probeLines = it.lines.size
                            probeStatus = if (it.lines.isNotEmpty()) "TIMED" else if (it.plainText.isBlank()) "EMPTY" else "PLAIN"
                        }.onFailure {
                            probeStatus = "ERROR"
                            probeError = (it as? tech.musxi.core.library.LibraryPageFailure)?.category
                                ?.takeIf { it in setOf("network", "timeout", "http", "expired", "protocol", "limit", "unavailable") } ?: "OTHER"
                            probeDiagnostic = (it as? tech.musxi.accounts.LyricsRepository.LyricsFailure)?.diagnostic ?: "NONE"
                        }
                    }
                }
                "p1data_refresh" -> Platform.entries.forEach(app.playbackJournal::refresh)
                "p1data_restore_netease" -> app.playbackJournal.restore(Platform.NETEASE)
                "p1data_restore_qq" -> app.playbackJournal.restore(Platform.QQ)
                "p1data_restore_kugou" -> app.playbackJournal.restore(Platform.KUGOU)
                "p1data_lyrics_refresh" -> app.lyrics.refresh()
                "p1data_seek_30" -> app.playback.seekTo(30_000)
                "p1data_seek_5" -> app.playback.seekTo(5_000)
            }
            main.postDelayed({
                if (token != revision) return@postDelayed
                val playback = app.playback.state.value
                val lyrics = app.lyrics.state.value
                val keys = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
                val edit = diag.edit().clear().putString("step", step).putLong("revision", token)
                    .putString("probeLyricsPlatform", probePlatform).putString("probeLyricsStatus", probeStatus)
                    .putInt("probeLyricsLines", probeLines).putString("probeLyricsError", probeError)
                    .putString("probeLyricsDiagnostic", probeDiagnostic)
                    .putInt("sourceResolveCalls", (app as DebugMusxiApplication).validationSources.resolveCount)
                    .putInt("injectedFailures", app.validationSources.injectedCount)
                    .putBoolean("connected", playback.connected).putBoolean("playing", playback.playing)
                    .putBoolean("wanted", playback.playWhenReady).putBoolean("buffering", playback.buffering)
                    .putInt("queueSize", playback.queueSize).putInt("queueIndex", playback.queueIndex)
                    .putLong("position", playback.positionMs).putString("repeatMode", playback.repeatMode.name)
                    .putBoolean("shuffle", playback.shuffleEnabled).putString("failure", playback.failure?.name ?: "NONE")
                    .putString("queuePlatform", playback.queuePlatform?.name ?: "NONE")
                    .putString("sourcePlatform", playback.sourcePlatform?.name ?: "NONE")
                    .putString("lyricsStatus", lyrics.status.name).putString("lyricsError", lyrics.error ?: "NONE")
                    .putInt("lyricLines", lyrics.lines.size).putInt("lyricActiveIndex", lyrics.activeIndex)
                    .putBoolean("lyricPlainPresent", lyrics.plainText.isNotEmpty())
                    .putBoolean("lyricsSourceMatches", lyrics.sourceKey != null && lyrics.sourceKey == playback.sourceKey)
                    .putBoolean("lyricIndexMatches", lyrics.activeIndex == LrcParser.activeIndex(lyrics.lines, playback.positionMs))
                Platform.entries.forEach { platform ->
                    val value = app.playbackJournal.state.value.getValue(platform)
                    val saved = value.queue
                    val prefix = platform.name.lowercase()
                    val recent = value.recent.firstOrNull()
                    edit.putBoolean("${prefix}Verified", value.accountVerified)
                        .putBoolean("${prefix}Loading", value.loading).putBoolean("${prefix}Restoring", value.restoring)
                        .putString("${prefix}Error", value.error ?: "NONE")
                        .putInt("${prefix}SavedSize", saved?.songs?.size ?: 0)
                        .putInt("${prefix}SavedIndex", saved?.index ?: -1)
                        .putLong("${prefix}SavedPosition", saved?.positionMs ?: -1)
                        .putString("${prefix}SavedRepeat", saved?.repeatMode ?: "NONE")
                        .putBoolean("${prefix}SavedShuffle", saved?.shuffleEnabled ?: false)
                        .putBoolean("${prefix}OrderMatches", saved != null && keys == saved.songs.map { it.key.stableId })
                        .putInt("${prefix}RecentCount", value.recent.size)
                        .putLong("${prefix}RecentTime", recent?.playedAtEpochMs ?: -1)
                        .putBoolean("${prefix}RecentOriginalMatches", recent != null && recent.original.key.stableId == playback.mediaId)
                        .putBoolean("${prefix}RecentSourceMatches", recent != null && recent.source.key == playback.sourceKey)
                        .putString("${prefix}RecentSourcePlatform", recent?.source?.key?.platform?.name ?: "NONE")
                }
                edit.apply()
            }, 400)
        }
        controller?.let { capture(it); return true }
        val future = MediaController.Builder(app, SessionToken(app,
            ComponentName(app, MusxiPlaybackService::class.java))).buildAsync()
        future.addListener({
            try { controller = future.get(); capture(controller!!) }
            catch (_: Exception) { diag.edit().putString("step", step).putBoolean("connected", false).apply() }
        }, Executor { main.post(it) })
        return true
    }
}
