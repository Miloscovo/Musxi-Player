package tech.musxi.player

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tech.musxi.core.account.Platform
import tech.musxi.core.playback.QualityOptions
import tech.musxi.core.update.*

/** Live read-only account checks and checkpoint; never changes credentials or platform favourites. */
@RunWith(AndroidJUnit4::class)
class BackendBatch3DeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun developmentMetadataAndDownloadGuard() {
        val app = compose.activity.application as MusxiApplication
        val result = CompletableFuture<UpdateCheckResult>()
        var callbackOnMain = false
        compose.runOnIdle { app.updates.check { value ->
            callbackOnMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
            result.complete(value)
        } }
        val legacy = result.get(35, TimeUnit.SECONDS)
        val checked = app.updates.state.value
        val report = JSONObject().put("development", checked.developmentBuild)
            .put("check", checked.check.phase.name).put("reason", checked.check.reason?.name)
            .put("latest", checked.latest?.version).put("canDownload", checked.latest?.canDownload)
            .put("callbackOnMain", callbackOnMain)
        File(app.filesDir, "batch3-development-device.json").writeText(report.toString(2))
        assertTrue(checked.developmentBuild)
        assertEquals(UpdateCheckPhase.SUCCEEDED, checked.check.phase)
        assertNotNull(checked.latest)
        assertFalse(checked.latest!!.newer)
        assertFalse(checked.latest!!.canDownload)
        assertTrue(callbackOnMain)
        assertEquals(UpdateCheckResult.NoUpdate, legacy)
        compose.runOnIdle { app.updates.startDownload(checked.latest!!.version) }
        assertEquals(UpdateFailure.DEVELOPMENT_BUILD, app.updates.state.value.download.reason)
        compose.runOnIdle { app.updates.install() }
        assertEquals(UpdateFailure.DEVELOPMENT_BUILD, app.updates.state.value.download.reason)
    }

    @Test fun kugouMenuCachesVerifiedOptions() {
        val app = compose.activity.application as MusxiApplication
        compose.waitUntil(35_000) { app.playbackJournal.state.value.getValue(Platform.KUGOU).let {
            it.accountVerified && !it.loading && it.queue != null
        } }
        val original = app.playbackJournal.state.value.getValue(Platform.KUGOU).queue!!
        val song = original.songs[original.index]
        val report = JSONObject().put("queueSizeBefore", original.songs.size).put("queueIndexBefore", original.index)
        val parts = song.key.id.split('_')
        report.put("canonicalId", parts.size == 3 && parts[0].matches(Regex("[a-f0-9]{32}")))
            .put("albumPresent", parts.getOrNull(1)?.toLongOrNull()?.let { it > 0 } == true)
            .put("audioPresent", parts.getOrNull(2)?.toLongOrNull()?.let { it > 0 } == true)
        fun flush() { File(app.filesDir, "batch3-kugou-device.json").writeText(report.toString(2)) }
        fun menu(): Result<QualityOptions> {
            val done = CompletableFuture<Result<QualityOptions>>()
            compose.runOnIdle { app.kugouLibrary.qualities(song.key) { done.complete(it) } }
            return done.get(90, TimeUnit.SECONDS)
        }
        var started = android.os.SystemClock.elapsedRealtime()
        val first = menu()
        report.put("firstMs", android.os.SystemClock.elapsedRealtime() - started)
            .put("firstSuccess", first.isSuccess)
            .put("failure", (first.exceptionOrNull() as? tech.musxi.core.library.LibraryPageFailure)?.category)
        flush()
        val options = first.getOrThrow()
        assertTrue(options.options.isNotEmpty())
        started = android.os.SystemClock.elapsedRealtime()
        val second = menu().getOrThrow()
        report.put("secondMs", android.os.SystemClock.elapsedRealtime() - started)
            .put("sameOptions", options == second).put("options", org.json.JSONArray(options.options.map { it.id }))
        flush()
        assertEquals(options, second)
    }

    @Test fun playbackCheckpointPreservesQueue() {
        val app = compose.activity.application as MusxiApplication
        compose.waitUntil(35_000) { app.playbackJournal.state.value.getValue(Platform.KUGOU).let {
            it.accountVerified && !it.loading && it.queue != null
        } }
        val original = app.playbackJournal.state.value.getValue(Platform.KUGOU).queue!!
        val report = JSONObject().put("queueSizeBefore", original.songs.size).put("queueIndexBefore", original.index)
        fun flush() { File(app.filesDir, "batch3-checkpoint-device.json").writeText(report.toString(2)) }
        compose.runOnIdle { app.playbackJournal.restore(Platform.KUGOU) }
        compose.waitUntil(20_000) { app.playback.state.value.let { it.connected && it.queueSize == original.songs.size } }
        val checkpoint = CompletableFuture<Result<Unit>>()
        compose.runOnIdle { app.playback.checkpointBeforeInstall { checkpoint.complete(it) } }
        val saved = checkpoint.get(15, TimeUnit.SECONDS)
        report.put("checkpointSuccess", saved.isSuccess)
        val after = app.playbackJournal.state.value.getValue(Platform.KUGOU).queue!!
        report.put("queueSizeAfter", after.songs.size).put("queueIndexAfter", after.index)
            .put("sameSongs", original.songs == after.songs).put("paused", !app.playback.state.value.playing)
        flush()
        saved.getOrThrow()
        assertEquals(original.songs, after.songs)
        assertEquals(original.index, after.index)
        assertFalse(app.playback.state.value.playing)
    }
}
