package tech.musxi.player

import android.content.Context
import tech.musxi.core.account.Platform
import tech.musxi.core.library.FavoritesBrowser
import tech.musxi.core.library.FavoritesFetch
import tech.musxi.core.library.FavoritesSnapshot
import tech.musxi.core.library.FavoritesState
import tech.musxi.core.library.FavoritesWrite
import tech.musxi.core.library.LibraryCacheIdentity
import tech.musxi.core.library.LibraryPageFailure
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.SongSummary

/** Synthetic-only acceptance checks; never reads app accounts, network, or the real favorites cache. */
internal object BackendFavoritesValidationRunner {
    private const val PREFS = "backend-favorites-validation"
    private var last = Snapshot(Phase.NOT_RUN, emptyList())

    fun run(app: MusxiApplication, step: String): Boolean {
        if (step != "bf_faults" && step != "bf_snapshot") return false
        if (step == "bf_faults") {
            last = Snapshot(Phase.RUNNING, emptyList())
            publish(app, last)
            last = try {
                execute()
            } catch (_: Throwable) {
                Snapshot(Phase.FAIL, emptyList())
            }
            publish(app, last)
        } else {
            publish(app, last)
        }
        return true
    }

    private fun execute(): Snapshot {
        val stages = buildList {
            Scenario.entries.forEach { scenario ->
                val fixture = Fixture()
                Platform.entries.forEach { add(fixture.run(scenario, it)) }
            }
        }
        val pass = stages.size == Scenario.entries.size * Platform.entries.size && stages.all { it.pass }
        return Snapshot(if (pass) Phase.PASS else Phase.FAIL, stages)
    }

    private fun publish(app: MusxiApplication, snapshot: Snapshot) {
        val editor = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
            .putBoolean("synthetic", true)
            .putString("phase", snapshot.phase.name)
            .putBoolean("pass", snapshot.phase == Phase.PASS)
            .putInt("fetchRequests", snapshot.stages.sumOf { it.counts.fetches })
            .putInt("writeRequests", snapshot.stages.sumOf { it.counts.writes })
            .putInt("verifiedEvents", snapshot.stages.sumOf { it.counts.verified })
            .putInt("stageCount", snapshot.stages.size)
        snapshot.stages.forEach { stage ->
            val prefix = "stage_${stage.scenario.name}_${stage.platform.name}"
            editor.putString("${prefix}_phase", if (stage.pass) Phase.PASS.name else Phase.FAIL.name)
                .putBoolean("${prefix}_pass", stage.pass)
                .putInt("${prefix}_fetchRequests", stage.counts.fetches)
                .putInt("${prefix}_writeRequests", stage.counts.writes)
                .putInt("${prefix}_verifiedEvents", stage.counts.verified)
        }
        editor.apply()
    }

    private enum class Scenario { WRITE_FAILURE, READBACK_FAILURE, READBACK_MISMATCH, LATE_RESULT }
    private enum class Phase { NOT_RUN, RUNNING, PASS, FAIL }
    private data class Counts(val fetches: Int, val writes: Int, val verified: Int)
    private data class Stage(
        val scenario: Scenario,
        val platform: Platform,
        val counts: Counts,
        val pass: Boolean,
    )
    private data class Snapshot(val phase: Phase, val stages: List<Stage>)
    private data class FetchCall(val platform: Platform, val isCurrent: () -> Boolean,
        val result: (Result<FavoritesSnapshot>) -> Unit)
    private data class WriteCall(val platform: Platform, val isCurrent: () -> Boolean,
        val result: (Result<Unit>) -> Unit)

    private class Fixture {
        val identities = Platform.entries.associateWith { LibraryCacheIdentity(it.name, 1, true) }.toMutableMap()
        private val fetches = mutableListOf<FetchCall>()
        private val writes = mutableListOf<WriteCall>()
        private val verified = mutableListOf<Pair<Platform, LibraryCacheIdentity>>()
        private var fetchRequests = 0
        private var writeRequests = 0
        private val browser = FavoritesBrowser(
            identity = { identities[it] },
            fetch = FavoritesFetch { platform, isCurrent, result ->
                fetchRequests++; fetches += FetchCall(platform, isCurrent, result)
            },
            write = FavoritesWrite { song, _, isCurrent, result ->
                writeRequests++; writes += WriteCall(song.key.platform, isCurrent, result)
            },
            now = { 0L },
            onVerifiedChange = { platform, identity -> verified += platform to identity },
        )

        init {
            Platform.entries.forEach { browser.refresh(it); nextFetch(it).result(Result.success(FavoritesSnapshot(keys(it)))) }
        }

        fun run(scenario: Scenario, platform: Platform): Stage {
            val others = otherStates(platform)
            val before = counts()
            val pass = try {
                when (scenario) {
                    Scenario.WRITE_FAILURE -> writeFailure(platform)
                    Scenario.READBACK_FAILURE -> readback(platform, fail = true)
                    Scenario.READBACK_MISMATCH -> readback(platform, fail = false)
                    Scenario.LATE_RESULT -> lateResults(platform)
                }
            } catch (_: Throwable) {
                false
            }
            val counts = counts() - before
            val expected = if (scenario == Scenario.LATE_RESULT) Counts(2, 2, 0)
                else Counts(if (scenario == Scenario.WRITE_FAILURE) 0 else 1, 1, 0)
            return Stage(scenario, platform, counts, pass && otherStates(platform) == others && counts == expected &&
                fetches.isEmpty() && writes.isEmpty() && verified.isEmpty())
        }

        private fun writeFailure(platform: Platform): Boolean {
            val old = keys(platform)
            val song = song(platform, "write-failure-new")
            browser.setLiked(song, true)
            val call = nextWrite(platform)
            val pending = isPending(platform, song, old)
            call.result(Result.failure(LibraryPageFailure("network")))
            return pending && settled(platform, old, "network")
        }

        private fun readback(platform: Platform, fail: Boolean): Boolean {
            val old = keys(platform)
            val song = song(platform, "readback-new")
            browser.setLiked(song, true)
            val write = nextWrite(platform)
            if (!isPending(platform, song, old)) return false
            write.result(Result.success(Unit))
            val readback = nextFetch(platform)
            if (!isPending(platform, song, old)) return false
            readback.result(if (fail) Result.failure(LibraryPageFailure("network"))
                else Result.success(FavoritesSnapshot(old)))
            return settled(platform, old, "write_unverified")
        }

        private fun lateResults(platform: Platform): Boolean {
            val old = keys(platform)
            val first = song(platform, "late-write-new")
            browser.setLiked(first, true)
            val lateWrite = nextWrite(platform)
            if (!isPending(platform, first, old)) return false
            advanceIdentity(platform)
            val writeRejected = !lateWrite.isCurrent()
            lateWrite.result(Result.success(Unit))
            if (!writeRejected || fetches.isNotEmpty() || !settled(platform, emptySet(), "account_changed")) return false

            val current = keys(platform)
            browser.refresh(platform)
            nextFetch(platform).result(Result.success(FavoritesSnapshot(current)))
            val second = song(platform, "late-readback-new")
            browser.setLiked(second, true)
            val write = nextWrite(platform)
            if (!isPending(platform, second, current)) return false
            write.result(Result.success(Unit))
            val lateReadback = nextFetch(platform)
            if (!isPending(platform, second, current)) return false
            advanceIdentity(platform)
            val readbackRejected = !lateReadback.isCurrent()
            lateReadback.result(Result.success(FavoritesSnapshot(current + second.key)))
            return readbackRejected && settled(platform, emptySet(), "account_changed")
        }

        private fun advanceIdentity(platform: Platform) {
            val previous = identities.getValue(platform)
            identities[platform] = previous.copy(owner = "${previous.owner}-next", generation = previous.generation + 1)
            browser.accountChanged()
        }

        private fun isPending(platform: Platform, song: SongSummary, old: Set<MediaKey>): Boolean {
            val state = browser.state.value.getValue(platform)
            return state.loading && state.pending == song.key && state.keys == old &&
                song.key !in old && state.synced && state.error == null
        }

        private fun settled(platform: Platform, old: Set<MediaKey>, error: String): Boolean {
            val state = browser.state.value.getValue(platform)
            return !state.loading && state.pending == null && state.keys == old && !state.synced && state.error == error
        }

        private fun counts() = Counts(fetchRequests, writeRequests, verified.size)
        private fun otherStates(platform: Platform): Map<Platform, FavoritesState> =
            browser.state.value.filterKeys { it != platform }
        private fun seq(platform: Platform, suffix: String) = MediaKey(platform, "synthetic-$suffix")
        private fun keys(platform: Platform) = setOf(seq(platform, "saved"))
        private fun song(platform: Platform, suffix: String) =
            SongSummary(seq(platform, suffix), "synthetic", listOf("synthetic"), "synthetic", 1L)
        private fun nextFetch(platform: Platform) = fetches.removeAt(fetches.indexOfLast { it.platform == platform })
        private fun nextWrite(platform: Platform) = writes.removeAt(writes.indexOfLast { it.platform == platform })
        private operator fun Counts.minus(other: Counts) = Counts(
            fetches - other.fetches, writes - other.writes, verified - other.verified)
    }
}
