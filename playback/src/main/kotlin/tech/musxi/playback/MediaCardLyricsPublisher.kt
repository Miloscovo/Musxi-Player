package tech.musxi.playback

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Handler
import androidx.media3.common.Player
import kotlin.math.ceil
import tech.musxi.core.lyrics.MediaCardLyricsTimeline

/** Main-thread platform-only title overlay. Never changes Media3 items, queue or playback commands. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class MediaCardLyricsPublisher(
    private val player: Player,
    private val session: MediaSession,
    private val main: Handler,
    private val sourceId: () -> String?,
) : AutoCloseable {
    private data class Owned(val mediaId: String, val original: Map<String, Any?>, val written: Map<String, String>)
    private var owned: Owned? = null
    private var state = MediaCardLyricsState()
    private var closed = false
    private val controller = session.controller
    private val boundary = Runnable { refresh() }
    private val metadataCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { refresh() }
        override fun onSessionDestroyed() { close() }
    }
    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { refresh() }
        override fun onEvents(player: Player, events: Player.Events) { refresh() }
    }

    init {
        checkThread()
        player.addListener(playerListener)
        controller.registerCallback(metadataCallback, main)
    }

    fun update(next: MediaCardLyricsState) {
        checkThread()
        if (closed || next == state) return
        state = next
        refresh()
    }

    private fun refresh() {
        checkThread()
        if (closed) return
        main.removeCallbacks(boundary)
        val item = player.currentMediaItem
        val valid = state.enabled && state.mediaId != null && state.mediaId == item?.mediaId &&
            state.sourceId != null && state.sourceId == sourceId() && state.lines.isNotEmpty() &&
            player.playerError == null && player.playbackState in setOf(Player.STATE_READY, Player.STATE_BUFFERING)
        val sample = if (valid) MediaCardLyricsTimeline.sample(state.lines, player.currentPosition, state.offsetMs) else null
        try {
            val current = controller.metadata
            val line = sample?.text
            if (line == null || current == null || current.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) != state.mediaId) {
                restore(current)
            } else {
                apply(current, state.mediaId!!, line)
            }
        } catch (_: Exception) {
            // Unsupported vendor value or oversized metadata: leave it intact, never rebuild the item.
            android.util.Log.w("MusxiMediaCardLyrics", "Platform metadata overlay skipped")
        }
        if (valid && player.isPlaying) {
            sample?.nextPositionMs?.let { next ->
                val speed = player.playbackParameters.speed.toDouble()
                if (speed.isFinite() && speed > 0) {
                    val delay = ceil((next - player.currentPosition).coerceAtLeast(1) / speed)
                        .toLong().coerceAtLeast(1)
                    main.postDelayed(boundary, delay)
                }
            }
        }
    }

    private fun apply(current: MediaMetadata, mediaId: String, line: String) {
        val values = PlatformMetadataOverlay.values(current)
        val old = owned?.takeIf { it.mediaId == mediaId }
        val originals = FIELDS.associateWith { key ->
            if (old != null && current.getText(key)?.toString() == old.written[key]) old.original[key] else values[key]
        }
        val title = (originals[MediaMetadata.METADATA_KEY_TITLE] as? CharSequence)?.toString().orEmpty()
        val artist = (originals[MediaMetadata.METADATA_KEY_ARTIST] as? CharSequence)?.toString().orEmpty()
        val subtitle = MediaCardLyricsTimeline.displayText(listOf(title, artist).filter(String::isNotBlank).joinToString(" · "))
        val fields = mapOf(MediaMetadata.METADATA_KEY_TITLE to line, MediaMetadata.METADATA_KEY_DISPLAY_TITLE to line,
            MediaMetadata.METADATA_KEY_ARTIST to subtitle, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE to subtitle)
        if (fields.all { (key, text) -> current.getText(key)?.toString() == text }) return
        val next = PlatformMetadataOverlay.replace(current, fields)
        owned = Owned(mediaId, originals, fields)
        session.setMetadata(next)
    }

    private fun restore(current: MediaMetadata?) {
        val old = owned ?: return
        if (current?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) != old.mediaId) {
            owned = null
            return
        }
        // An external title/artist update wins. Restore only values still equal to our last write.
        val fields = old.written.filter { (key, text) -> current.getText(key)?.toString() == text }
            .mapValues { (key, _) -> old.original[key] }
        if (fields.isNotEmpty()) session.setMetadata(PlatformMetadataOverlay.replace(current, fields))
        owned = null
    }

    override fun close() {
        checkThread()
        if (closed) return
        closed = true
        main.removeCallbacks(boundary)
        player.removeListener(playerListener)
        controller.unregisterCallback(metadataCallback)
        try { restore(controller.metadata) } catch (_: Exception) { /* Session may already be destroyed. */ }
    }

    private fun checkThread() = check(android.os.Looper.myLooper() == main.looper)

    private companion object {
        val FIELDS = listOf(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
            MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
    }
}
