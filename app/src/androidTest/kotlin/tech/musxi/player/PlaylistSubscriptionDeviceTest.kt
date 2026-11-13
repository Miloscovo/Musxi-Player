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

/** Explicit, recoverable subscription check. Only a previously-unfollowed public target is changed. */
class PlaylistSubscriptionDeviceTest {
    @Test fun neteaseSubscriptionRoundTrip() = roundTrip(Platform.NETEASE, "neteasePlaylist")
    @Test fun qqSubscriptionRoundTrip() = roundTrip(Platform.QQ, "qqPlaylist")

    @Test fun qqPlaylistDirectoryRead() {
        val phone = BackendBatch5DeviceTest.PhoneDriver()
        val app = phone.activity.application as MusxiApplication
        val report = JSONObject().put("started", System.currentTimeMillis())
        try {
            phone.waitUntil(60_000) { app.accounts.states.value.getValue(Platform.QQ).phase == AccountPhase.CONNECTED }
            val lists = mutableListOf<PlaylistSummary>()
            var offset = 0
            do {
                val page = await<Page<PlaylistSummary>>(phone) { app.qqLibrary.playlists(offset, it) }
                lists += page.items
                check(lists.size <= 500)
                val next = page.nextOffset
                if (next == null) break
                check(next > offset)
                offset = next
            } while (true)
            report.put("createdCount", lists.count { it.kind == PlaylistKind.CREATED })
                .put("collectedCount", lists.count { it.kind == PlaylistKind.COLLECTED })
                .put("createdDirectoryValues", JSONArray(lists.filter { it.kind == PlaylistKind.CREATED }
                    .map { it.key.id.substringAfterLast('_') }.distinct()))
            val writable = await<List<EditablePlaylist>>(phone) { app.playlists.listWritable(Platform.QQ, it) }
            report.put("ordinaryWritableCount", writable.count { !it.favorite }).put("status", "PASSED")
        } catch (failure: Throwable) {
            report.put("status", "FAILED").put("error", category(failure))
            throw failure
        } finally {
            report.put("finished", System.currentTimeMillis())
            File(app.filesDir, "playlist-directory-qq.json").writeText(report.toString(2))
        }
    }

    private fun roundTrip(platform: Platform, argument: String) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit temporary subscription authorization", arguments.getString("allowPlatformWrites") == "true" &&
            arguments.getString("allowPlaylistSubscription") == "true")
        val candidate = arguments.getString(argument)
        assumeTrue("Requires an explicitly selected public playlist", !candidate.isNullOrBlank())
        val phone = BackendBatch5DeviceTest.PhoneDriver()
        val app = phone.activity.application as MusxiApplication
        val report = JSONObject().put("platform", platform.name).put("started", System.currentTimeMillis()).put("status", "FAILED")
        val pending = File(app.filesDir, "playlist-subscription-recovery-${platform.name.lowercase()}.json")
        fun snapshot(key: MediaKey) = await<PlaylistSubscription>(phone) { app.playlists.playlistSubscription(key, it) }
        fun lists(): List<PlaylistSummary> {
            val all = mutableListOf<PlaylistSummary>()
            var offset = 0
            while (true) {
                val page = await<Page<PlaylistSummary>>(phone) { done -> when (platform) {
                    Platform.QQ -> app.qqLibrary.playlists(offset, done)
                    Platform.NETEASE -> app.library.playlists(offset, done)
                    else -> error("Unsupported subscription platform")
                } }
                all += page.items
                check(all.size <= 500)
                val next = page.nextOffset ?: return all
                check(next > offset)
                offset = next
            }
        }
        fun canonical(id: String) = if (platform == Platform.QQ) id.substringBefore('_') else id
        fun listKeys() = lists().map { canonical(it.key.id) }.sorted()
        fun writableContents(): JSONObject {
            val contents = JSONObject()
            val writable = await<List<EditablePlaylist>>(phone) { app.playlists.listWritable(platform, it) }
            for (target in writable) {
                val ids = await<List<MediaKey>>(phone) { app.playlists.playlistSongIds(target, it) }
                contents.put(target.playlist.key.id, JSONArray(ids.map { it.id }))
            }
            return contents
        }
        fun sameContents(expected: JSONObject) {
            val actual = writableContents()
            check(actual.keys().asSequence().toSet() == expected.keys().asSequence().toSet()) { "Writable targets changed during test" }
            for (id in expected.keys()) check(actual.getJSONArray(id).toString() == expected.getJSONArray(id).toString()) {
                "Subscription operation changed owned playlist contents"
            }
        }
        fun cleanup() {
            if (!pending.exists()) return
            val saved = JSONObject(pending.readText())
            check(app.accounts.playbackIdentity(platform)?.owner == saved.getString("owner")) { "Recovery account changed" }
            check(!saved.getBoolean("originalFollowed")) { "Only newly followed test targets may be removed" }
            val key = MediaKey(platform, saved.getString("playlist"))
            val current = snapshot(key)
            if (current.followed) {
                val removed = await<PlaylistFollowResult>(phone) { app.playlists.setPlaylistFollowed(current, false, it) }
                check(removed.status in setOf(PlaylistChangeStatus.APPLIED, PlaylistChangeStatus.UNCHANGED)) {
                    "Subscription recovery not confirmed"
                }
            }
            check(!snapshot(key).followed) { "Subscription recovery unreadable" }
            val originalKeys = saved.getJSONArray("originalKeys").let { a -> List(a.length()) { a.getString(it) } }
            check(listKeys() == originalKeys) { "Account playlist set changed; recovery retained" }
            sameContents(saved.getJSONObject("contents"))
            report.put("subscriptionRestored", true).put("ownedSongsAndFavoritesUnchanged", true)
            check(pending.renameTo(File(app.filesDir, "playlist-subscription-restored-${platform.name.lowercase()}-${saved.getLong("started")}.json")))
        }
        try {
            phone.waitUntil(60_000) { app.accounts.states.value.getValue(platform).phase == AccountPhase.CONNECTED }
            cleanup()
            val key = MediaKey(platform, requireNotNull(candidate))
            val initial = snapshot(key)
            report.put("initialReadConfirmed", true)
            assumeTrue("Candidate is already followed; no existing subscription is removed", !initial.followed)
            val originalKeys = listKeys()
            check(canonical(key.id) !in originalKeys)
            val contents = writableContents()
            pending.writeText(JSONObject().put("owner", requireNotNull(app.accounts.playbackIdentity(platform)).owner)
                .put("started", report.getLong("started")).put("playlist", key.id).put("originalFollowed", false)
                .put("originalKeys", JSONArray(originalKeys)).put("contents", contents).toString())
            val followed = await<PlaylistFollowResult>(phone) { app.playlists.setPlaylistFollowed(initial, true, it) }
            report.put("followStatus", followed.status.name).put("followError", followed.error)
            check(followed.status == PlaylistChangeStatus.APPLIED && followed.confirmedFollowed == true) { "Follow not confirmed" }
            check(snapshot(key).followed)
            check(listKeys() == (originalKeys + canonical(key.id)).sorted()) { "Unexpected subscription list diff" }
            val repeated = await<PlaylistFollowResult>(phone) { app.playlists.setPlaylistFollowed(initial, true, it) }
            check(repeated.status == PlaylistChangeStatus.UNCHANGED)
            report.put("repeatIdempotent", true)
            cleanup()
            report.put("status", "PASSED")
        } catch (skipped: AssumptionViolatedException) {
            report.put("status", "SKIPPED").put("error", "candidate_already_followed")
            throw skipped
        } catch (failure: Throwable) {
            report.put("error", category(failure))
            throw failure
        } finally {
            try { cleanup() } catch (failure: Throwable) {
                report.put("status", "FAILED").put("recoveryError", category(failure))
                throw failure
            } finally {
                report.put("recoveryPending", pending.exists()).put("finished", System.currentTimeMillis())
                File(app.filesDir, "playlist-subscription-${platform.name.lowercase()}.json").writeText(report.toString(2))
            }
        }
    }

    private fun category(failure: Throwable) = (failure as? LibraryPageFailure)?.category ?: failure.javaClass.simpleName
    private fun <T> await(phone: BackendBatch5DeviceTest.PhoneDriver, block: ((Result<T>) -> Unit) -> Unit): T {
        val future = CompletableFuture<Result<T>>()
        phone.runOnIdle { block { future.complete(it) } }
        return future.get(180, TimeUnit.SECONDS).getOrThrow()
    }
}
