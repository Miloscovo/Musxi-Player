package tech.musxi.playback

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import org.json.JSONObject
import java.util.Locale

/** Synthetic metadata only. No player commands, lyric fetching, settings or product UI. */
internal class PlatformLyricsProbe(
    private val session: MediaSession,
    val mode: Mode,
    provider: String,
    generation: Long,
    positionMs: Long,
    private val event: (String, JSONObject) -> Unit,
) {
    enum class Mode { A1, A2 }

    private val main = Handler(Looper.getMainLooper())
    private val controller = session.controller
    private val original = requireNotNull(controller.metadata) { "metadata_unavailable" }
    private val mediaId = requireNotNull(original.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)) { "media_id_unavailable" }
        .also { require(it.isNotEmpty()) { "media_id_unavailable" } }
    private val previousOwned = ProbeMetadata.values(original).filterKeys { it == LYRIC_INFO || it == CONFIG }
    private val startedAt = SystemClock.elapsedRealtime()
    val payload: String = probePayload(original, provider, generation, mode, positionMs)
    var active = false
        private set
    private var writes = 0
    private var callbacks = 0
    private var reattachments = 0
    private val expire = Runnable { stop("TIME_LIMIT") }
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            if (!active) return
            callbacks++
            // Read the latest value, not a queued callback snapshot that could predate our write.
            applyLatest("METADATA_CHANGED")
        }
        override fun onSessionDestroyed() { stop("SESSION_DESTROYED", restore = false) }
    }

    fun start() {
        checkMainThread()
        check(!active)
        active = true
        controller.registerCallback(callback, main)
        main.postDelayed(expire, TIME_LIMIT_MS)
        applyLatest("START")
    }

    private fun applyLatest(reason: String) {
        if (!active) return
        if (SystemClock.elapsedRealtime() - startedAt >= TIME_LIMIT_MS) return stop("TIME_LIMIT")
        val current = controller.metadata
        if (current?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) != mediaId) {
            return stop("MEDIA_CHANGED")
        }
        val expectedConfig = if (mode == Mode.A2) "1" else null
        if (current.getString(LYRIC_INFO) == payload && current.getString(CONFIG) == expectedConfig) return
        try {
            val next = ProbeMetadata.overlay(current, mapOf(LYRIC_INFO to payload, CONFIG to expectedConfig))
            session.setMetadata(next)
            writes++
            if (reason == "METADATA_CHANGED") reattachments++
            val readBack = snapshot()
            event(if (readBack.getBoolean("readBackMatches")) "WRITE_READ_BACK_OK" else "WRITE_READ_BACK_FAILED", readBack)
            if (!readBack.getBoolean("readBackMatches")) stop("READ_BACK_MISMATCH")
        } catch (_: Exception) {
            stop("METADATA_REJECTED")
        }
    }

    fun snapshot(): JSONObject {
        checkMainThread()
        if (active && SystemClock.elapsedRealtime() - startedAt >= TIME_LIMIT_MS) stop("TIME_LIMIT")
        val current = controller.metadata
        val readBack = current?.getString(LYRIC_INFO)
        return JSONObject()
            .put("mode", mode.name).put("active", active)
            .put("elapsedMs", SystemClock.elapsedRealtime())
            .put("mediaIdMatches", current?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) == mediaId)
            .put("readBackMatches", readBack == payload && current.getString(CONFIG) == (if (mode == Mode.A2) "1" else null))
            .put("lyricInfoPresent", readBack != null)
            .put("configReadBack", current?.getString(CONFIG) ?: JSONObject.NULL)
            .put("writeCount", writes).put("metadataCallbacks", callbacks).put("reattachCount", reattachments)
            .put("positionMs", controller.playbackState?.position ?: 0)
            .put("playbackState", controller.playbackState?.state ?: 0)
            .put("lyricInfoReadBack", readBack ?: JSONObject.NULL)
    }

    fun stop(reason: String, restore: Boolean = true) {
        checkMainThread()
        if (!active) return
        active = false
        main.removeCallbacks(expire)
        controller.unregisterCallback(callback)
        var cleaned = true
        if (restore) {
            try {
                val current = controller.metadata
                if (current?.getString(LYRIC_INFO) == payload) {
                    // Preserve the latest artwork/title/duration; restore only our two owned keys.
                    val sameItem = current.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) == mediaId
                    val replacements = mutableMapOf<String, Any?>(LYRIC_INFO to previousOwned[LYRIC_INFO].takeIf { sameItem })
                    if (current.getString(CONFIG) == if (mode == Mode.A2) "1" else null) {
                        replacements[CONFIG] = previousOwned[CONFIG].takeIf { sameItem }
                    }
                    session.setMetadata(ProbeMetadata.overlay(current, replacements))
                }
            } catch (_: Exception) { cleaned = false }
        }
        event("STOP", snapshot().put("reason", reason).put("cleanupSucceeded", cleaned))
    }

    companion object {
        const val LYRIC_INFO = "lyricInfo"
        const val CONFIG = "oplus.media.config"
        const val TIME_LIMIT_MS = 10 * 60_000L

        private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper())

        private fun probePayload(metadata: MediaMetadata, provider: String, generation: Long, mode: Mode, positionMs: Long): String {
            val base = positionMs.coerceAtLeast(0) / 5_000 * 5_000
            val lines = (0..120).map { index ->
                val time = base + index * 5_000L
                Triple(time, "Musxi 锁屏探针 ${mode.name} · 第 ${index + 1} 行", "Probe ${mode.name} · line ${index + 1}")
            }
            fun stamp(time: Long) = String.format(Locale.ROOT, "%02d:%02d.%03d", time / 60_000, time / 1_000 % 60, time % 1_000)
            return JSONObject()
                .put("songName", metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().take(1_024))
                .put("artist", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty().take(1_024))
                .put("album", metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty().take(1_024))
                .put("songId", metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID))
                .put("provider", provider).put("sessionGeneration", generation)
                .put("lyricType", 0).put("noLyric", false)
                .put("lyric", lines.joinToString("\n") { (time, text, _) -> "[${stamp(time)}]$text" })
                .put("rawLyric", lines.joinToString("\n") { (time, text, _) ->
                    "[${stamp(time)}]<${stamp(time)}>Musxi <${stamp(time + 1_500)}>锁屏探针 ${mode.name} <${stamp(time + 3_000)}>· ${text.substringAfter('·').trim()}<${stamp(time + 4_500)}>"
                })
                .put("translationLyric", lines.joinToString("\n") { (time, _, translation) -> "[${stamp(time)}]$translation" })
                .toString().also { require(it.length <= 128 * 1_024) { "payload_too_large" } }
        }
    }
}

/** Copy by runtime value type, including bitmaps; never copy signed MediaItem/stream extras. */
internal object ProbeMetadata {
    private const val MAX_BYTES = 512 * 1_024

    @Suppress("DEPRECATION")
    fun values(metadata: MediaMetadata): Map<String, Any?> {
        val parcel = Parcel.obtain()
        try {
            metadata.writeToParcel(parcel, 0)
            require(parcel.dataSize() <= MAX_BYTES) { "metadata_too_large" }
            parcel.setDataPosition(0)
            val bundle = requireNotNull(parcel.readBundle(MediaMetadata::class.java.classLoader))
            return bundle.keySet().associateWith { bundle.get(it) }
        } finally { parcel.recycle() }
    }

    fun overlay(metadata: MediaMetadata, replacements: Map<String, Any?>): MediaMetadata {
        val builder = MediaMetadata.Builder()
        (values(metadata) + replacements).forEach { (key, value) ->
            when (value) {
                null -> Unit
                is CharSequence -> builder.putText(key, value)
                is Long -> builder.putLong(key, value)
                is Bitmap -> builder.putBitmap(key, value)
                is Rating -> builder.putRating(key, value)
                else -> error("unsupported_metadata_type")
            }
        }
        return builder.build().also { values(it) } // Bound the complete platform parcel, not JSON alone.
    }
}
