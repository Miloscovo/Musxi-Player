package tech.musxi.playback

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaSession
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import androidx.media3.common.FlagSet
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.PlatformSessionAccess
import java.lang.reflect.Proxy
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowMediaSession
import tech.musxi.core.lyrics.LyricLine

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [MediaCardSessionShadow::class])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class MediaCardLyricsPublisherTest {
    private class Fixture : AutoCloseable {
        var item = MediaItem.Builder().setMediaId("qq:queue").setUri("file:///unused.mp3").build()
        var source: String? = "netease:source"
        var playing = true
        var playbackState = Player.STATE_READY
        var parameters = PlaybackParameters.DEFAULT
        private var position = 0L
        private var sampledAt = SystemClock.elapsedRealtime()
        val listeners = mutableListOf<Player.Listener>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, args ->
            when (method.name) {
                "getCurrentMediaItem" -> item
                "getCurrentPosition" -> position + if (playing) ((SystemClock.elapsedRealtime() - sampledAt) * parameters.speed).toLong() else 0
                "getPlayerError" -> null
                "getPlaybackState" -> playbackState
                "isPlaying" -> playing
                "getPlaybackParameters" -> parameters
                "addListener" -> { listeners += args!![0] as Player.Listener; null }
                "removeListener" -> { listeners -= args!![0] as Player.Listener; null }
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "MediaCardPlayer"
                else -> throw AssertionError("Unexpected player command/read: ${method.name}")
            }
        } as Player
        val session = MediaSession(RuntimeEnvironment.getApplication(), "media-card-test")
        val original = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, item.mediaId)
            .putString(MediaMetadata.METADATA_KEY_TITLE, "原歌名")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "原歌手")
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "原专辑")
            .putString("lyricInfo", "unrelated-vendor-data")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 60_000).build()
        val publisher: MediaCardLyricsPublisher
        val state = MediaCardLyricsState(true, item.mediaId, source,
            listOf(LyricLine(1_000, "第一行"), LyricLine(3_000, "第二行"), LyricLine(5_000, ""), LyricLine(6_000, "尾行")))
        val metadata get() = session.controller.metadata!!
        val title get() = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val writes get() = Shadow.extract<MediaCardSessionShadow>(session).writes
        init {
            session.setMetadata(original)
            session.isActive = true
            publisher = MediaCardLyricsPublisher(player, session, Handler(Looper.getMainLooper())) { source }
        }
        fun events() { listeners.toList().forEach { it.onEvents(player, Player.Events(FlagSet.Builder().add(Player.EVENT_POSITION_DISCONTINUITY).build())) } }
        fun seek(value: Long) { position = value; sampledAt = SystemClock.elapsedRealtime(); events() }
        fun pause() { position = player.currentPosition; sampledAt = SystemClock.elapsedRealtime(); playing = false; events() }
        fun resume(speed: Float = 1f) { sampledAt = SystemClock.elapsedRealtime(); parameters = PlaybackParameters(speed); playing = true; events() }
        fun advance(ms: Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
        fun enable() { publisher.update(state); advance(0) }
        fun disable() { publisher.update(MediaCardLyricsState()); advance(0) }
        override fun close() { publisher.close(); session.release(); advance(0) }
    }

    @Test fun offByDefaultDoesNotRewriteAndLineChangesOnlyAtBoundariesWithoutAppTicks() {
        Fixture().use { f ->
            f.advance(2_000)
            assertEquals("原歌名", f.title)
            assertEquals(1, f.writes)
            f.seek(0)
            f.enable()
            f.advance(999)
            assertEquals("原歌名", f.title)
            f.advance(1)
            assertEquals("第一行", f.title)
            assertEquals("第一行", f.metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
            assertEquals("原歌名 · 原歌手", f.metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
            val writes = f.writes
            f.advance(1_999)
            assertEquals(writes, f.writes)
            f.advance(1)
            assertEquals("第二行", f.title)
            assertEquals("原专辑", f.metadata.getString(MediaMetadata.METADATA_KEY_ALBUM))
            assertEquals("unrelated-vendor-data", f.metadata.getString("lyricInfo"))
            assertEquals(60_000L, f.metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
        }
    }

    @Test fun pauseSeekSpeedBufferAndEndRecomputeFromPlayerPosition() {
        Fixture().use { f ->
            f.seek(1_500); f.enable(); f.pause()
            val writes = f.writes
            f.advance(10_000)
            assertEquals("第一行", f.title)
            assertEquals(writes, f.writes)
            f.seek(3_500)
            assertEquals("第二行", f.title)
            f.seek(1_500); f.resume(2f)
            f.advance(749)
            assertEquals("第一行", f.title)
            f.advance(1)
            assertEquals("第二行", f.title)
            f.pause(); f.playbackState = Player.STATE_BUFFERING; f.events()
            f.advance(5_000)
            assertEquals("第二行", f.title)
            f.playbackState = Player.STATE_ENDED; f.events()
            assertEquals("原歌名", f.title)
        }
    }

    @Test fun offsetChangesBlankIntervalsAndDisableRestoreAllOwnedKeys() {
        Fixture().use { f ->
            f.seek(1_500); f.enable()
            f.publisher.update(f.state.copy(offsetMs = 2_000))
            assertEquals("原歌名", f.title)
            f.seek(3_500)
            assertEquals("第一行", f.title)
            f.publisher.update(f.state)
            f.seek(5_500)
            assertEquals("原歌名", f.title)
            f.seek(6_000)
            assertEquals("尾行", f.title)
            f.disable()
            assertEquals("原歌名", f.title)
            assertEquals("原歌手", f.metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
            assertFalse(f.metadata.containsKey(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
            assertFalse(f.metadata.containsKey(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
        }
    }

    @Test fun freshArtworkAndMetadataArePreservedWithoutFeedbackLoopOrNestedOriginalTitle() {
        Fixture().use { f ->
            f.seek(1_500); f.enable()
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            f.session.setMetadata(PlatformMetadataOverlay.replace(f.original, mapOf(
                MediaMetadata.METADATA_KEY_ALBUM_ART to bitmap,
                MediaMetadata.METADATA_KEY_TITLE to "更新后的原歌名",
                "rating" to Rating.newHeartRating(true), "zero" to 0L)))
            f.advance(0)
            assertEquals("第一行", f.title)
            assertEquals("更新后的原歌名 · 原歌手", f.metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
            val writes = f.writes
            f.advance(100)
            assertEquals(writes, f.writes)
            f.disable()
            assertEquals("更新后的原歌名", f.title)
            assertNotNull(f.metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
            assertTrue(f.metadata.getRating("rating")!!.hasHeart())
            assertTrue(f.metadata.containsKey("zero"))
            assertEquals(0L, f.metadata.getLong("zero"))
        }
    }

    @Test fun sourceChangeClearsImmediatelyAndStaleDocumentCannotReappear() {
        Fixture().use { f ->
            f.seek(1_500); f.enable()
            f.source = "kugou:new-source"; f.events()
            assertEquals("原歌名", f.title)
            f.advance(5_000)
            assertEquals("原歌名", f.title)
            f.seek(1_500)
            f.publisher.update(f.state.copy(sourceId = f.source, lines = listOf(LyricLine(0, "新音源歌词"))))
            assertEquals("新音源歌词", f.title)
        }
    }

    @Test fun trackTransitionAndNoLyricsDoNotCarryOverOldTitleOrArtist() {
        Fixture().use { f ->
            f.seek(1_500); f.enable()
            f.item = MediaItem.Builder().setMediaId("qq:next").build(); f.events()
            assertEquals("原歌名", f.title)
            f.session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID, f.item.mediaId)
                .putString(MediaMetadata.METADATA_KEY_TITLE, "下一首").putString(MediaMetadata.METADATA_KEY_ARTIST, "下一位").build())
            f.advance(5_000)
            assertEquals("下一首", f.title)
            assertEquals("下一位", f.metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
            f.publisher.update(f.state.copy(mediaId = f.item.mediaId, lines = emptyList()))
            assertEquals("下一首", f.title)
        }
    }

    @Test fun existingDisplayFieldsAreRestoredAndExternalUpdatesWinOnDisable() {
        Fixture().use { f ->
            f.session.setMetadata(PlatformMetadataOverlay.replace(f.original, mapOf(
                MediaMetadata.METADATA_KEY_DISPLAY_TITLE to "原显示标题", MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE to "原显示副标题")))
            f.seek(1_500); f.enable(); f.disable()
            assertEquals("原显示标题", f.metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
            assertEquals("原显示副标题", f.metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
            f.enable()
            f.session.setMetadata(PlatformMetadataOverlay.replace(f.metadata, mapOf(MediaMetadata.METADATA_KEY_TITLE to "外部新标题")))
            // Disable before the pending metadata callback: restoration must not erase this write.
            f.disable()
            assertEquals("外部新标题", f.title)
        }
    }

    @Test fun unknownTypesAndOversizedMetadataAreNotWrittenAndCloseCancelsCallbacks() {
        Fixture().use { f ->
            val parcel = Parcel.obtain()
            val unsupported = try {
                parcel.writeBundle(Bundle().apply { putFloat("vendor_float", 1f); putString(MediaMetadata.METADATA_KEY_MEDIA_ID, f.item.mediaId) })
                parcel.setDataPosition(0)
                MediaMetadata.CREATOR.createFromParcel(parcel)
            } finally { parcel.recycle() }
            f.session.setMetadata(unsupported)
            f.seek(1_500); f.enable()
            val writes = f.writes
            f.advance(100)
            assertEquals(writes, f.writes)
            assertThrows(IllegalArgumentException::class.java) {
                PlatformMetadataOverlay.replace(f.original, mapOf("large" to "X".repeat(512 * 1_024)))
            }
            f.session.setMetadata(f.original); f.advance(0)
            f.publisher.close()
            assertEquals("原歌名", f.title)
            assertTrue(f.listeners.isEmpty())
            assertTrue(shadowOf(f.session.controller).callbacks.isEmpty())
            val closedWrites = f.writes
            f.advance(10_000)
            assertEquals(closedWrites, f.writes)
        }
    }

    @Test fun mainSourceSetHelperReturnsMedia3ExistingPlatformToken() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        val media3 = androidx.media3.session.MediaSession.Builder(RuntimeEnvironment.getApplication(), player).build()
        try {
            assertEquals(media3.platformToken, PlatformSessionAccess.getPlatformSession(media3).sessionToken)
        } finally { media3.release(); player.release() }
    }
}

/** Test-only binder substitute; real device read-back/display must be verified separately. */
@Implements(MediaSession::class)
class MediaCardSessionShadow : ShadowMediaSession() {
    @RealObject private lateinit var realSession: MediaSession
    var writes = 0
        private set
    @Implementation protected fun setMetadata(metadata: MediaMetadata?) {
        writes++
        val controller = shadowOf(realSession.controller)
        controller.setMetadata(metadata)
        Handler(Looper.getMainLooper()).post { controller.executeOnMetadataChanged(metadata) }
    }
}
