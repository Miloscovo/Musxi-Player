package tech.musxi.player

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import tech.musxi.core.account.*
import tech.musxi.core.library.*

class CatalogDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MusxiApplication
    private fun <T> await(block: ((Result<T>) -> Unit) -> Unit): Result<T> {
        val promise = CompletableFuture<Result<T>>()
        android.os.Handler(android.os.Looper.getMainLooper()).post { block { promise.complete(it) } }
        return promise.get(70, TimeUnit.SECONDS)
    }
    @Test fun readRealCatalogPages() {
        val report = JSONArray()
        val deadline = System.currentTimeMillis() + 60_000
        while (app.accounts.states.value.values.any { it.phase != AccountPhase.CONNECTED } && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
        }
        for (platform in Platform.entries) {
            if (app.accounts.states.value.getValue(platform).phase != AccountPhase.CONNECTED) continue
            val identity = app.accounts.playbackIdentity(platform) ?: continue
            val journal = await<tech.musxi.core.playback.PlaybackJournal> { app.accounts.playbackJournal(platform, identity, result = it) }.getOrThrow()
            val key = journal.queue?.songs?.getOrNull(journal.queue!!.index)?.key ?: continue
            val loaded = await<SongSummary> { done -> when (platform) {
                Platform.KUGOU -> app.kugouLibrary.song(key, done)
                Platform.QQ -> app.qqLibrary.song(key, done)
                Platform.NETEASE -> app.library.song(key, done)
            } }
            val song = if (platform == Platform.KUGOU) {
                val songs = await<List<SongSummary>> { app.kugouLibrary.search("周杰伦 晴天", it) }.getOrThrow()
                songs.first { it.artists == listOf("周杰伦") && it.title == "晴天" }
            } else loaded.getOrThrow()
            val row = JSONObject().put("platform", platform.name).put("songSuccess", true)
                .put("artistIds", song?.artistIds?.size ?: 0).put("albumId", song?.albumId != null)
            for (kind in CatalogKind.entries) {
                val id = if (kind == CatalogKind.ARTIST) song.artistIds.firstOrNull() else song.albumId
                if (id == null) { row.put(kind.name, "missing_id"); continue }
                val page = await<Page<SongSummary>> { app.catalog.tracks(CatalogKey(platform, kind, id), 0, it) }
                row.put(kind.name, JSONObject().put("success", page.isSuccess).put("count", page.getOrNull()?.items?.size)
                    .put("total", page.getOrNull()?.total).put("next", page.getOrNull()?.nextOffset)
                    .put("failure", (page.exceptionOrNull() as? LibraryPageFailure)?.category))
                File(app.filesDir, "batch4-catalog-device.json").writeText(JSONArray(report.toString()).put(row).toString(2))
                val first = page.getOrThrow()
                assertTrue("Catalog must contain real tracks", first.items.isNotEmpty())
                assertTrue(first.items.all { it.key.platform == platform && it.durationMs > 0 })
                first.nextOffset?.let { next ->
                    val second = await<Page<SongSummary>> { app.catalog.tracks(CatalogKey(platform, kind, id), next, it) }.getOrThrow()
                    row.getJSONObject(kind.name).put("secondCount", second.items.size).put("secondNext", second.nextOffset)
                    assertTrue(second.items.isNotEmpty())
                    assertTrue(first.items.map { it.key }.intersect(second.items.map { it.key }.toSet()).isEmpty())
                }
            }
            report.put(row)
            File(app.filesDir, "batch4-catalog-device.json").writeText(report.toString(2))
        }
        assertEquals("All three connected platforms must be checked", 3, report.length())
        for (i in 0 until report.length()) for (kind in CatalogKind.entries) {
            assertTrue(report.getJSONObject(i).getJSONObject(kind.name).getBoolean("success"))
        }
    }
}
