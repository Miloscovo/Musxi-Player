package tech.musxi.playback

import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.playback.*

class QueueQualityTest {
    @Test fun preparationRetainsOldStreamCommitKeepsQueueIdentityAndRollbackRestoresOldQuality() {
        val provider = Provider(); val sources = QueueSources(provider)
        val uris = sources.replace(listOf("netease:1", "netease:1"), 1)
        val old = sources.resolve(uris[0])
        val prepared = sources.prepareQuality(uris[0], "netease:exhigh")
        assertTrue(old.isCurrent())
        assertEquals("标准音质", sources.currentQualityName(uris[0]))
        sources.commitQuality(prepared)
        assertFalse(old.isCurrent())
        assertEquals("netease:1" to 1L, sources.currentSource(uris[0]))
        assertEquals("极高音质", sources.currentQualityName(uris[0]))
        assertTrue(sources.resolve(uris[0]).url.endsWith("exhigh.mp3"))
        assertTrue(sources.resolve(uris[1]).url.endsWith("standard.mp3"))
        sources.rollbackQuality(prepared)
        assertEquals("标准音质", sources.currentQualityName(uris[0]))
        assertTrue(sources.resolve(uris[0]).url.endsWith("standard.mp3"))
    }
    @Test fun cannotCommitQualityAfterSourceChangeOrAccountChange() {
        val provider = Provider(); val sources = QueueSources(provider)
        val uri = sources.replace(listOf("netease:1"), 1).single()
        val prepared = sources.prepareQuality(uri, "netease:exhigh")
        sources.selectSource(uri, "netease:2", 1)
        assertAuth { sources.commitQuality(prepared) }
        val other = sources.prepareQuality(uri, "netease:exhigh")
        provider.generation = 2
        assertAuth { sources.commitQuality(other) }
    }
    @Test fun queryRejectsLateSourceResultAndExactSwitchRejectsDowngrade() {
        val provider = Provider(); val sources = QueueSources(provider)
        val uri = sources.replace(listOf("netease:1"), 1).single()
        provider.onQuery = { sources.selectSource(uri, "netease:2", 1) }
        assertAuth { sources.qualities(uri) }
        provider.downgrade = true
        try { sources.prepareQuality(uri, "netease:exhigh"); fail() }
        catch (error: PlaybackSourceException) { assertEquals(PlaybackFailure.UNAVAILABLE, error.failure) }
    }
    private fun assertAuth(action: () -> Unit) {
        try { action(); fail() } catch (error: PlaybackSourceException) { assertEquals(PlaybackFailure.AUTH_REQUIRED, error.failure) }
    }
    private class Provider : PlaybackSourceProvider {
        var generation = 1L
        var downgrade = false
        var onQuery: () -> Unit = {}
        override val authorization = emptyFlow<Long?>()
        override fun currentGeneration() = generation
        override fun resolve(mediaId: String, generation: Long) = resolve(mediaId, generation, null)
        override fun resolve(mediaId: String, generation: Long, qualityId: String?): StreamLease {
            val id = qualityId?.takeUnless { downgrade } ?: "netease:standard"
            return StreamLease(null, id, PlaybackQualities.name(id)) { "https://m.music.126.net/${id.substringAfter(':')}.mp3" }
        }
        override fun qualityOptions(mediaId: String, generation: Long): QualityOptions {
            onQuery()
            return QualityOptions(listOf(QualityOption("netease:standard", "标准音质")), null)
        }
    }
}
