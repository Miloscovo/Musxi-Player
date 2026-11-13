package tech.musxi.player

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Test
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*

/** Select explicitly: only a song absent from both existing targets is temporarily changed. */
class PlaylistMultiMembershipDeviceTest {
    @Test fun qqTwoPlaylistsRemoveOneAndKeepOne() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allowPlatformWrites") == "true" &&
            args.getString("allowMultiPlaylistWrites") == "true")
        val phone = BackendBatch5DeviceTest.PhoneDriver()
        val app = phone.activity.application as MusxiApplication
        val pending = File(app.filesDir, "playlist-multi-recovery-qq.json")
        val report = JSONObject().put("started", System.currentTimeMillis()).put("status", "FAILED")
        fun <T> await(block: ((Result<T>) -> Unit) -> Unit): T {
            val future = CompletableFuture<Result<T>>()
            phone.runOnIdle { block { future.complete(it) } }
            return future.get(180, TimeUnit.SECONDS).getOrThrow()
        }
        fun targets() = await<List<EditablePlaylist>> { app.playlists.listWritable(Platform.QQ, it) }
        fun ids(target: EditablePlaylist) = await<List<MediaKey>> { app.playlists.playlistSongIds(target, it) }
            .map { it.id }
        fun strings(rows: JSONArray) = List(rows.length()) { rows.getString(it) }
        fun cleanup() {
            if (!pending.exists()) return
            val saved = JSONObject(pending.readText())
            check(app.accounts.playbackIdentity(Platform.QQ)?.owner == saved.getString("owner"))
            val song = SongSummary(MediaKey(Platform.QQ, saved.getString("song")), saved.getString("title"),
                emptyList(), "", saved.getLong("duration"))
            val baseline = saved.getJSONObject("contents")
            for (key in baseline.keys()) {
                val original = strings(baseline.getJSONArray(key))
                check(song.key.id !in original)
                val target = targets().single { !it.favorite && it.playlist.key.id == key }
                if (song.key.id in ids(target)) await<Unit> { app.playlists.removeFromPlaylist(song, target, it) }
                check(ids(target) == original) { "Target content differs from baseline" }
            }
            check(ids(targets().single { it.favorite }) == strings(saved.getJSONArray("favorites")))
            report.put("bothRestored", true).put("favoritesUnchanged", true)
            check(pending.renameTo(File(app.filesDir, "playlist-multi-restored-qq-${saved.getLong("started")}.json")))
        }
        try {
            phone.waitUntil(60_000) { app.accounts.states.value.getValue(Platform.QQ).phase == AccountPhase.CONNECTED }
            cleanup()
            val available = targets()
            val ordinary = available.filter { !it.favorite }.take(2)
            assumeTrue("Requires two existing ordinary QQ playlists", ordinary.size == 2)
            val before = ordinary.associate { it.playlist.key.id to ids(it) }
            val favorites = ids(available.single { it.favorite })
            val songs = await<List<SongSummary>> { app.qqLibrary.search("周杰伦 晴天", it) }
            val song = songs.first { candidate -> before.values.none { candidate.key.id in it } }
            val snapshot = await<SongPlaylistMembership> { app.playlists.membershipFor(song.key, it) }
            for (target in ordinary) check(!snapshot.entries.single { it.target.playlist.key == target.playlist.key }.present)
            val contents = JSONObject()
            before.forEach { (key, value) -> contents.put(key, JSONArray(value)) }
            pending.writeText(JSONObject().put("started", report.getLong("started"))
                .put("owner", requireNotNull(app.accounts.playbackIdentity(Platform.QQ)).owner)
                .put("song", song.key.id).put("title", song.title).put("duration", song.durationMs)
                .put("contents", contents).put("favorites", JSONArray(favorites)).toString())
            val added = await<PlaylistMembershipResult> {
                app.playlists.updateMembership(song, snapshot, ordinary.associate { target -> target.playlist.key to true }, it)
            }
            check(added.successful && added.results.size == 2 && added.results.all { it.status == PlaylistChangeStatus.APPLIED })
            for (target in ordinary) {
                val after = ids(target)
                check(after.size == before.getValue(target.playlist.key.id).size + 1 && song.key.id in after)
                check(after.filterNot { it == song.key.id } == before.getValue(target.playlist.key.id))
            }
            report.put("addedToBoth", true)
            val fresh = await<SongPlaylistMembership> { app.playlists.membershipFor(song.key, it) }
            val desired = linkedMapOf(ordinary[0].playlist.key to false, ordinary[1].playlist.key to true)
            val changed = await<PlaylistMembershipResult> { app.playlists.updateMembership(song, fresh, desired, it) }
            check(changed.successful && changed.results.size == 2)
            check(changed.results.single { it.playlist == ordinary[0].playlist.key }.status == PlaylistChangeStatus.APPLIED)
            check(changed.results.single { it.playlist == ordinary[1].playlist.key }.status == PlaylistChangeStatus.UNCHANGED)
            check(ids(ordinary[0]) == before.getValue(ordinary[0].playlist.key.id))
            val kept = ids(ordinary[1])
            check(song.key.id in kept && kept.filterNot { it == song.key.id } == before.getValue(ordinary[1].playlist.key.id))
            report.put("removedFromFirstKeptInSecond", true).put("keepWasNoOp", true)
                .put("baselineCounts", JSONArray(ordinary.map { before.getValue(it.playlist.key.id).size }))
            cleanup()
            report.put("status", "PASSED")
        } catch (failure: Throwable) {
            report.put("status", if (failure is AssumptionViolatedException) "SKIPPED" else "FAILED")
                .put("error", (failure as? LibraryPageFailure)?.category ?: failure.javaClass.simpleName)
            throw failure
        } finally {
            try { cleanup() } finally {
                report.put("recoveryPending", pending.exists()).put("finished", System.currentTimeMillis())
                File(app.filesDir, "playlist-multi-qq.json").writeText(report.toString(2))
            }
        }
    }
}
