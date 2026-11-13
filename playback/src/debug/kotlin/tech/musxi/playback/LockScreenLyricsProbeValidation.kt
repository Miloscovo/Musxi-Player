package tech.musxi.playback

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import androidx.media3.session.PlatformSessionProbeAccess
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Called only by the app's DUMP-protected debug ValidationReceiver. No shell payload is accepted. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object LockScreenLyricsProbeValidation {
    private const val TAG = "MusxiLockLyricsProbe"
    private var probe: PlatformLyricsProbe? = null
    private var generation = 0L
    private var events = ArrayDeque<JSONObject>()

    fun run(context: Context, step: String): Boolean {
        if (step !in setOf("lock_lyrics_a1", "lock_lyrics_a2", "lock_lyrics_status", "lock_lyrics_off")) return false
        check(Looper.myLooper() == Looper.getMainLooper())
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val app = context.applicationContext
        when (step) {
            "lock_lyrics_off" -> {
                probe?.stop("REQUESTED")
                record(app, "OFF", probe?.snapshot() ?: JSONObject().put("active", false))
                probe = null
            }
            "lock_lyrics_status" -> record(app, "STATUS", probe?.snapshot() ?: JSONObject().put("active", false))
            else -> {
                probe?.stop("RESTARTED")
                probe = null
                events = ArrayDeque()
                val service = MusxiPlaybackService.validationService?.get()
                val session = service?.sessions?.singleOrNull()
                if (session == null || session.player.currentMediaItem == null) {
                    record(app, "NOT_STARTED", JSONObject().put("active", false).put("reason", "PLAY_A_TRACK_FIRST"))
                    return true
                }
                try {
                    val platform = PlatformSessionProbeAccess.getPlatformSession(session)
                    val expectedId = session.player.currentMediaItem?.mediaId
                    if (platform.controller.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID) != expectedId) {
                        record(app, "NOT_STARTED", JSONObject().put("active", false).put("reason", "WAIT_FOR_PLATFORM_METADATA"))
                        return true
                    }
                    val mode = if (step == "lock_lyrics_a1") PlatformLyricsProbe.Mode.A1 else PlatformLyricsProbe.Mode.A2
                    probe = PlatformLyricsProbe(platform, mode, app.packageName, ++generation, session.player.currentPosition) { kind, snapshot ->
                        record(app, kind, snapshot)
                    }.also { it.start() }
                } catch (_: Exception) {
                    record(app, "NOT_STARTED", JSONObject().put("active", false).put("reason", "PLATFORM_BRIDGE_UNAVAILABLE"))
                }
            }
        }
        return true
    }

    private fun record(context: Context, kind: String, snapshot: JSONObject) {
        val logSnapshot = JSONObject(snapshot.toString()).apply {
            remove("lyricInfoReadBack")
            put("kind", kind)
        }
        // Log the read-back equality and a synthetic lyric excerpt, never a whole metadata bundle.
        val readBack = snapshot.optString("lyricInfoReadBack")
        val excerpt = runCatching { JSONObject(readBack).optString("lyric").take(100) }.getOrDefault("")
        logSnapshot.put("lyricReadBackPreview", excerpt)
        events.addLast(logSnapshot)
        while (events.size > 64) events.removeFirst()
        Log.i(TAG, logSnapshot.toString())
        val report = JSONObject().put("schemaVersion", 1).put("syntheticLyrics", true)
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("sdk", Build.VERSION.SDK_INT).put("osDisplay", Build.DISPLAY)
            .put("lastEvent", kind).put("state", snapshot)
            .put("events", JSONArray(events.toList()))
        val file = AtomicFile(File(context.filesDir, "lockscreen-lyrics-probe.json"))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(report.toString(2).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            Log.w(TAG, "Probe report could not be saved: ${error.javaClass.simpleName}")
        }
    }
}
