package tech.musxi.player

import android.content.Context
import android.os.SystemClock
import java.io.IOException
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*

/** Synthetic-only validation; does not access app accounts, network, or the real library cache. */
internal object BackendCacheValidationRunner {
    private const val PREFS = "backend-cache-validation"
    private var last = Snapshot("NONE", "NOT_RUN")

    fun run(app: MusxiApplication, step: String): Boolean {
        if (step != "bc_storage_failure" && step != "bc_snapshot") return false
        if (step == "bc_snapshot") {
            publish(app, last); return true
        }
        val started = SystemClock.elapsedRealtime()
        last = try {
            execute()
        } catch (_: Exception) {
            Snapshot(step, "ERROR", ran = true, error = true)
        }.let { it.copy(elapsedMs = SystemClock.elapsedRealtime() - started) }
        publish(app, last)
        return true
    }

    private fun execute(): Snapshot {
        val identity = LibraryCacheIdentity("synthetic".padEnd(64, '0'), 1, true)
        val fixture = Fixture(identity)
        fixture.store.write(0, saved("OLD")); fixture.store.write(1, saved("OTHER"))
        val stages = mutableListOf<Stage>()
        fixture.browser.playlists(0); fixture.flush()
        stages += fixture.stage("old_cache", "OLD", "CACHE")
        fixture.browser.invalidate(identity); fixture.store.fail = true
        fixture.fake.release(page("FRESH")); fixture.flush()
        stages += fixture.stage("write_failure", "FRESH", "NETWORK")
        fixture.browser.playlists(1, refresh = false); fixture.flush()
        stages += fixture.stage("other_page", "OTHER", "CACHE")
        val requests = fixture.fake.requests
        fixture.browser.playlists(0, refresh = false); fixture.flush()
        stages += fixture.stage("reopen_old", "OLD", "CACHE").let {
            it.copy(pass = it.pass && fixture.fake.requests == requests)
        }
        fixture.store.fail = false; fixture.browser.refresh(); fixture.flush()
        fixture.fake.release(page("RECOVERED")); fixture.flush()
        stages += fixture.stage("recovered", "RECOVERED", "NETWORK")
        val pass = stages.all { it.pass } &&
            stages.map { it.prefix } == listOf("old_cache", "write_failure", "other_page", "reopen_old", "recovered") &&
            fixture.store.failures == 1 && fixture.store.successes == 3 &&
            fixture.fake.requests == 3 && fixture.fake.responses == 2
        return Snapshot("bc_storage_failure", if (pass) "COMPLETE" else "FAILED", true,
            stages = stages, store = fixture.store, fake = fixture.fake, pass = pass)
    }

    private fun publish(app: MusxiApplication, s: Snapshot) {
        val e = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("synthetic", true).putString("command", s.command).putString("phase", s.phase)
            .putBoolean("scenarioRan", s.ran).putBoolean("executionError", s.error)
            .putBoolean("pass", s.pass).putLong("elapsedMs", s.elapsedMs)
            .putInt("cacheReads", s.store.reads).putInt("writes", s.store.writes)
            .putInt("writeFailures", s.store.failures).putInt("writeSuccesses", s.store.successes)
            .putInt("networkRequests", s.fake.requests).putInt("networkResponses", s.fake.responses)
            .putInt("stageCount", s.stages.size)
        s.stages.forEach { t ->
            val p = "stage_${t.prefix}"
            e.putString("${p}_source", t.source)
                .putString("${p}_freshness", if (t.stale) "STALE" else "FRESH")
                .putBoolean("${p}_stale", t.stale).putBoolean("${p}_storageError", t.storageError)
                .putBoolean("${p}_errorPresent", t.errorPresent).putBoolean("${p}_contentMatch", t.contentMatch)
                .putInt("${p}_cacheReads", t.counts.reads).putInt("${p}_writes", t.counts.writes)
                .putInt("${p}_writeFailures", t.counts.writeFailures)
                .putInt("${p}_writeSuccesses", t.counts.writeSuccesses)
                .putInt("${p}_networkRequests", t.counts.networkRequests)
                .putInt("${p}_networkResponses", t.counts.networkResponses)
                .putBoolean("${p}_pass", t.pass)
        }
        e.apply()
    }

    private fun page(title: String) = Page(listOf(PlaylistSummary(
        MediaKey(Platform.NETEASE, "synthetic"), title, null, 1)), null, 1)
    private fun saved(title: String) = SavedLibraryPage(page(title), 100L)

    private data class Counts(
        val reads: Int, val writes: Int, val writeFailures: Int, val writeSuccesses: Int,
        val networkRequests: Int, val networkResponses: Int,
    )
    private data class Stage(
        val prefix: String, val source: String, val stale: Boolean, val storageError: Boolean,
        val errorPresent: Boolean, val contentMatch: Boolean, val counts: Counts, val pass: Boolean,
    )
    private data class Snapshot(
        val command: String, val phase: String, val ran: Boolean = false, val error: Boolean = false,
        val stages: List<Stage> = emptyList(), val store: Store = Store(), val fake: Fake = Fake(),
        val elapsedMs: Long = 0, val pass: Boolean = false,
    )

    private class Fixture(private val identity: LibraryCacheIdentity) {
        val store = Store(); val fake = Fake()
        private val io = ArrayDeque<() -> Unit>(); private val delivered = ArrayDeque<() -> Unit>()
        val browser = LibraryBrowser({ identity }, { expected, action ->
            if (!expected.sameAttempt(identity)) throw IllegalArgumentException()
            action(store)
        }, fake, io::add, delivered::add, { 200L })
        fun flush() {
            while (io.isNotEmpty() || delivered.isNotEmpty()) {
                while (io.isNotEmpty()) io.removeFirst()()
                while (delivered.isNotEmpty()) delivered.removeFirst()()
            }
        }
        fun stage(prefix: String, title: String, source: String): Stage {
            val s = browser.state.value
            val match = (s.content as? LibraryContent.Playlists)?.page?.items?.singleOrNull()?.title == title
            val stale = prefix == "other_page" || prefix == "reopen_old"
            val counts = Counts(store.reads, store.writes, store.failures, store.successes,
                fake.requests, fake.responses)
            return Stage(prefix, s.source?.name ?: "NONE", s.stale, s.storageError, s.error != null,
                match, counts, s.source?.name == source && match && s.stale == stale &&
                s.storageError == (prefix == "write_failure") && s.error == null)
        }
    }

    private class Fake : LibraryPageFetch {
        private val callbacks = ArrayDeque<(Result<Page<PlaylistSummary>>) -> Unit>()
        var requests = 0; var responses = 0
        override fun playlists(offset: Int, result: (Result<Page<PlaylistSummary>>) -> Unit) {
            requests++; callbacks.add { responses++; result(it) }
        }
        fun release(page: Page<PlaylistSummary>) = callbacks.removeLast()(Result.success(page))
        override fun tracks(playlist: MediaKey, offset: Int, result: (Result<Page<SongSummary>>) -> Unit) =
            result(Result.failure(IllegalStateException()))
    }

    private class Store : LibraryPageStore {
        private val pages = mutableMapOf<Int, SavedLibraryPage<PlaylistSummary>>()
        var fail = false; var reads = 0; var writes = 0; var failures = 0; var successes = 0
        fun write(offset: Int, value: SavedLibraryPage<PlaylistSummary>) = writePlaylists("", offset, value)
        override fun readPlaylists(owner: String, offset: Int): SavedLibraryPage<PlaylistSummary>? {
            reads++; return pages[offset]
        }
        override fun writePlaylists(owner: String, offset: Int, value: SavedLibraryPage<PlaylistSummary>) {
            writes++; if (fail) { failures++; throw IOException() }
            pages[offset] = value; successes++
        }
        override fun readTracks(owner: String, playlist: MediaKey, offset: Int) = null
        override fun writeTracks(owner: String, playlist: MediaKey, offset: Int, value: SavedLibraryPage<SongSummary>) =
            throw IOException()
        override fun clear() = pages.clear()
    }
}
