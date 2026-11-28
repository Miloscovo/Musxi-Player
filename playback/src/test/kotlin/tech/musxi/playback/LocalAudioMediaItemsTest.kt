package tech.musxi.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import tech.musxi.core.localaudio.LocalAudioItem
import tech.musxi.core.localaudio.LocalAudioLimits
import tech.musxi.core.localaudio.LocalAudioMetadata
import tech.musxi.core.localaudio.LocalAudioSegment
import tech.musxi.core.localaudio.LocalImportMode

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class)
class LocalAudioMediaItemsTest {
    @Test fun cueFramesKeepMicrosecondPrecisionAndAreAbsoluteSourceTime() {
        val item = item(segment = LocalAudioSegment(startUs = 12_333_333, endUs = 38_666_667, trackNumber = 2))
        val clip = LocalAudioMediaItems.build(item, "musxi-local://queue/a/0", MediaMetadata.EMPTY, "local:${item.id}")
            .clippingConfiguration

        assertEquals(12_333_333L, clip.startPositionUs)
        assertEquals(38_666_667L, clip.endPositionUs)
        assertEquals(26_333L, item.segment!!.durationMs)
        assertFalse(clip.relativeToDefaultPosition)
        assertFalse(clip.relativeToLiveWindow)
        assertFalse(clip.startsAtKeyFrame)
    }

    @Test fun ordinaryItemsKeepMedia3DefaultClipping() {
        val item = item(segment = null)
        val mediaItem = LocalAudioMediaItems.build(item, "musxi-local://queue/a/0", MediaMetadata.EMPTY, "local:${item.id}")

        assertEquals(MediaItem.ClippingConfiguration.UNSET, mediaItem.clippingConfiguration)
        assertEquals(C.TIME_END_OF_SOURCE, mediaItem.clippingConfiguration.endPositionUs)
        assertEquals("musxi-local://queue/a/0", mediaItem.localConfiguration!!.uri.toString())
    }

    @Test fun sameSourceSlicesRemainDistinctQueueItems() {
        val first = item(segment = LocalAudioSegment(0, 5_000_000, 1))
        val second = item(segment = LocalAudioSegment(5_000_000, 11_000_000, 2))
        val firstItem = LocalAudioMediaItems.build(first, "musxi-local://queue/a/0", MediaMetadata.EMPTY, "local:${first.id}")
        val secondItem = LocalAudioMediaItems.build(second, "musxi-local://queue/a/1", MediaMetadata.EMPTY, "local:${second.id}")

        assertEquals("musxi-local://queue/a/0", firstItem.localConfiguration!!.uri.toString())
        assertEquals("musxi-local://queue/a/1", secondItem.localConfiguration!!.uri.toString())
        assertEquals(0L, firstItem.clippingConfiguration.startPositionUs)
        assertEquals(5_000_000L, firstItem.clippingConfiguration.endPositionUs)
        assertEquals(5_000_000L, secondItem.clippingConfiguration.startPositionUs)
        assertEquals(11_000_000L, secondItem.clippingConfiguration.endPositionUs)
        assertNull(firstItem.mediaMetadata.artworkUri)
    }

    @Test fun seekAndProgressPositionsStayInsideTheSlice() {
        val segment = LocalAudioSegment(20_000_000, 80_000_000, 3)
        val item = item(segment = segment)

        assertEquals(0L, LocalAudioMediaItems.positionMs(item, -1))
        assertEquals(5_000L, LocalAudioMediaItems.positionMs(item, 5_000))
        assertEquals(60_000L, LocalAudioMediaItems.positionMs(item, 60_000))
        assertEquals(60_000L, LocalAudioMediaItems.positionMs(item, 90_000))
        assertEquals(90_000L, LocalAudioMediaItems.positionMs(item(segment = null), 90_000))
    }

    private fun item(segment: LocalAudioSegment?) = LocalAudioItem(
        id = LocalAudioLimits.newId(),
        metadata = LocalAudioMetadata("disc.flac", title = "分曲"),
        mode = LocalImportMode.COPY,
        addedAtMs = 0,
        segment = segment,
    )
}
