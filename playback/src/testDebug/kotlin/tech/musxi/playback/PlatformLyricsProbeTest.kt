package tech.musxi.playback

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.PlatformSessionProbeAccess
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowMediaSession
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ProbeMediaSessionShadow::class])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlatformLyricsProbeTest {
    private lateinit var session: MediaSession
    private var probe: PlatformLyricsProbe? = null
    private val events = mutableListOf<Pair<String, JSONObject>>()
    private val original get() = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "probe:track")
        .putString(MediaMetadata.METADATA_KEY_TITLE, "原始标题")
        .putString(MediaMetadata.METADATA_KEY_ARTIST, "原始歌手")
        .putString(MediaMetadata.METADATA_KEY_ALBUM, "原始专辑")
        .putLong(MediaMetadata.METADATA_KEY_DURATION, 600_000)
        .build()

    @Before fun setup() {
        session = MediaSession(RuntimeEnvironment.getApplication(), "probe-test")
        session.setMetadata(original)
        shadowOf(session.controller).setPlaybackState(PlaybackState.Builder()
            .setState(PlaybackState.STATE_PLAYING, 60_000, 1f)
            .setActions(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SEEK_TO).build())
        session.isActive = true
    }

    @After fun cleanup() {
        probe?.stop("TEST_FINISHED")
        session.release()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun start(mode: PlatformLyricsProbe.Mode): PlatformLyricsProbe = PlatformLyricsProbe(
        session, mode, "tech.musxi.player.dev", 1, 60_000,
    ) { kind, data -> events += kind to data }.also { probe = it; it.start() }

    @Test fun a1WritesMinimalProtocolAndReadsItBackWithoutChangingPlayerMetadataOrActions() {
        val active = start(PlatformLyricsProbe.Mode.A1)
        val metadata = session.controller.metadata!!
        val readBack = JSONObject(metadata.getString(PlatformLyricsProbe.LYRIC_INFO)!!)
        assertTrue(active.snapshot().getBoolean("readBackMatches"))
        assertEquals("WRITE_READ_BACK_OK", events.first().first)
        assertFalse(metadata.containsKey(PlatformLyricsProbe.CONFIG))
        assertEquals("probe:track", readBack.getString("songId"))
        assertEquals("tech.musxi.player.dev", readBack.getString("provider"))
        assertEquals(0, readBack.getInt("lyricType"))
        assertFalse(readBack.getBoolean("noLyric"))
        assertTrue(readBack.getString("lyric").startsWith("[01:00.000]Musxi 锁屏探针 A1"))
        assertTrue(readBack.getString("rawLyric").contains("<01:01.500>"))
        assertTrue(readBack.getString("translationLyric").startsWith("[01:00.000]Probe A1"))
        assertEquals("原始标题", metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertEquals(600_000L, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
        assertEquals(PlaybackState.STATE_PLAYING, session.controller.playbackState!!.state)
        assertEquals(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SEEK_TO, session.controller.playbackState!!.actions)
    }

    @Test fun a2WritesConfigAndStopRestoresPriorKeysWhileKeepingLatestArtwork() {
        session.setMetadata(ProbeMetadata.overlay(original, mapOf(PlatformLyricsProbe.LYRIC_INFO to "previous", PlatformLyricsProbe.CONFIG to "previous-config")))
        val active = start(PlatformLyricsProbe.Mode.A2)
        assertEquals("1", session.controller.metadata!!.getString(PlatformLyricsProbe.CONFIG))
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff228844.toInt()) }
        session.setMetadata(ProbeMetadata.overlay(session.controller.metadata!!, mapOf(MediaMetadata.METADATA_KEY_ALBUM_ART to bitmap)))
        shadowOf(Looper.getMainLooper()).idle()
        active.stop("REQUESTED")
        val restored = session.controller.metadata!!
        assertEquals("previous", restored.getString(PlatformLyricsProbe.LYRIC_INFO))
        assertEquals("previous-config", restored.getString(PlatformLyricsProbe.CONFIG))
        assertEquals(0xff228844.toInt(), restored.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)!!.getPixel(0, 0))
        assertTrue(events.last().second.getBoolean("cleanupSucceeded"))
    }

    @Test fun a1RemovesConfigDuringTheControlAndRestoresItOnStop() {
        session.setMetadata(ProbeMetadata.overlay(original, mapOf(PlatformLyricsProbe.CONFIG to "original-config")))
        val active = start(PlatformLyricsProbe.Mode.A1)
        assertTrue(active.snapshot().getBoolean("readBackMatches"))
        assertFalse(session.controller.metadata!!.containsKey(PlatformLyricsProbe.CONFIG))
        active.stop("REQUESTED")
        assertEquals("original-config", session.controller.metadata!!.getString(PlatformLyricsProbe.CONFIG))
        assertFalse(session.controller.metadata!!.containsKey(PlatformLyricsProbe.LYRIC_INFO))
        assertTrue(shadowOf(session.controller).callbacks.isEmpty())
    }

    @Test fun media3ArtworkRewriteReattachesOnceAndOwnCallbackDoesNotLoop() {
        val active = start(PlatformLyricsProbe.Mode.A1)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, active.snapshot().getInt("writeCount"))
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "probe:track")
            .putString(MediaMetadata.METADATA_KEY_TITLE, "封面后新标题")
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap).build())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, active.snapshot().getInt("writeCount"))
        assertEquals(1, active.snapshot().getInt("reattachCount"))
        assertTrue(active.snapshot().getBoolean("readBackMatches"))
        assertEquals("封面后新标题", session.controller.metadata!!.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertNotNull(session.controller.metadata!!.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, active.snapshot().getInt("writeCount"))
    }

    @Test fun trackChangeStopsProbeAndDoesNotCopyOldLyricsOntoNextTrack() {
        val active = start(PlatformLyricsProbe.Mode.A2)
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "probe:next").build())
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(active.active)
        assertFalse(session.controller.metadata!!.containsKey(PlatformLyricsProbe.LYRIC_INFO))
        assertFalse(session.controller.metadata!!.containsKey(PlatformLyricsProbe.CONFIG))
        assertEquals("MEDIA_CHANGED", events.last().second.getString("reason"))
    }

    @Test fun nextTrackCannotInheritEitherTheProbeOrThePreviousTracksOriginalLyrics() {
        session.setMetadata(ProbeMetadata.overlay(original, mapOf(PlatformLyricsProbe.LYRIC_INFO to "old-original", PlatformLyricsProbe.CONFIG to "old-config")))
        val active = start(PlatformLyricsProbe.Mode.A2)
        session.setMetadata(ProbeMetadata.overlay(session.controller.metadata!!, mapOf(MediaMetadata.METADATA_KEY_MEDIA_ID to "probe:next")))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(active.active)
        assertFalse(session.controller.metadata!!.containsKey(PlatformLyricsProbe.LYRIC_INFO))
        assertFalse(session.controller.metadata!!.containsKey(PlatformLyricsProbe.CONFIG))
    }

    @Test fun timeLimitRemovesTheOverlayAndCallback() {
        val active = start(PlatformLyricsProbe.Mode.A2)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PlatformLyricsProbe.TIME_LIMIT_MS))
        assertFalse(active.active)
        assertFalse(session.controller.metadata!!.containsKey(PlatformLyricsProbe.LYRIC_INFO))
        assertFalse(session.controller.metadata!!.containsKey(PlatformLyricsProbe.CONFIG))
        assertEquals("TIME_LIMIT", events.last().second.getString("reason"))
    }

    @Test fun typedCopyPreservesBitmapRatingAndZeroLongAndRejectsUnknownOrOversizedPayload() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val rating = Rating.newHeartRating(true)
        val metadata = MediaMetadata.Builder().putBitmap("bitmap", bitmap).putRating("rating", rating)
            .putLong("zero", 0).putString("text", "kept").build()
        val copied = ProbeMetadata.overlay(metadata, mapOf(PlatformLyricsProbe.LYRIC_INFO to "synthetic"))
        assertNotNull(copied.getBitmap("bitmap"))
        assertTrue(copied.getRating("rating")!!.hasHeart())
        assertTrue(copied.containsKey("zero"))
        assertEquals(0, copied.getLong("zero"))
        val parcel = Parcel.obtain()
        val unsupported = try {
            parcel.writeBundle(Bundle().apply { putFloat("unknown", 1f) })
            parcel.setDataPosition(0)
            MediaMetadata.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
        assertThrows(IllegalStateException::class.java) { ProbeMetadata.overlay(unsupported, emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) {
            ProbeMetadata.overlay(metadata, mapOf(PlatformLyricsProbe.LYRIC_INFO to "X".repeat(512 * 1_024)))
        }
    }

    @Test fun packageHelperResolvesTheSameMedia3PlatformToken() {
        val context = RuntimeEnvironment.getApplication()
        val player = ExoPlayer.Builder(context).build()
        val media3 = androidx.media3.session.MediaSession.Builder(context, player).build()
        try {
            player.setMediaItem(MediaItem.Builder().setMediaId("probe:media3").setUri("file:///unused-probe.mp3").build())
            val platform = PlatformSessionProbeAccess.getPlatformSession(media3)
            assertEquals(platform.sessionToken, media3.platformToken)
        } finally { media3.release(); player.release() }
    }
}

/** Robolectric has no system-server binder linking MediaSession writes to MediaController. */
@Implements(MediaSession::class)
class ProbeMediaSessionShadow : ShadowMediaSession() {
    @RealObject private lateinit var realSession: MediaSession

    @Implementation
    protected fun setMetadata(metadata: MediaMetadata?) {
        val controller = shadowOf(realSession.controller)
        controller.setMetadata(metadata)
        Handler(Looper.getMainLooper()).post { controller.executeOnMetadataChanged(metadata) }
    }
}
