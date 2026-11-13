package tech.musxi.playback

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.localaudio.*

class LocalQueueSourcesTest {
    private val ticketPattern = Regex("musxi-local://queue/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9]+")
    private val contentUri = "content://tech.musxi.files/audio/fixture.mp3"
    private val fileUri = "file:///storage/emulated/0/Music/fixture-secret.mp3"

    @Test fun ticketsAreOpaqueSlotsThatNeverExposeIdsOrPaths() {
        val host = Host()
        val sources = LocalQueueSources(host)
        val ids = List(3) { UUID.randomUUID().toString() }
        val tickets = sources.replace(ids.map { entry(it) })

        assertEquals(3, tickets.size)
        tickets.forEachIndexed { index, ticket ->
            assertTrue(ticket, ticketPattern.matches(ticket))
            assertTrue(ticket.endsWith("/$index"))
            ids.forEach { assertFalse(ticket.contains(it)) }
            assertFalse(ticket.contains("content://") || ticket.contains("file://"))
            assertTrue(sources.contains(ticket))
        }
        assertEquals(3, tickets.distinct().size)
        assertEquals("LocalQueueSources(redacted)", sources.toString())
        assertEquals("LocalAudioQueueEntry(${ids[0]}, redacted)", entry(ids[0]).toString())
    }

    @Test fun replacingReusingTheSameAudioGetsANewUniqueSlotAndRevokesTheOldOne() {
        val host = Host()
        val sources = LocalQueueSources(host)
        val id = UUID.randomUUID().toString()
        val first = sources.replace(listOf(entry(id))).single()
        val second = sources.replace(listOf(entry(id))).single()

        assertNotEquals(first, second)
        assertFalse(sources.contains(first))
        assertTrue(sources.contains(second))
        assertEquals(LocalAudioError.NOT_FOUND, failure { sources.resolve(first) }.error)
        assertEquals(contentUri, sources.resolve(second))
        assertEquals(1, host.calls.get())
    }

    @Test fun everyResolveAsksTheHostSoFileGrantsAndVersionsAreNeverCached() {
        val host = Host()
        val sources = LocalQueueSources(host)
        val ticket = sources.replace(listOf(entry())).single()

        assertEquals(contentUri, sources.resolve(ticket))
        host.load = { fileUri }
        assertEquals(fileUri, sources.resolve(ticket))
        assertEquals(2, host.calls.get())
    }

    @Test fun contentAndFileUrisAreAcceptedButNetworkAndDecoratedUrisAreRejected() {
        val host = Host()
        val sources = LocalQueueSources(host)
        val ticket = sources.replace(listOf(entry())).single()

        listOf(contentUri, "content://media/external/audio/media/42", "content://authority/audio/1?mode=original",
            "file:///data/user/0/tech.musxi/files/audio/a.mp3", "FILE:///data/x.mp3")
            .forEach { accepted ->
                host.load = { accepted }
                assertEquals(accepted, sources.resolve(ticket))
            }

        listOf("https://m.music.126.net/a.mp3", "http://fs.youthandroid.kugou.com/a.mp3", "content://",
            "content://user:secret@authority/audio/1", "file:///", "file:///data/a.mp3?token=secret",
            "content://authority/audio/1#fragment", "file:fixture.mp3", "file://host/fixture.mp3",
            "/storage/emulated/0/a.mp3", "content://authority/a\nb", "", "   ",
            "content://authority/" + "a".repeat(9000)).forEach { rejected ->
                host.load = { rejected }
                val error = failure { sources.resolve(ticket) }
                assertEquals(rejected, LocalAudioError.READ_FAILED, error.error)
                assertEquals("Local audio: READ_FAILED", error.message)
                assertNull(error.cause)
            }
    }

    @Test fun hostFailuresKeepTheirReasonAndNeverLeakTheUri() {
        val host = Host()
        val sources = LocalQueueSources(host)
        val ticket = sources.replace(listOf(entry())).single()

        host.load = { throw LocalAudioFailure(LocalAudioError.PERMISSION_REQUIRED) }
        val denied = failure { sources.resolve(ticket) }
        assertEquals(LocalAudioError.PERMISSION_REQUIRED, denied.error)
        assertEquals("Local audio: PERMISSION_REQUIRED", denied.message)
        assertNull(denied.cause)

        host.load = { throw IllegalStateException("content://authority/leak-secret") }
        val crashed = failure { sources.resolve(ticket) }
        assertEquals(LocalAudioError.READ_FAILED, crashed.error)
        assertEquals("Local audio: READ_FAILED", crashed.message)
        assertNull(crashed.cause)
        assertFalse(crashed.toString().contains("leak-secret"))
    }

    @Test fun unknownOrRevokedTicketsNeverReachTheHost() {
        val host = Host()
        val sources = LocalQueueSources(host)
        assertEquals(LocalAudioError.NOT_FOUND, failure { sources.resolve("musxi-local://queue/x/0") }.error)
        val ticket = sources.replace(listOf(entry())).single()
        sources.clear()
        assertFalse(sources.contains(ticket))
        assertEquals(LocalAudioError.NOT_FOUND, failure { sources.resolve(ticket) }.error)
        assertEquals(0, host.calls.get())
    }

    @Test fun retainKeepsSurvivingSlotsAndRevokesRemovedOnesImmediately() {
        val host = Host()
        val sources = LocalQueueSources(host)
        val tickets = sources.replace(List(3) { entry() })
        sources.retain(setOf(tickets[1], tickets[2]))

        assertFalse(sources.contains(tickets[0]))
        assertEquals(LocalAudioError.NOT_FOUND, failure { sources.resolve(tickets[0]) }.error)
        assertEquals(contentUri, sources.resolve(tickets[1]))
        assertEquals(contentUri, sources.resolve(tickets[2]))
        assertEquals(2, host.calls.get())

        try {
            sources.retain(setOf(tickets[1], "musxi-local://queue/unknown/0")); fail("Unknown ticket retained")
        } catch (_: IllegalArgumentException) { }
        assertEquals(contentUri, sources.resolve(tickets[1]))
        sources.retain(emptySet())
        assertEquals(LocalAudioError.NOT_FOUND, failure { sources.resolve(tickets[1]) }.error)
    }

    @Test fun lateLoaderResultAfterReplaceIsRejectedByTheTicketFence() {
        val host = Host()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        host.load = {
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            contentUri
        }
        val sources = LocalQueueSources(host)
        val stale = sources.replace(listOf(entry())).single()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<LocalAudioFailure> { failure { sources.resolve(stale) } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val fresh = sources.replace(listOf(entry())).single()
            release.countDown()
            assertEquals(LocalAudioError.SOURCE_CHANGED, result.get(3, TimeUnit.SECONDS).error)
            assertFalse(sources.contains(stale))
            assertTrue(sources.contains(fresh))
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test fun lateLoaderResultAfterClearIsRejectedByTheTicketFence() {
        val host = Host()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        host.load = {
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            fileUri
        }
        val sources = LocalQueueSources(host)
        val ticket = sources.replace(listOf(entry())).single()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<LocalAudioFailure> { failure { sources.resolve(ticket) } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            sources.clear()
            release.countDown()
            assertEquals(LocalAudioError.SOURCE_CHANGED, result.get(3, TimeUnit.SECONDS).error)
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test fun replaceRejectsEmptyDuplicateAndOversizedQueues() {
        val sources = LocalQueueSources(Host())
        try { sources.replace(emptyList()); fail("Empty queue accepted") } catch (_: IllegalArgumentException) { }

        val id = UUID.randomUUID().toString()
        try { sources.replace(listOf(entry(id), entry(id))); fail("Duplicate slot accepted") }
        catch (_: IllegalArgumentException) { }

        try {
            sources.replace(List(QueueSources.MAX_QUEUE_SIZE + 1) { entry() }); fail("Oversized queue accepted")
        } catch (_: IllegalArgumentException) { }

        assertFalse(sources.contains("musxi-local://queue/anything/0"))
    }

    @Test fun queuedSlotsResolveTheEntryThatOwnsTheirIndex() {
        val host = Host()
        val seen = mutableListOf<String>()
        host.load = { resolved -> synchronized(seen) { seen += resolved.audio.id }; contentUri }
        val sources = LocalQueueSources(host)
        val ids = List(4) { UUID.randomUUID().toString() }
        val tickets = sources.replace(ids.mapIndexed { index, id -> entry(id, "0".repeat(62) + "%02x".format(index)) })

        assertEquals(contentUri, sources.resolve(tickets[2]))
        assertEquals(contentUri, sources.resolve(tickets[0]))
        assertEquals(listOf(ids[2], ids[0]), seen)
    }

    private fun item(id: String) = LocalAudioItem(
        id = id,
        metadata = LocalAudioMetadata(fileName = "secret-track.mp3", title = "Fixture", sizeBytes = 1024),
        mode = LocalImportMode.COPY,
        addedAtMs = 0,
    )

    private fun entry(id: String = UUID.randomUUID().toString(), version: String = "a".repeat(64)) =
        LocalAudioQueueEntry(item(id), version, resumePositionMs = 0)

    private inner class Host : LocalAudioPlaybackHost {
        val calls = AtomicInteger()
        var load: (LocalAudioQueueEntry) -> String = { contentUri }
        override fun resolve(entry: LocalAudioQueueEntry): String {
            calls.incrementAndGet()
            return load(entry)
        }
        override fun checkpoint(folderId: String?, audioId: String, positionMs: Long, completed: Boolean,
            result: (Result<Unit>) -> Unit) = result(Result.success(Unit))
    }

    private fun failure(block: () -> Any?): LocalAudioFailure {
        try { block(); fail("Expected LocalAudioFailure") } catch (error: LocalAudioFailure) { return error }
        error("unreachable")
    }
}
