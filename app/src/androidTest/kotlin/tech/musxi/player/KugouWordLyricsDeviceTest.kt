package tech.musxi.player

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import tech.musxi.accounts.LyricsRepository
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.Platform
import tech.musxi.core.library.SongSummary
import tech.musxi.core.library.MediaKey
import tech.musxi.core.lyrics.*

/** Fixed read-only samples: no audio, binding, favorites, account or queue mutations. */
class KugouWordLyricsDeviceTest {
    @Test fun cacheAcceptsParentAliasButRejectsLinkedRootAndOwner() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val scratch = File(context.cacheDir, "word-lyrics-path-${java.util.UUID.randomUUID()}")
        val parent = File(scratch, "real-parent").apply { check(mkdirs()) }
        val alias = File(scratch, "parent-alias").toPath()
        val owner = "a".repeat(64)
        val key = MediaKey(Platform.KUGOU, "${"a".repeat(32)}_0_0")
        val lyrics = ParsedLyrics(listOf(LyricLine(1_000, "Hi", words = listOf(LyricWord(0, 2, 1_000, 1_500)))), "Hi")
        try {
            Files.createSymbolicLink(alias, parent.toPath())
            val root = alias.resolve("lyrics")
            val store = FileLyricsCacheStore(root.toFile())
            store.write(owner, key, lyrics)
            assertEquals(lyrics, FileLyricsCacheStore(File(parent, "lyrics")).read(owner, key)?.lyrics)
            val realRoot = root.toRealPath()
            val legacyChild = root.resolve(owner).toRealPath(LinkOption.NOFOLLOW_LINKS)
            val legacyMismatch = legacyChild != realRoot.resolve(owner)

            val outside = File(scratch, "outside").apply { check(mkdir()) }
            val sentinel = File(outside, "sentinel").apply { writeText("unchanged") }
            val ownerLink = root.resolve("b".repeat(64))
            Files.createSymbolicLink(ownerLink, outside.toPath())
            fun rejected(block: () -> Unit) {
                try { block(); fail("Linked cache directory was accepted") }
                catch (_: IllegalStateException) { }
            }
            rejected { store.write("b".repeat(64), key, lyrics) }
            assertTrue(Files.isSymbolicLink(ownerLink))
            val rootLink = File(scratch, "root-link").toPath()
            Files.createSymbolicLink(rootLink, realRoot)
            rejected { FileLyricsCacheStore(rootLink.toFile()).write(owner, key, lyrics) }
            assertEquals("unchanged", sentinel.readText())
            assertEquals(listOf("sentinel"), outside.listFiles()!!.map { it.name })
            File(context.filesDir, "kugou-word-lyrics-cache-path-device.json").writeText(JSONObject()
                .put("status", "passed").put("legacyCanonicalComparisonMismatch", legacyMismatch)
                .put("parentAliasRoundTrip", true).put("ownerLinkRejected", true)
                .put("rootLinkRejected", true).put("outsideUnchanged", true).toString(2))
        } finally {
            // Walk only this test's UUID directory; no FOLLOW_LINKS, so the aliases are not traversed.
            Files.walk(scratch.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test fun readRealKrcWordsAndRoundTripPrivateTemporaryCache() {
        val phone = BackendBatch5DeviceTest.PhoneDriver()
        val app = phone.activity.application as MusxiApplication
        fun <T> await(block: ((Result<T>) -> Unit) -> Unit): T {
            val promise = CompletableFuture<Result<T>>()
            phone.runOnIdle { block { promise.complete(it) } }
            return promise.get(70, TimeUnit.SECONDS).getOrThrow()
        }
        // A cold process restores the platforms independently. Take the identity/queue
        // baseline only after automatic revalidation and the controller connection settle.
        val restoring = setOf(AccountPhase.PREPARING, AccountPhase.VERIFYING, AccountPhase.REVALIDATING)
        phone.waitUntil(60_000) {
            app.accounts.states.value.getValue(Platform.KUGOU).phase == AccountPhase.CONNECTED &&
                app.accounts.states.value.values.none { it.phase in restoring } &&
                app.playback.state.value.connected
        }
        val identities = Platform.entries.associateWith { app.accounts.playbackIdentity(it) }
        val queue = app.playback.queueState.value
        val repository = LyricsRepository(app.accounts)
        val report = JSONArray()
        val reportFile = File(app.filesDir, "kugou-word-lyrics-device.json")
        fun progress() = reportFile.writeText(JSONObject().put("status", "running").put("samples", report).toString(2))
        progress()
        val cacheDirectory = File(app.cacheDir, "word-lyrics-device-${java.util.UUID.randomUUID()}")
        try {
            for (query in listOf("Lemon 米津玄師", "Hello Adele")) {
                val songs = await<List<SongSummary>> { app.kugouLibrary.search(query, it) }
                val title = query.substringBefore(' ')
                val song = songs.first { it.title.equals(title, ignoreCase = true) }
                val parsed = await<ParsedLyrics> { repository.preview(song.key, { true }, it) }
                assertTrue("Sample has no line lyrics", parsed.lines.isNotEmpty())
                assertTrue("Sample has no verified word timings", parsed.lines.any { it.words.isNotEmpty() })
                assertTrue(parsed.lines.all { LyricsWordTimeline.valid(it.text, it.words) })
                val sampleRow = JSONObject().put("sample", title).put("rows", parsed.lines.size)
                    .put("wordRows", parsed.lines.count { it.words.isNotEmpty() })
                    .put("words", parsed.lines.sumOf { it.words.size })
                    .put("translatedRows", parsed.lines.count { it.translation != null }).put("cacheRoundTrip", false)
                report.put(sampleRow)
                progress()
                val cache = FileLyricsCacheStore(cacheDirectory)
                val owner = requireNotNull(identities[Platform.KUGOU]).owner
                cache.write(owner, song.key, parsed)
                val cold = requireNotNull(FileLyricsCacheStore(cacheDirectory).read(owner, song.key)).lyrics
                assertEquals(parsed, cold)
                val row = cold.lines.first { it.words.isNotEmpty() }
                val first = row.words.first()
                val sample = LyricsWordTimeline.sample(row.words, first.startTimeMs)
                assertTrue(sample.index >= 0)
                sampleRow.put("cacheRoundTrip", true)
                progress()
            }
            assertEquals(identities, Platform.entries.associateWith { app.accounts.playbackIdentity(it) })
            assertEquals(queue, app.playback.queueState.value)
            reportFile.writeText(
                JSONObject().put("status", "passed").put("samples", report)
                    .put("accountsAndQueueUnchanged", true).toString(2))
        } finally {
            // Only this run's UUID directory, created above under the app's cache directory.
            cacheDirectory.deleteRecursively()
        }
    }
}
