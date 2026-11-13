package tech.musxi.player

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.Platform
import tech.musxi.core.cache.CacheKind
import tech.musxi.core.cache.CacheOperationResult
import tech.musxi.core.library.CachedPlaylistSearchResult
import tech.musxi.core.library.EditablePlaylist
import tech.musxi.core.playback.PlaybackJournal

/** Reads existing on-device caches without clearing accounts, metadata or playback history. */
class BackendCacheReadDeviceTest {
    @Test fun measureCachesAndSearchRetainedPlaylistPagesWithoutNetworkSearch() {
        val phone = BackendBatch5DeviceTest.PhoneDriver()
        val app = phone.activity.application as MusxiApplication
        fun <T> await(block: ((Result<T>) -> Unit) -> Unit): T {
            val done = CompletableFuture<Result<T>>()
            phone.runOnIdle { block { done.complete(it) } }
            return done.get(40, TimeUnit.SECONDS).getOrThrow()
        }
        phone.waitUntil(60_000) { app.accounts.states.value.values.all { it.phase == AccountPhase.CONNECTED } }
        val identities = Platform.entries.associateWith { requireNotNull(app.accounts.playbackIdentity(it)) }
        val journals = identities.mapValues { (platform, identity) ->
            await<PlaybackJournal> { app.accounts.playbackJournal(platform, identity, result = it) }
        }
        val queue = app.playback.queueState.value
        val result = CompletableFuture<CacheOperationResult>()
        phone.runOnIdle { app.cache.refresh { result.complete(it) } }
        val usage = result.get(40, TimeUnit.SECONDS)
        assertTrue(usage.succeeded)
        assertEquals(CacheKind.entries.toSet(), usage.usage.bytes.keys)
        assertTrue(usage.usage.bytes.values.all { it >= 0 })
        val rows = JSONArray()
        for (platform in Platform.entries) {
            val list = await<List<EditablePlaylist>> { app.playlists.listWritable(platform, it) }
                .first { it.favorite }.playlist
            // Favorite round trips intentionally invalidate old pages. Populate a
            // fresh retained page through the real browser before checking search.
            val browser = when (platform) {
                Platform.KUGOU -> app.kugouLibraryBrowser
                Platform.QQ -> app.qqLibraryBrowser
                Platform.NETEASE -> app.libraryBrowser
            }
            phone.runOnIdle { browser.tracks(list.key, refresh = true) }
            phone.waitUntil(30_000) { !browser.state.value.refreshing && browser.state.value.error == null }
            var all = await<CachedPlaylistSearchResult> { app.accounts.searchCachedPlaylist(list.key, "", it) }
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            while (all.loadedCount == 0 && android.os.SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(100)
                all = await { app.accounts.searchCachedPlaylist(list.key, "", it) }
            }
            assertTrue("No retained pages for $platform", all.loadedCount > 0)
            val query = all.items.first().title
            val before = app.accounts.requestTimingSnapshot().sumOf { it.count }
            val found = await<CachedPlaylistSearchResult> { app.accounts.searchCachedPlaylist(list.key, query, it) }
            assertTrue(found.items.any { it.key == all.items.first().key })
            assertEquals(before, app.accounts.requestTimingSnapshot().sumOf { it.count })
            rows.put(JSONObject().put("platform", platform.name).put("loadedCount", all.loadedCount)
                .put("complete", all.complete).put("matched", found.items.size).put("networkSearchAbsent", true))
        }
        assertEquals(identities, Platform.entries.associateWith { app.accounts.playbackIdentity(it) })
        for ((platform, journal) in journals) assertEquals(journal,
            await<PlaybackJournal> { app.accounts.playbackJournal(platform, identities.getValue(platform), result = it) })
        assertEquals(queue, app.playback.queueState.value)
        File(app.filesDir, "v02-cache-read-device.json").writeText(JSONObject().put("status", "passed")
            .put("bytes", JSONObject(usage.usage.bytes.mapKeys { it.key.name }))
            .put("cachedSearch", rows).put("accountsQueuesAndJournalsUnchanged", true).toString(2))
    }
}
