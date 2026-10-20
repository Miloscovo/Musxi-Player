package tech.musxi.playback

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*
import tech.musxi.core.playback.PlaybackFailure

class QueueSourcesTest {
    @Test fun substituteBindsBothAccountsAndReturnsToOriginalWithoutChangingQueueTickets() {
        val provider = Provider()
        var qqGeneration: Long? = 9
        val combined = object : PlaybackSourceProvider by provider {
            override fun generationFor(mediaId: String) = if (mediaId.startsWith("qq:")) qqGeneration else provider.generation
            override fun resolve(mediaId: String, generation: Long) = if (mediaId.startsWith("qq:"))
                StreamLease(null) { "https://isure.stream.qqmusic.qq.com/song.mp3" } else provider.resolve(mediaId, generation)
        }
        val sources = QueueSources(combined)
        val tickets = sources.replace(listOf("netease:1", "netease:2"), 1)
        val originalMetadata = sources.resolve(tickets[0])
        assertTrue(originalMetadata.isCurrent())
        sources.selectSource(tickets[0], "qq:A0000000000001", 9)
        assertFalse(originalMetadata.isCurrent())
        val substituteMetadata = sources.resolve(tickets[0])
        assertTrue(substituteMetadata.isCurrent())
        assertTrue(substituteMetadata.url.contains("qqmusic"))
        assertEquals(fixtureUrl, sources.resolve(tickets[1]).url)
        qqGeneration = 10
        assertFalse(sources.sourceAuthorized(tickets[0]))
        assertEquals(PlaybackFailure.AUTH_REQUIRED, failure { sources.resolve(tickets[0]) })
        sources.resetSource(tickets[0])
        assertFalse(substituteMetadata.isCurrent())
        assertEquals(fixtureUrl, sources.resolve(tickets[0]).url)
        qqGeneration = 9
        sources.selectSource(tickets[0], "qq:A0000000000001", 9)
        provider.generation = 2
        assertEquals(PlaybackFailure.AUTH_REQUIRED, failure { sources.resolve(tickets[0]) })
        sources.clear()
        assertEquals(PlaybackFailure.AUTH_REQUIRED, failure { sources.selectSource(tickets[0], "qq:A0000000000001", 9) })
    }

    @Test fun sourceChangeWhileResolvingCannotReturnOrCacheOldSource() {
        val provider = Provider(); val sources = QueueSources(provider)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        provider.load = { _, _ ->
            entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); StreamLease(null) { fixtureUrl }
        }
        val ticket = sources.replace(listOf("netease:1"), 1).single()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<PlaybackFailure> { failure { sources.resolve(ticket) } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            sources.selectSource(ticket, "netease:2", 1)
            release.countDown()
            assertEquals(PlaybackFailure.AUTH_REQUIRED, result.get(3, TimeUnit.SECONDS))
        } finally { release.countDown(); worker.shutdownNow() }
    }
    @Test fun queueChecksItsOwnPlatformEvenWhenGenerationsAreEqual() {
        class PlatformSource(var generation: Long?, private val url: String) : PlaybackSourceProvider {
            override val authorization = emptyFlow<Long?>()
            override fun currentGeneration() = generation
            override fun resolve(mediaId: String, generation: Long) = StreamLease(null) { url }
        }
        val netease = PlatformSource(1, fixtureUrl)
        val kugou = PlatformSource(1, "http://fs.youthandroid2.kugou.com/song.mp3")
        val sources = QueueSources(PlatformPlaybackSources(mapOf("netease" to netease, "kugou" to kugou)))
        val id = "kugou:" + "a".repeat(32) + "_0_1"
        val ticket = sources.replace(listOf(id), 1).single()
        assertTrue(sources.resolve(ticket).url.startsWith("http:"))
        netease.generation = null
        assertTrue(sources.resolve(ticket).url.startsWith("http:"))
        kugou.generation = 2
        assertEquals(PlaybackFailure.AUTH_REQUIRED, failure { sources.resolve(ticket) })
        kugou.generation = 1
        try { sources.replace(listOf(id, "netease:1"), 1); fail("Mixed platform queue accepted") }
        catch (_: IllegalArgumentException) { }
    }
    private val fixtureUrl = "https://m.music.126.net/song.mp3?signature=fixture-secret"
    private inner class Provider : PlaybackSourceProvider {
        override val authorization = emptyFlow<Long?>()
        @Volatile var generation: Long? = 1
        val calls = AtomicInteger()
        var load: (String, Long) -> StreamLease = { _, _ -> StreamLease(null) { fixtureUrl } }
        override fun currentGeneration() = generation
        override fun resolve(mediaId: String, generation: Long): StreamLease { calls.incrementAndGet(); return load(mediaId, generation) }
    }
    private fun failure(block: () -> Unit): PlaybackFailure {
        try { block(); fail("Expected fixed source failure") } catch (error: PlaybackSourceException) {
            assertEquals("Playback source unavailable", error.message)
            assertNull(error.cause)
            return error.failure
        }
        error("unreachable")
    }

    @Test fun duplicateSongsUseDifferentOpaqueTicketsAndNeverAcceptAnArbitraryUrl() {
        val provider = Provider(); val sources = QueueSources(provider)
        val uris = sources.replace(listOf("netease:1", "netease:1"), 1)
        assertNotEquals(uris[0], uris[1])
        assertTrue(uris.all { it.startsWith("musxi://queue/") && !it.contains("netease:1") })
        assertEquals(PlaybackFailure.AUTH_REQUIRED, failure { sources.resolve(fixtureUrl) })
        assertEquals(0, provider.calls.get())
        assertEquals(fixtureUrl, sources.resolve(uris[1]).url)
    }

    @Test fun cachedCapabilitiesCannotSurviveQueueReplacementOrClear() {
        val provider = Provider(); val sources = QueueSources(provider)
        val old = sources.replace(listOf("netease:1"), 1).single()
        sources.resolve(old)
        val replacement = sources.replace(listOf("netease:1"), 1).single()
        assertEquals(PlaybackFailure.AUTH_REQUIRED, failure { sources.resolve(old) })
        sources.resolve(replacement)
        sources.clear()
        assertEquals(PlaybackFailure.AUTH_REQUIRED, failure { sources.resolve(replacement) })
        assertEquals(2, provider.calls.get())
    }

    @Test fun cachedSourceStillChecksCurrentAccountBeforeReadingUrl() {
        val provider = Provider(); val sources = QueueSources(provider)
        val uri = sources.replace(listOf("netease:1"), 1).single()
        sources.resolve(uri)
        provider.generation = 2
        assertEquals(PlaybackFailure.AUTH_REQUIRED, failure { sources.resolve(uri) })
        assertEquals(1, provider.calls.get())
    }

    @Test fun expiredCachedLeaseIsResolvedAgainAndFreshLeaseIsReused() {
        val provider = Provider(); val sources = QueueSources(provider)
        val expired = AtomicBoolean(false)
        provider.load = { _, _ ->
            val first = provider.calls.get() == 1
            StreamLease(null) {
                if (first && expired.get()) throw PlaybackSourceException(PlaybackFailure.SOURCE_EXPIRED)
                fixtureUrl
            }
        }
        val uri = sources.replace(listOf("netease:1"), 1).single()
        sources.resolve(uri); sources.resolve(uri)
        assertEquals(1, provider.calls.get())
        expired.set(true)
        sources.resolve(uri); sources.resolve(uri)
        assertEquals(2, provider.calls.get())
    }

    @Test fun leaseCacheOnlyRetainsThreeEntries() {
        val provider = Provider(); val sources = QueueSources(provider)
        val uris = sources.replace((1..4).map { "netease:$it" }, 1)
        uris.forEach { sources.resolve(it) }
        assertEquals(4, provider.calls.get())
        sources.resolve(uris[3])
        assertEquals(4, provider.calls.get())
        sources.resolve(uris[0])
        assertEquals(5, provider.calls.get())
    }

    @Test fun slowResponseCannotDeliverIntoAReplacementQueue() = rejectSlowResponse { sources, provider ->
        sources.replace(listOf("netease:2"), provider.generation!!)
    }

    @Test fun slowResponseCannotDeliverAfterAccountChanges() = rejectSlowResponse { _, provider -> provider.generation = 2 }

    private fun rejectSlowResponse(change: (QueueSources, Provider) -> Unit) {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val provider = Provider(); val sources = QueueSources(provider)
        provider.load = { _, _ ->
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            StreamLease(null) { fixtureUrl }
        }
        val uri = sources.replace(listOf("netease:1"), 1).single()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<PlaybackFailure> { failure { sources.resolve(uri) } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            change(sources, provider)
            release.countDown()
            assertEquals(PlaybackFailure.AUTH_REQUIRED, future.get(3, TimeUnit.SECONDS))
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun foreignHostAndMismatchedMetadataAreRejectedWithoutLeakingSource() {
        val provider = Provider(); val sources = QueueSources(provider)
        val uri = sources.replace(listOf("netease:1"), 1).single()
        provider.load = { _, _ -> StreamLease(null) { "https://evil.test/fixture-secret" } }
        assertEquals(PlaybackFailure.PROTOCOL, failure { sources.resolve(uri) })
        provider.load = { _, _ -> StreamLease(SongSummary(MediaKey(Platform.NETEASE, "2"), "Fixture", emptyList(), "", 0)) { fixtureUrl } }
        assertEquals(PlaybackFailure.PROTOCOL, failure { sources.resolve(uri) })
    }

    @Test fun invalidationForcesFreshSourceAndSafeToStringsDoNotRevealUrls() {
        val provider = Provider(); val sources = QueueSources(provider)
        val uri = sources.replace(listOf("netease:1"), 1).single()
        assertFalse(sources.resolve(uri).toString().contains("fixture-secret"))
        sources.invalidate(uri)
        sources.resolve(uri)
        assertEquals(2, provider.calls.get())
        assertEquals("StreamLease(redacted)", StreamLease(null) { fixtureUrl }.toString())
    }
    @Test fun qqTicketAndCachedLeaseRemainBoundToItsAccountAndRejectMixedPlatformQueue() {
        class Source(var generation: Long?) : PlaybackSourceProvider {
            override val authorization = emptyFlow<Long?>()
            override fun currentGeneration() = generation
            override fun resolve(mediaId: String, generation: Long) = StreamLease(null) {
                "https://dl.stream.qqmusic.qq.com/M500fixture.mp3?sign=fixture"
            }
        }
        val qq = Source(7); val netease = Source(7)
        val sources = QueueSources(PlatformPlaybackSources(mapOf("qq" to qq, "netease" to netease)))
        val id = "qq:003abcDEF01234"
        val ticket = sources.replace(listOf(id), 7).single()
        assertTrue(sources.resolve(ticket).url.startsWith("https:"))
        netease.generation = null
        assertTrue(sources.resolve(ticket).url.startsWith("https:"))
        qq.generation = 8
        assertEquals(PlaybackFailure.AUTH_REQUIRED, failure { sources.resolve(ticket) })
        try { sources.replace(listOf(id, "netease:1"), 8); fail("Mixed QQ queue accepted") }
        catch (_: IllegalArgumentException) { }
    }

}
