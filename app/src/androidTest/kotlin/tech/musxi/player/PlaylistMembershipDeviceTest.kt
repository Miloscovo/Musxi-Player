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
import tech.musxi.core.library.*

/** Select this class explicitly. Saves a fresh recovery record before any server write. */
class PlaylistMembershipDeviceTest {
    @Test fun kugouOrdinaryPlaylistRoundTrip() = roundTrip(Platform.KUGOU)
    @Test fun qqOrdinaryPlaylistRoundTrip() = roundTrip(Platform.QQ)
    @Test fun neteaseOrdinaryPlaylistRoundTrip() = roundTrip(Platform.NETEASE)

    private fun roundTrip(platform: Platform) {
        assumeTrue("Requires explicit temporary write-and-restore authorization", InstrumentationRegistry.getArguments()
            .getString("allowPlatformWrites") == "true")
        val phone = BackendBatch5DeviceTest.PhoneDriver()
        val app = phone.activity.application as MusxiApplication
        val report = JSONObject().put("platform", platform.name).put("started", System.currentTimeMillis())
        val pending = File(app.filesDir, "playlist-membership-recovery-${platform.name.lowercase()}.json")
        fun <T> await(block: ((Result<T>) -> Unit) -> Unit): T {
            val done = CompletableFuture<Result<T>>()
            phone.runOnIdle { block { done.complete(it) } }
            return done.get(180, TimeUnit.SECONDS).getOrThrow()
        }
        fun targets() = await<List<EditablePlaylist>> { app.playlists.listWritable(platform, it) }
        fun ids(target: EditablePlaylist) = await<List<MediaKey>> { app.playlists.playlistSongIds(target, it) }.map { it.id }
        fun cleanup() {
            if (!pending.exists()) return
            val saved = JSONObject(pending.readText())
            check(app.accounts.playbackIdentity(platform)?.owner == saved.getString("owner")) { "Recovery belongs to another account" }
            val target = targets().single { !it.favorite && it.playlist.key.id == saved.getString("playlist") }
            val original = saved.getJSONArray("original").let { rows -> List(rows.length()) { rows.getString(it) } }
            val song = SongSummary(MediaKey(platform, saved.getString("song")), saved.getString("title"),
                emptyList(), "", saved.getLong("duration"))
            check(song.key.id !in original) { "Recovery is only allowed to remove a newly added test song" }
            if (song.key.id in ids(target)) await<Unit> { app.playlists.removeFromPlaylist(song, target, it) }
            val restored = ids(target)
            report.put("baselineCount", original.size).put("restoredCount", restored.size)
                .put("orderRestored", restored == original)
            check(restored == original) { "Playlist differs from baseline; recovery record retained" }
            val favorite = targets().single { it.favorite }
            val originalFavorite = saved.getJSONArray("favorite").let { rows -> List(rows.length()) { rows.getString(it) } }
            check(ids(favorite) == originalFavorite) { "Ordinary-list operation changed favorites" }
            report.put("favoritesUnchanged", true)
            check(pending.renameTo(File(app.filesDir,
                "playlist-membership-restored-${platform.name.lowercase()}-${saved.getLong("started")}.json")))
        }
        try {
            phone.waitUntil(60_000) { app.accounts.states.value.getValue(platform).phase == AccountPhase.CONNECTED }
            cleanup() // Recover an interrupted opted-in run before selecting a new song.
            val available = targets()
            val target = available.firstOrNull { !it.favorite }
            assumeTrue("No existing writable ordinary playlist for $platform", target != null)
            target!!
            val original = ids(target)
            val favorite = available.single { it.favorite }
            val originalFavorite = ids(favorite)
            val songs = await<List<SongSummary>> { done -> when(platform) {
                Platform.KUGOU -> app.kugouLibrary.search("周杰伦 晴天", done)
                Platform.QQ -> app.qqLibrary.search("周杰伦 晴天", done)
                Platform.NETEASE -> app.library.search("周杰伦 晴天", done)
            } }
            val song = songs.first { it.key.id !in original }
            val snapshot = await<SongPlaylistMembership> { app.playlists.membershipFor(song.key, it) }
            check(snapshot.entries.single { it.target.playlist.key == target.playlist.key }.present == false)
            // Persist baseline and recovery intent before dispatch; a timeout may follow a successful server write.
            pending.writeText(JSONObject().put("owner", requireNotNull(app.accounts.playbackIdentity(platform)).owner)
                .put("started", report.getLong("started")).put("playlist", target.playlist.key.id)
                .put("song", song.key.id).put("title", song.title).put("duration", song.durationMs)
                .put("original", JSONArray(original)).put("favorite", JSONArray(originalFavorite)).toString())
            val added = await<PlaylistMembershipResult> {
                app.playlists.updateMembership(song, snapshot, mapOf(target.playlist.key to true), it)
            }
            assertTrue("Add not confirmed", added.successful)
            assertEquals(PlaylistChangeStatus.APPLIED, added.results.single().status)
            val after = ids(target)
            assertEquals(original.size + 1, after.size)
            assertTrue(song.key.id in after)
            assertEquals(original, after.filterNot { it == song.key.id })
            val repeated = await<PlaylistMembershipResult> {
                app.playlists.updateMembership(song, snapshot, mapOf(target.playlist.key to true), it)
            }
            assertEquals(PlaylistChangeStatus.UNCHANGED, repeated.results.single().status)
            assertEquals(after, ids(target))
            report.put("addConfirmed", true).put("repeatIdempotent", true)
            cleanup()
            report.put("status", "passed")
        } finally {
            try { cleanup() } finally {
                report.put("recoveryPending", pending.exists()).put("finished", System.currentTimeMillis())
                File(app.filesDir, "playlist-membership-${platform.name.lowercase()}.json").writeText(report.toString(2))
            }
        }
    }
}
