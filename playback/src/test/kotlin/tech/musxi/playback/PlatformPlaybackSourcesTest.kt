package tech.musxi.playback

import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test

class PlatformPlaybackSourcesTest {
    private class Source(var generation: Long?) : PlaybackSourceProvider {
        override val authorization = emptyFlow<Long?>()
        override fun currentGeneration() = generation
        override fun resolve(mediaId: String, generation: Long) = StreamLease(null) { "fixture" }
    }
    @Test fun equalGenerationsOfDifferentPlatformsRemainIndependent() {
        val netease = Source(7); val kugou = Source(7)
        val sources = PlatformPlaybackSources(mapOf("netease" to netease, "kugou" to kugou))
        assertEquals(7L, sources.generationFor("netease:1"))
        assertEquals(7L, sources.generationFor("kugou:fixture"))
        kugou.generation = null
        assertEquals(7L, sources.generationFor("netease:1"))
        assertNull(sources.generationFor("kugou:fixture"))
        assertNull(sources.generationFor("qq:1"))
        try { sources.resolve("kugou:fixture", 7); fail("Disconnected source accepted") }
        catch (_: PlaybackSourceException) { }
    }
    @Test fun qqAuthorizationChangesDoNotInvalidateOtherPlatformsOrAcceptStaleQqGeneration() {
        val netease = Source(7); val kugou = Source(7); val qq = Source(7)
        val sources = PlatformPlaybackSources(mapOf("netease" to netease, "kugou" to kugou, "qq" to qq))
        val id = "qq:003abcDEF01234"
        assertEquals(7L, sources.generationFor(id))
        netease.generation = null; kugou.generation = 8
        assertEquals(7L, sources.generationFor(id))
        sources.resolve(id, 7)
        qq.generation = 9
        assertEquals(8L, sources.generationFor("kugou:fixture"))
        try { sources.resolve(id, 7); fail("Old QQ generation accepted") }
        catch (_: PlaybackSourceException) { }
        qq.generation = null
        assertNull(sources.generationFor(id))
        assertEquals(8L, sources.generationFor("kugou:fixture"))
    }

}
