package tech.musxi.player

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.Platform
import tech.musxi.core.library.EditablePlaylist
import tech.musxi.core.library.LibraryPageFailure
import tech.musxi.core.library.MediaKey

/** Creates at most one ordinary test playlist. It remains in the account; no playlist is deleted. */
class PlaylistCreationDeviceTest {
    @Test fun qqCreateOrdinaryPlaylistOnce() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit create authorization", arguments.getString("allowPlatformWrites") == "true" &&
            arguments.getString("allowPlaylistCreate") == "true")
        val phone = BackendBatch5DeviceTest.PhoneDriver()
        val app = phone.activity.application as MusxiApplication
        val marker = File(app.filesDir, "playlist-created-qq-once.json")
        val report = JSONObject().put("started", System.currentTimeMillis()).put("status", "FAILED")
        fun <T> await(block: ((Result<T>) -> Unit) -> Unit): T {
            val result = CompletableFuture<Result<T>>()
            phone.runOnIdle { block { result.complete(it) } }
            return result.get(180, TimeUnit.SECONDS).getOrThrow()
        }
        try {
            phone.waitUntil(60_000) { app.accounts.states.value.getValue(Platform.QQ).phase == AccountPhase.CONNECTED }
            val identity = requireNotNull(app.accounts.playbackIdentity(Platform.QQ))
            val lists = await<List<EditablePlaylist>> { app.playlists.listWritable(Platform.QQ, it) }
            val markerName = "Musxi验收"
            if (marker.exists()) {
                val saved = JSONObject(marker.readText())
                check(saved.getString("owner") == identity.owner) { "Create marker belongs to another account" }
                if (!saved.has("playlist")) {
                    // Evidence only: an uncertain create must never be automatically repeated or assigned by name.
                    report.put("status", "UNKNOWN").put("createdAgain", false)
                        .put("matchingNameCount", lists.count { !it.favorite && it.playlist.title == markerName })
                        .put("ordinaryCount", lists.count { !it.favorite })
                    return
                }
                check(lists.any { !it.favorite && it.playlist.key.id == saved.getString("playlist") }) {
                    "Previously created playlist no longer present; do not create again"
                }
                report.put("status", "PREVIOUSLY_CONFIRMED").put("createdAgain", false)
                return
            }
            assumeTrue("An ordinary playlist already exists", lists.none { !it.favorite })
            // Save intent before dispatch. Lost create responses cannot safely be retried by name.
            marker.writeText(JSONObject().put("owner", identity.owner).put("started", report.getLong("started"))
                .put("name", markerName).put("state", "PENDING").toString())
            val created = await<EditablePlaylist> { app.playlists.createPlaylist(Platform.QQ, markerName, it) }
            check(!created.favorite && created.accountGeneration == identity.generation)
            marker.writeText(JSONObject(marker.readText()).put("playlist", created.playlist.key.id)
                .put("state", "CONFIRMED").toString())
            val ids = await<List<MediaKey>> { app.playlists.playlistSongIds(created, it) }
            check(ids.isEmpty()) { "Created playlist was not empty" }
            check(await<List<EditablePlaylist>> { app.playlists.listWritable(Platform.QQ, it) }
                .any { it.playlist.key == created.playlist.key && !it.favorite })
            report.put("status", "PASSED").put("emptyConfirmed", true).put("createdAgain", false)
                .put("playlistRetained", true)
        } catch (failure: Throwable) {
            report.put("error", (failure as? LibraryPageFailure)?.category ?: failure.javaClass.simpleName)
            throw failure
        } finally {
            report.put("finished", System.currentTimeMillis()).put("creationMarkerExists", marker.exists())
            File(app.filesDir, "playlist-creation-qq.json").writeText(report.toString(2))
        }
    }
}
