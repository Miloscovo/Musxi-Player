package tech.musxi.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.Platform
import tech.musxi.core.library.LibraryPageFailure
import tech.musxi.core.library.SongSummary
import tech.musxi.core.playback.QualityOptions
import tech.musxi.core.playback.PlaybackJournal
import tech.musxi.core.playback.SourceSelectionPhase

/** Real network and decoder acceptance; no credentials, URLs or platform writes. */
@RunWith(AndroidJUnit4::class)
class ManualSourceSwitchDeviceTest {
    @Test fun verifiedAlternativeResumesWithoutReplacingQueueSlot() {
        assumeTrue("Requires explicit real-device playback authorization", InstrumentationRegistry.getArguments()
            .getString("allowManualSourceSwitch") == "true")
        val phone = BackendBatch5DeviceTest.PhoneDriver()
        val app = phone.activity.application as MusxiApplication
        val report = JSONObject().put("started", System.currentTimeMillis()).put("status", "running")
        val samples = JSONArray()
        report.put("samples", samples)
        fun flush() = File(app.filesDir, "manual-source-device.json").writeText(report.toString(2))
        fun <T> await(block: ((Result<T>) -> Unit) -> Unit): Result<T> {
            val done = CompletableFuture<Result<T>>()
            phone.runOnIdle { block { done.complete(it) } }
            return done.get(45, TimeUnit.SECONDS)
        }
        fun queueTicket(): String? {
            var ticket: String? = null
            phone.runOnIdle { ticket = app.playback.currentQueueTicket() }
            return ticket
        }
        phone.waitUntil(60_000) {
            app.accounts.states.value.values.all { it.phase == AccountPhase.CONNECTED } &&
                app.playbackJournal.state.value.getValue(Platform.KUGOU).let { !it.loading && it.queue != null }
        }
        val original = requireNotNull(app.playbackJournal.state.value.getValue(Platform.KUGOU).queue)
        val identities = Platform.entries.associateWith { requireNotNull(app.accounts.playbackIdentity(it)) }
        val journals = identities.mapValues { (platform, identity) ->
            await<PlaybackJournal> { app.accounts.playbackJournal(platform, identity, result = it) }.getOrThrow()
        }
        val settings = app.settings.value
        flush()
        try {
            phone.runOnIdle {
                app.settingsRepository.setAutoSwitchSource(false)
                app.settingsRepository.setRelaxSourceDuration(false)
            }
            var passed = false
            for ((platform, query) in listOf(
                Platform.QQ to "有何不可 许嵩", Platform.NETEASE to "平凡之路 朴树",
                Platform.KUGOU to "孤勇者 陈奕迅", Platform.KUGOU to "AIZO King Gnu",
            )) {
                val sample = JSONObject().put("query", query).put("originalPlatform", platform.name)
                samples.put(sample); flush()
                val songs = await<List<SongSummary>> { done -> when (platform) {
                    Platform.QQ -> app.qqLibrary.search(query, done)
                    Platform.NETEASE -> app.library.search(query, done)
                    Platform.KUGOU -> app.kugouLibrary.search(query, done)
                } }.getOrThrow()
                val song = songs.firstOrNull { it.durationMs > 60_000 && it.artists.isNotEmpty() }
                    ?: continue
                val originalQuality = await<QualityOptions> { done -> when (platform) {
                    Platform.QQ -> app.qqLibrary.qualities(song.key, done)
                    Platform.NETEASE -> app.library.qualities(song.key, done)
                    Platform.KUGOU -> app.kugouLibrary.qualities(song.key, done)
                } }
                sample.put("originalOptions", originalQuality.getOrNull()?.options?.size ?: 0); flush()
                if (originalQuality.getOrNull()?.options?.isNotEmpty() != true) continue
                phone.runOnIdle { app.queuePlayback.playSong(song) }
                phone.waitUntil(30_000) {
                    app.playback.state.value.let { it.playing && it.sourceKey == song.key || it.failure != null }
                }
                if (!app.playback.state.value.playing) {
                    sample.put("originalFailure", app.playback.state.value.failure?.name); flush(); continue
                }
                phone.runOnIdle { app.playback.seekTo(30_000) }
                phone.waitUntil(10_000) { app.playback.state.value.positionMs >= 28_500 }
                val queue = app.playback.queueState.value
                val ticket = queueTicket()
                val beforePosition = app.playback.state.value.positionMs
                await<Unit> { app.playback.findAlternativeSources(it) }.getOrThrow()
                phone.waitUntil(30_000) { app.sourceSelection.state.value.phase != SourceSelectionPhase.SEARCHING }
                var selection = app.sourceSelection.state.value
                sample.put("candidateCount", selection.candidates.size)
                    .put("searchKeptPlaying", app.playback.state.value.playing && app.playback.state.value.sourceKey == song.key)
                    .put("searchKeptPosition", app.playback.state.value.positionMs >= beforePosition - 1500)
                assertTrue(sample.getBoolean("searchKeptPlaying"))
                assertTrue(sample.getBoolean("searchKeptPosition"))
                if (selection.phase != SourceSelectionPhase.CHOOSING) { flush(); continue }
                val inspections = JSONArray(); sample.put("inspections", inspections); flush()
                var chosenIndex: Int? = null
                for ((index, candidate) in selection.candidates.withIndex().take(6)) {
                    val quality = await<QualityOptions> { app.inspectAlternativeSource(selection.revision, index, it) }
                    inspections.put(JSONObject().put("platform", candidate.song.key.platform.name)
                        .put("success", quality.isSuccess).put("options", quality.getOrNull()?.options?.size ?: 0)
                        .put("error", (quality.exceptionOrNull() as? LibraryPageFailure)?.category)); flush()
                    if (quality.getOrNull()?.options?.isNotEmpty() == true) { chosenIndex = index; break }
                }
                val canceled = selection.revision
                phone.runOnIdle { assertTrue(app.sourceSelection.cancel(canceled)) }
                assertTrue(app.playback.state.value.playing)
                phone.runOnIdle { assertFalse(app.sourceSelection.select(canceled, 0)) }
                sample.put("cancelKeptPlaying", true).put("staleRejected", true); flush()
                if (chosenIndex == null) continue
                // Search again after cancellation, then revalidate the exact chosen key.
                val key = selection.candidates[chosenIndex].song.key
                await<Unit> { app.playback.findAlternativeSources(it) }.getOrThrow()
                phone.waitUntil(30_000) { app.sourceSelection.state.value.phase != SourceSelectionPhase.SEARCHING }
                selection = app.sourceSelection.state.value
                val index = selection.candidates.indexOfFirst { it.song.key == key }
                assertTrue(index >= 0)
                assertTrue(await<QualityOptions> { app.inspectAlternativeSource(selection.revision, index, it) }
                    .getOrThrow().options.isNotEmpty())
                val position = app.playback.state.value.positionMs
                val started = android.os.SystemClock.elapsedRealtime()
                phone.runOnIdle { assertTrue(app.sourceSelection.select(selection.revision, index)) }
                phone.waitUntil(30_000) {
                    app.playback.state.value.let { it.playing && it.sourceKey == key } || app.playback.state.value.failure != null
                }
                val firstPlayingPosition = app.playback.state.value.positionMs
                Thread.sleep(1_500)
                val after = app.playback.state.value
                sample.put("switchMs", android.os.SystemClock.elapsedRealtime() - started)
                    .put("sourcePlatform", after.sourcePlatform?.name).put("playing", after.playing)
                    .put("playbackConfirmed", app.sourceSelection.state.value.playbackConfirmed)
                    .put("positionBeforeMs", position).put("positionAfterMs", after.positionMs)
                    .put("failure", after.failure?.name)
                    .put("queueStable", queue.songs.map { it.key } == app.playback.queueState.value.songs.map { it.key })
                    .put("slotStable", queue.currentIndex == app.playback.queueState.value.currentIndex)
                    .put("ticketStable", ticket == queueTicket()); flush()
                assertTrue(after.playing)
                assertEquals(key, after.sourceKey)
                assertNull(after.failure)
                assertTrue(after.positionMs >= firstPlayingPosition + 500)
                assertNotEquals(song.key.platform, after.sourcePlatform)
                assertTrue(app.sourceSelection.state.value.playbackConfirmed)
                assertTrue(after.positionMs in (position - 1500)..(position + 10_000))
                assertTrue(sample.getBoolean("queueStable") && sample.getBoolean("slotStable") && sample.getBoolean("ticketStable"))
                passed = true
                break
            }
            assertTrue("No verified alternative completed real playback; inspect per-sample evidence", passed)
            report.put("status", "passed")
        } catch (failure: Throwable) {
            report.put("status", "failed").put("errorType", failure.javaClass.simpleName)
            throw failure
        } finally {
            phone.runOnIdle {
                app.sourceSelection.cancel(app.sourceSelection.state.value.revision)
                app.playback.pause()
                app.settingsRepository.setAutoSwitchSource(settings.autoSwitchSource)
                app.settingsRepository.setRelaxSourceDuration(settings.relaxSourceDuration)
            }
            try {
                await<Unit> { app.queuePlayback.restore(original, it) }.getOrThrow()
                phone.waitUntil(10_000) {
                    app.playback.queueState.value.songs.map { it.key } == original.songs.map { it.key } &&
                        app.playback.queueState.value.currentIndex == original.index
                }
                phone.runOnIdle { app.playback.pause() }
                val restored = app.playback.queueState.value
                report.put("queueRestored", restored.songs.map { it.key } == original.songs.map { it.key })
                    .put("queueIndexRestored", restored.currentIndex == original.index)
                    .put("settingsRestored", app.settings.value == settings)
                    .put("paused", !app.playback.state.value.playWhenReady)
                assertEquals(original.songs.map { it.key }, restored.songs.map { it.key })
                assertEquals(original.index, restored.currentIndex)
                assertEquals(settings, app.settings.value)
                for ((platform, journal) in journals) {
                    await<PlaybackJournal> { app.accounts.playbackJournal(platform, identities.getValue(platform), { journal }, it) }.getOrThrow()
                }
            } catch (restoreFailure: Throwable) {
                report.put("status", "failed").put("restoreErrorType", restoreFailure.javaClass.simpleName)
                throw restoreFailure
            } finally {
                report.put("finished", System.currentTimeMillis()); flush()
            }
        }
    }
}
