package tech.musxi.player

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.SongSummary

/** Explicit opt-in only. Persist recovery data before writes; verify the full server order after removal. */
class FavoritesRoundTripDeviceTest {
    @Test fun qqFavoriteRoundTrip() = roundTrip(Platform.QQ)
    @Test fun neteaseFavoriteRoundTrip() = roundTrip(Platform.NETEASE)

    private fun roundTrip(platform: Platform) {
        assumeTrue("Requires explicit real-platform write authorization", InstrumentationRegistry.getArguments()
            .getString("allowPlatformWrites") == "true")
        val phone = BackendBatch5DeviceTest.PhoneDriver()
        val app = phone.activity.application as MusxiApplication
        val report = JSONObject().put("platform", platform.name).put("started", System.currentTimeMillis())
        val pending = File(app.filesDir, "v02-favorite-recovery-${platform.name.lowercase()}.json")
        fun <T> await(block: ((Result<T>) -> Unit) -> Unit): T {
            val done = CompletableFuture<Result<T>>()
            phone.runOnIdle { block { done.complete(it) } }
            return done.get(45, TimeUnit.SECONDS).getOrThrow()
        }
        fun refresh() {
            phone.waitUntil(30_000) { app.favorites.state.value.getValue(platform).let {
                !it.loading && it.pending == null && it.queuedKeys.isEmpty()
            } }
            phone.runOnIdle { app.favorites.refresh(platform) }
            phone.waitUntil(45_000) { !app.favorites.state.value.getValue(platform).loading }
            val state = app.favorites.state.value.getValue(platform)
            check(state.synced && state.error == null && state.canWrite)
        }
        fun cleanup() {
            if (!pending.exists()) return
            val saved = JSONObject(pending.readText())
            check(app.accounts.playbackIdentity(platform)?.owner == saved.getString("owner"))
            refresh()
            val song = SongSummary(MediaKey(platform, saved.getString("id")), saved.getString("title"),
                emptyList(), "", saved.getLong("duration"))
            if (song.key in app.favorites.state.value.getValue(platform).keys)
                await<Unit> { app.favorites.enqueueLiked(song, false, it) }
            refresh()
            val original = saved.getJSONArray("original").let { ids -> List(ids.length()) { ids.getString(it) } }
            val actual = app.favorites.state.value.getValue(platform).keys.map { it.id }
            report.put("restoredCount", actual.size).put("setRestored", actual.toSet() == original.toSet())
                .put("orderRestored", actual == original)
            check(actual == original) { "Favorite recovery is still pending" }
            check(pending.renameTo(File(app.filesDir, "v02-favorite-restored-${platform.name.lowercase()}-${saved.getLong("started")}.json")))
        }
        try {
            phone.waitUntil(60_000) { app.accounts.states.value.getValue(platform).phase == AccountPhase.CONNECTED }
            // A previous interrupted run must recover before a new mutation can start.
            cleanup()
            refresh()
            val original = app.favorites.state.value.getValue(platform).keys.toList()
            val songs = await<List<SongSummary>> { done -> when (platform) {
                Platform.QQ -> app.qqLibrary.search("周杰伦 晴天", done)
                Platform.NETEASE -> app.library.search("周杰伦 晴天", done)
                else -> error("Use the existing Kugou live_favorites case")
            } }
            val song = songs.first { it.key !in original }
            val owner = requireNotNull(app.accounts.playbackIdentity(platform)).owner
            pending.writeText(JSONObject().put("owner", owner).put("started", report.getLong("started"))
                .put("id", song.key.id).put("title", song.title).put("duration", song.durationMs)
                .put("original", JSONArray(original.map { it.id })).toString())
            report.put("baselineCount", original.size)
            await<Unit> { app.favorites.enqueueLiked(song, true, it) }
            refresh()
            assertTrue(song.key in app.favorites.state.value.getValue(platform).keys)
            report.put("addConfirmed", true).put("countAfterAdd", app.favorites.state.value.getValue(platform).keys.size)
        } finally {
            try { cleanup() } finally {
                phone.runOnIdle { app.playback.pause() }
                report.put("recoveryPending", pending.exists()).put("finished", System.currentTimeMillis())
                File(app.filesDir, "v02-favorite-${platform.name.lowercase()}.json").writeText(report.toString(2))
            }
        }
    }
}
