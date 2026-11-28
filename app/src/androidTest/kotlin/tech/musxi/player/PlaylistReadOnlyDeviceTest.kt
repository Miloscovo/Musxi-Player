package tech.musxi.player

import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.Platform
import tech.musxi.player.ui.library.LibraryFilter
import tech.musxi.player.ui.library.LibraryViewModel

/** Explicit read-only regression: no playlist writes, local imports, queue or playback commands. */
class PlaylistReadOnlyDeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext as MusxiApplication
    private fun main(action: () -> Unit) = instrument.runOnMainSync(action)

    @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
    @Test fun sameOwnerHomeReopenAndRefreshKeepVisibleModulesOnAllPlatforms() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowPlaylistReadOnly") == "true")
        val report = JSONObject().put("platforms", JSONArray())
        val output = File(app.filesDir, "library-home-stability-device-results.json")
        try {
            main { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            waitFor(90_000) { app.accounts.states.value.values.none { it.phase == AccountPhase.REVALIDATING } }
            for (platform in listOf(Platform.KUGOU, Platform.QQ, Platform.NETEASE)) {
                val row = JSONObject().put("platform", platform.name)
                report.getJSONArray("platforms").put(row)
                val phase = app.accounts.states.value.getValue(platform).phase
                row.put("accountPhase", phase.name)
                if (phase != AccountPhase.CONNECTED) {
                    row.put("result", "account_not_connected")
                    continue
                }
                val catalog = app.playlists.catalog(platform)
                main { catalog.open() }
                waitFor(90_000) { catalog.state.value.let { !it.loading && it.items.isNotEmpty() && it.error == null } }
                val cycles = JSONArray()
                row.put("cycles", cycles)
                repeat(3) { cycle ->
                    // Use the product's legacy-name fallback too: some QQ cache rows are OTHER.
                    val expectedLiked = catalog.state.value.items.filter { tech.musxi.player.ui.library.isLikedPlaylist(it) }.map { it.key }.toSet()
                    row.put("featuredLikedRows", expectedLiked.size)
                    var emptyEmissions = 0
                    var missingLiked = 0
                    var emissions = 0
                    val scope = CoroutineScope(Dispatchers.Main.immediate)
                    try {
                        main {
                            scope.launch {
                                catalog.state.collect { state ->
                                    emissions++
                                    if (state.items.isEmpty()) emptyEmissions++
                                    if (!state.items.map { it.key }.containsAll(expectedLiked)) missingLiked++
                                }
                            }
                            catalog.cancel()
                            catalog.open()
                        }
                        waitFor(90_000) { !catalog.state.value.loading }
                        main { catalog.refresh() }
                        waitFor(90_000) { !catalog.state.value.loading }
                        main {
                            cycles.put(JSONObject().put("cycle", cycle).put("emissions", emissions)
                                .put("emptyEmissions", emptyEmissions).put("missingLikedEmissions", missingLiked)
                                .put("finalCount", catalog.state.value.items.size)
                                .put("error", catalog.state.value.error ?: JSONObject.NULL))
                            scope.cancel()
                        }
                        output.writeText(report.toString(2))
                        assertTrue("$platform transient empty/liked removal: $report", emptyEmissions == 0 && missingLiked == 0)
                        assertTrue("$platform refresh failed: $report", catalog.state.value.error == null)
                    } finally { main { scope.cancel() } }
                }
                row.put("result", "passed")
            }
            report.put("result", "passed")
        } catch (error: Throwable) {
            report.put("result", "failed").put("failureType", error.javaClass.simpleName)
            throw error
        } finally { output.writeText(report.toString(2)) }
    }

    @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
    @Test fun kugouMenuPermissionsAreReadableWithoutMutations() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowPlaylistReadOnly") == "true")
        val report = JSONObject().put("rows", JSONArray())
        val output = File(app.filesDir, "playlist-permissions-device-results.json")
        try {
            main { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            waitFor(90_000) { app.accounts.states.value.getValue(Platform.KUGOU).phase == AccountPhase.CONNECTED }
            if (InstrumentationRegistry.getArguments().getString("allowKugouPermissionSchema") == "true") {
                val schemaFinished = java.util.concurrent.atomic.AtomicBoolean()
                main {
                    app.accounts.withKugouSession({ api, session ->
                        tech.musxi.loginpoc.KugouPermissionReadProbe.read(api, session)
                    }, { result ->
                        result.onSuccess { report.put("schema", it) }
                        schemaFinished.set(true)
                    })
                }
                waitFor(90_000) { schemaFinished.get() }
            }
            val catalog = app.playlists.catalog(Platform.KUGOU)
            main { catalog.open() }
            waitFor(60_000) { catalog.state.value.let { !it.loading && (it.items.isNotEmpty() || it.error != null) } }
            val rows = catalog.state.value.items.filter { it.kind != tech.musxi.core.library.PlaylistKind.LIKED }
            assertTrue("Need existing playlist rows", rows.isNotEmpty())
            var failures = 0
            for (playlist in rows) {
                val row = JSONObject().put("kind", playlist.kind.name)
                report.getJSONArray("rows").put(row)
                val finished = java.util.concurrent.atomic.AtomicBoolean()
                main {
                    app.playlists.managementInfo(playlist.key) { result ->
                        result.onSuccess { info ->
                            if (info.target.playlist.key != playlist.key) failures++
                            row.put("canRename", info.canRename).put("canDelete", info.canDelete)
                                .put("targetMatches", info.target.playlist.key == playlist.key)
                        }.onFailure { error ->
                            failures++
                            row.put("error", (error as? tech.musxi.core.library.LibraryPageFailure)?.category ?: "unclassified")
                        }
                        finished.set(true)
                    }
                }
                waitFor(90_000) { finished.get() }
                output.writeText(report.toString(2))
            }
            assertTrue("Permission reads failed: $report", failures == 0)
            report.put("result", "passed")
        } catch (error: Throwable) {
            report.put("result", "failed").put("failureType", error.javaClass.simpleName)
            throw error
        } finally { output.writeText(report.toString(2)) }
    }

    @Test fun kugouRefreshAndAutomaticContinuationSettle() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowPlaylistReadOnly") == "true")
        val report = JSONObject().put("transitions", JSONArray())
        val output = File(app.filesDir, "playlist-refresh-device-results.json")
        lateinit var catalog: tech.musxi.core.library.PlaylistCatalog
        var last = ""
        try {
            main { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            waitFor(90_000) { app.accounts.states.value.getValue(Platform.KUGOU).phase == AccountPhase.CONNECTED }
            instrument.waitForIdleSync()
            SystemClock.sleep(3_000)
            main {
                catalog = app.playlists.catalog(Platform.KUGOU)
                catalog.open()
            }
            val cycles = JSONArray()
            report.put("cycles", cycles)
            repeat(4) { cycle ->
                if (cycle > 0) main { catalog.refresh() }
                val deadline = SystemClock.elapsedRealtime() + 30_000
                var calls = 0
                var settledAt = 0L
                while (SystemClock.elapsedRealtime() < deadline) {
                    main {
                        val state = catalog.state.value
                        val signature = "$cycle/${state.revision}/${state.items.size}/${state.nextOffset}/${state.loading}/${state.complete}/${state.error}"
                        if (signature != last) {
                            report.getJSONArray("transitions").put(JSONObject().put("cycle", cycle)
                                .put("elapsedMs", 30_000 - (deadline - SystemClock.elapsedRealtime()))
                                .put("revision", state.revision).put("count", state.items.size)
                                .put("next", state.nextOffset ?: JSONObject.NULL).put("loading", state.loading)
                                .put("complete", state.complete).put("error", state.error ?: JSONObject.NULL))
                            last = signature
                        }
                        if (!state.loading && state.error == null && state.nextOffset != null) {
                            calls++
                            if (calls <= 64) catalog.loadMore()
                        }
                        if (state.complete && !state.loading && state.error == null) {
                            if (settledAt == 0L) settledAt = SystemClock.elapsedRealtime()
                        } else settledAt = 0L
                    }
                    if (calls > 64 || (settledAt != 0L && SystemClock.elapsedRealtime() - settledAt >= 3_000)) break
                    SystemClock.sleep(20)
                }
                cycles.put(JSONObject().put("cycle", cycle).put("loadMoreCalls", calls)
                    .put("count", catalog.state.value.items.size).put("complete", catalog.state.value.complete))
                assertTrue("Refresh/continuation must settle: $report", settledAt != 0L && calls <= 64)
            }
            report.put("result", "passed")
        } catch (error: Throwable) {
            report.put("result", "failed").put("failureType", error.javaClass.simpleName)
            throw error
        } finally {
            output.writeText(report.toString(2))
        }
    }

    @Test fun catalogAndDetailsKeepTheirIndependentSubscriptions() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowPlaylistReadOnly") == "true")
        val report = JSONObject().put("platforms", JSONArray())
        val output = File(app.filesDir, "playlist-readonly-device-results.json")
        fun save() = output.writeText(report.toString(2))
        val store = ViewModelStore()
        lateinit var vm: LibraryViewModel
        try {
            main { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            waitFor(30_000) { app.localAudio.state.value.let { it.ready || it.error != null } }
            report.put("localReady", app.localAudio.state.value.ready)
                .put("localItemCount", app.localAudio.state.value.items.size)
                .put("dsfEnabled", app.dsfPlaybackEnabled)
            save()
            assertTrue("Local library must remain readable", app.localAudio.state.value.ready)
            waitFor(90_000) { app.accounts.states.value.values.none { it.phase == AccountPhase.REVALIDATING } }
            report.put("accountPhases", JSONObject().also { phases ->
                app.accounts.states.value.forEach { (platform, state) -> phases.put(platform.name, state.phase.name) }
            })
            save()
            main { vm = LibraryViewModel(app); store.put("read-only", vm) }
            for (platform in listOf(Platform.KUGOU, Platform.QQ, Platform.NETEASE)) {
                val row = JSONObject().put("platform", platform.name)
                report.getJSONArray("platforms").put(row)
                if (app.accounts.states.value.getValue(platform).phase != AccountPhase.CONNECTED) {
                    row.put("result", "account_not_connected"); save(); continue
                }
                main { vm.selectFilter(LibraryFilter.entries.first { it.platform == platform }) }
                waitFor(90_000) { vm.playlists.value.let { !it.loading && (it.items.isNotEmpty() || it.error != null || it.complete) } }
                val catalog = vm.playlists.value
                row.put("catalogCount", catalog.items.size).put("catalogError", catalog.error)
                    .put("complete", catalog.complete).put("verified", catalog.accountVerified)
                save()
                assertTrue("${platform.name} catalog: ${catalog.error}", catalog.error == null && catalog.accountVerified)
                val playlist = catalog.items.firstOrNull { it.trackCount > 0 }
                assertTrue("${platform.name} needs an existing nonempty playlist for this regression", playlist != null)
                main { vm.openPlaylist(playlist!!) }
                waitFor(90_000) { !vm.tracks.value.loading }
                val details = vm.tracks.value
                row.put("detailCount", details.items.size).put("detailError", details.error)
                    .put("result", if (details.error == null && details.items.isNotEmpty()) "passed" else "failed")
                save()
                assertTrue("${platform.name} details: ${details.error}", details.error == null && details.items.isNotEmpty())
                main { vm.closePlaylist() }
            }
            report.put("result", "passed")
        } catch (error: Throwable) {
            report.put("result", "failed").put("failureType", error.javaClass.simpleName)
            throw error
        } finally {
            main { store.clear() }
            save()
        }
    }

    private fun waitFor(timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        error("Read-only state wait timed out")
    }
}
