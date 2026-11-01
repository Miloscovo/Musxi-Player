package tech.musxi.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.Platform
import tech.musxi.core.library.LibraryPageFailure
import tech.musxi.core.library.PlaybackQueue
import tech.musxi.core.library.SongSummary
import tech.musxi.core.playback.SavedPlaybackQueue
import tech.musxi.core.settings.AppSettings

/**
 * Live P0 source-switch check. It reads existing account queues, changes only two
 * non-credential preferences, and always restores the original queue and settings.
 */
@RunWith(AndroidJUnit4::class)
class BackendBatch4DeviceTest {
    private lateinit var compose: BackendBatch5DeviceTest.PhoneDriver
    @Before fun openProduct() { compose = BackendBatch5DeviceTest.PhoneDriver() }
    @After fun leavePaused() {
        if (this::compose.isInitialized) compose.runOnIdle {
            (compose.activity.application as MusxiApplication).playback.pause()
        }
    }
    private val originalPlatform: Platform get() = Platform.valueOf(
        androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("originalPlatform", "KUGOU")!!,
    )

    @Test fun restoreLatestQqQueuePaused() = restoreLatestQueuePaused(Platform.QQ)
    @Test fun restoreLatestKugouQueuePaused() = restoreLatestQueuePaused(Platform.KUGOU)

    private fun restoreLatestQueuePaused(platform: Platform) {
        val app = compose.activity.application as MusxiApplication
        compose.waitUntil(60_000) {
            app.playbackJournal.state.value.getValue(platform).let {
                it.accountVerified && !it.loading && it.queue?.songs?.isNotEmpty() == true
            }
        }
        val saved = requireNotNull(app.playbackJournal.state.value.getValue(platform).queue)
        val generation = app.accounts.states.value.getValue(platform).generation
        restoreQueue(app, PlaybackQueue(saved.songs, saved.index, generation), saved)
        compose.waitUntil(15_000) { app.playback.state.value.connected && app.playback.state.value.queueSize == saved.songs.size }
        val state = app.playback.state.value
        val matches = app.playback.queueState.value.songs == saved.songs
        val report = JSONObject().put("queuePlatform", platform.name).put("queueSize", state.queueSize)
            .put("queueIndex", state.queueIndex).put("positionMs", state.positionMs)
            .put("playing", state.playing).put("playWhenReady", state.playWhenReady)
            .put("orderMatches", matches).put("settings", "unchanged")
        File(app.filesDir, "batch4-post-acceptance-state.json").writeText(report.toString(2))
        assertTrue(matches)
        assertEquals(saved.index, state.queueIndex)
        assertEquals(saved.positionMs, state.positionMs)
        assertFalse(state.playing)
        assertFalse(state.playWhenReady)
    }

    @Test fun playableOriginalStaysAndDeniedOriginalSwitchesThenRestoresQueue() {
        val app = compose.activity.application as MusxiApplication
        val report = JSONObject()
        var originalSettings: AppSettings? = null
        var saved: SavedPlaybackQueue? = null
        var queueGeneration = 0L
        var returnQueue: SavedPlaybackQueue? = null
        var returnGeneration = 0L
        var deniedIndex: Int? = null
        var playableIndex: Int? = null
        var scenarioFailure: Throwable? = null
        var failureStage = "setup"

        try {
            failureStage = "wait_accounts_and_queue"
            compose.waitUntil(60_000) {
                val accounts = app.accounts.states.value
                accounts[originalPlatform]?.phase == AccountPhase.CONNECTED &&
                    Platform.entries.any { it != originalPlatform && accounts[it]?.phase == AccountPhase.CONNECTED } &&
                    app.playbackJournal.state.value.getValue(originalPlatform).let {
                        it.accountVerified && !it.loading && it.queue?.songs?.isNotEmpty() == true
                    }
            }
            val original: SavedPlaybackQueue =
                app.playbackJournal.state.value.getValue(originalPlatform).queue
                    ?: error("Original queue missing")
            saved = original
            queueGeneration = app.accounts.states.value.getValue(originalPlatform).generation
            report.put("queueBefore", original.songs.size)
                .put("queueIndexBefore", original.index)
                .put("testedOriginalPlatform", originalPlatform.name)
                .put("membership", JSONObject(app.accounts.states.value.map { (platform, state) ->
                    platform.name to state.membership.level.name
                }.toMap()))

            androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("restorePlatform")?.let { name ->
                val platform = Platform.valueOf(name)
                compose.waitUntil(30_000) {
                    app.playbackJournal.state.value.getValue(platform).let {
                        it.accountVerified && !it.loading && it.queue?.songs?.isNotEmpty() == true
                    }
                }
                returnQueue = app.playbackJournal.state.value.getValue(platform).queue
                returnGeneration = app.accounts.states.value.getValue(platform).generation
                report.put("activeQueuePlatformBefore", platform.name)
                    .put("activeQueueSizeBefore", returnQueue!!.songs.size)
                    .put("activeQueueIndexBefore", returnQueue!!.index)
                    .put("activePositionBeforeMs", returnQueue!!.positionMs)
            }

            originalSettings = app.settings.value
            compose.runOnIdle {
                app.settingsRepository.setAutoSwitchSource(true)
                app.settingsRepository.setRelaxSourceDuration(false)
            }
            assertEquals(true, app.settings.value.autoSwitchSource)
            assertEquals(false, app.settings.value.relaxSourceDuration)

            failureStage = "select_candidates"
            val title = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("title", "晴天")!!
            report.put("sampleTitle", title)
            var testSongs = original.songs.filter { song -> song.artists == listOf("周杰伦") && song.title.trim() == title }.take(3)
            if (testSongs.isEmpty()) {
                val search = CompletableFuture<Result<List<SongSummary>>>()
                compose.runOnIdle {
                    val done: (Result<List<SongSummary>>) -> Unit = { search.complete(it) }
                    when (originalPlatform) {
                        Platform.KUGOU -> app.kugouLibrary.search("周杰伦 $title", done)
                        Platform.QQ -> app.qqLibrary.search("周杰伦 $title", done)
                        Platform.NETEASE -> app.library.search("周杰伦 $title", done)
                    }
                }
                testSongs = search.get(45, TimeUnit.SECONDS).getOrThrow().filter { song ->
                    song.artists == listOf("周杰伦") && song.title.trim() == title
                }.take(3)
            }
            val candidates = testSongs.withIndex()
                .filter { (_, song) ->
                    song.artists.any { it.contains("周杰伦", ignoreCase = true) } ||
                        song.title.trim() in KNOWN_COPYRIGHTED_TITLES
                }
                .distinctBy { it.value.key }
                .take(8)
            report.put("queueCandidateCount", candidates.size)
            assertTrue("No Kugou candidates matching the real queue", candidates.isNotEmpty())

            failureStage = "probe_kugou_permission"
            var tested = 0
            var permissionCategory: String? = null
            val probeFailures = JSONArray()
            val probesStarted = android.os.SystemClock.elapsedRealtime()
            val probeDiagnostics = mutableListOf<String>()
            for (candidate in candidates.take(3)) {
                tested++
                val resolution = CompletableFuture<Result<Any?>>()
                val probeStarted = android.os.SystemClock.elapsedRealtime()
                compose.runOnIdle {
                    when (originalPlatform) {
                        Platform.KUGOU -> app.kugouLibrary.resolveAudio(candidate.value.key) { resolution.complete(it.map { null }) }
                        Platform.QQ -> app.qqLibrary.resolveAudio(candidate.value.key) { resolution.complete(it.map { null }) }
                        Platform.NETEASE -> app.library.resolveAudio(candidate.value.key) { resolution.complete(it.map { null }) }
                    }
                }
                val result = resolution.get(90, TimeUnit.SECONDS)
                val probeMs = android.os.SystemClock.elapsedRealtime() - probeStarted
                val category = (result.exceptionOrNull() as? LibraryPageFailure)?.category
                    ?: result.exceptionOrNull()?.let { "other" }
                probeDiagnostics += JSONObject()
                    .put("probe", tested)
                    .put("success", result.isSuccess)
                    .put("category", category ?: "success")
                    .put("elapsedMs", probeMs)
                    .toString()
                if (result.isSuccess && playableIndex == null) playableIndex = candidate.index
                if (category == "permission") {
                    deniedIndex = candidate.index
                    permissionCategory = category
                    break
                }
            }
            report.put("probeCount", tested)
                .put("probeElapsedMs", android.os.SystemClock.elapsedRealtime() - probesStarted)
                .put("permissionCategory", permissionCategory ?: JSONObject.NULL)
                .put("probes", JSONArray(probeDiagnostics))
            val originalPlayable = permissionCategory == null && playableIndex != null
            assertTrue("Neither a playable track nor a confirmed permission failure was found", originalPlayable || deniedIndex != null)
            val denied = deniedIndex ?: playableIndex ?: error("Test queue index missing")
            report.put("expectedBehavior", if (originalPlayable) "original" else "fallback")
                .put("sampleKey", testSongs[denied].key.stableId)

            failureStage = "restore_original_queue"
            val playbackSongs = listOf(testSongs[denied])
            restoreQueue(app, PlaybackQueue(playbackSongs, 0, queueGeneration), original.copy(songs = playbackSongs,
                index = 0, positionMs = 0, repeatMode = "ALL", shuffleEnabled = false))
            compose.waitUntil(30_000) {
                app.playback.state.value.let {
                    it.connected && it.queueSize == playbackSongs.size
                }
            }
            assertEquals(playbackSongs.size, app.playback.queueState.value.songs.size)

            failureStage = "jump_to_denied_kugou_song"
            val revision = app.playback.queueState.value.revision
            val jump = CompletableFuture<Result<Unit>>()
            val jumpStarted = android.os.SystemClock.elapsedRealtime()
            compose.runOnIdle {
                app.playback.jumpToQueueIndex(0, revision) { jump.complete(it) }
            }
            jump.get(30, TimeUnit.SECONDS).getOrThrow()

            failureStage = "await_real_cross_platform_playback"
            compose.waitUntil(45_000) {
                app.playback.state.value.let {
                    it.playing && if (originalPlayable) it.sourcePlatform == originalPlatform
                    else it.sourcePlatform != null && it.sourcePlatform != originalPlatform && app.sourceSelection.state.value.playbackConfirmed
                }
            }
            val switched = app.playback.state.value
            val selection = app.sourceSelection.state.value
            val sourceMatchesSelection = if (originalPlayable) switched.sourcePlatform == originalPlatform && selection.selected == null
                else switched.sourcePlatform == selection.selectedPlatform
            report.put("switchElapsedMs",
                    android.os.SystemClock.elapsedRealtime() - jumpStarted)
                .put("queuePlatform", switched.queuePlatform?.name ?: JSONObject.NULL)
                .put("sourcePlatform", switched.sourcePlatform?.name ?: JSONObject.NULL)
                .put("selectionPhase", selection.phase.name)
                .put("selectionPlatform", selection.selectedPlatform?.name ?: JSONObject.NULL)
                .put("match", when {
                    selection.selected == null -> "none"
                    selection.selectedRelaxedMatch -> "relaxed"
                    else -> "strict"
                })
                .put("playbackConfirmed", selection.playbackConfirmed)
                .put("sourceMatchesSelection", sourceMatchesSelection)
                .put("playing", switched.playing)
                .put("playbackFailure", switched.failure?.name ?: JSONObject.NULL)

            assertTrue("Source selection did not confirm real playback", originalPlayable || selection.playbackConfirmed)
            assertTrue("Playback did not start", switched.playing)
            assertTrue("Original-source priority was not respected", if (originalPlayable) switched.sourcePlatform == originalPlatform
                else switched.sourcePlatform != originalPlatform)
            assertTrue("Playback source and selected source differ", sourceMatchesSelection)

            failureStage = "prefetch_next_song"
            val insert = CompletableFuture<Result<Unit>>()
            compose.runOnIdle {
                app.playback.enqueue(testSongs[denied], app.playback.queueState.value.revision, playNext = true) { insert.complete(it) }
            }
            insert.get(20, TimeUnit.SECONDS).getOrThrow()
            // Give the real background prefetch its 1.5s delay and network budget.
            Thread.sleep(10_000)
            val nextStarted = android.os.SystemClock.elapsedRealtime()
            val previousIndex = app.playback.queueState.value.currentIndex
            compose.runOnIdle { app.playback.next() }
            compose.waitUntil(30_000) {
                app.playback.queueState.value.currentIndex != previousIndex &&
                    app.playback.state.value.playing && (if (originalPlayable) app.playback.state.value.sourcePlatform == originalPlatform
                    else app.sourceSelection.state.value.playbackConfirmed)
            }
            report.put("nextElapsedMs", android.os.SystemClock.elapsedRealtime() - nextStarted)
                .put("nextSourcePlatform", app.playback.state.value.sourcePlatform?.name)
                .put("nextPlaybackConfirmed", app.sourceSelection.state.value.playbackConfirmed)
        } catch (failure: Throwable) {
            scenarioFailure = failure
            val playback = app.playback.state.value
            val selection = app.sourceSelection.state.value
            report.put("failureStage", failureStage)
                .put("accountPhases", JSONObject(app.accounts.states.value.map { (p, s) -> p.name to s.phase.name }.toMap()))
                .put("journalState", app.playbackJournal.state.value.getValue(originalPlatform).let {
                    JSONObject().put("verified", it.accountVerified).put("loading", it.loading).put("queueCount", it.queue?.songs?.size)
                })
                .put("failureClass", failure.javaClass.simpleName)
                .put("failureQueueCount", playback.queueSize)
                .put("failureQueuePlatform", playback.queuePlatform?.name ?: JSONObject.NULL)
                .put("failureSourcePlatform", playback.sourcePlatform?.name ?: JSONObject.NULL)
                .put("failurePlayback", playback.failure?.name ?: JSONObject.NULL)
                .put("failureLastSkipped", playback.lastSkipped?.name ?: JSONObject.NULL)
                .put("failureSelectionPhase", selection.phase.name)
                .put("failureSelectionPlatform",
                    selection.selectedPlatform?.name ?: JSONObject.NULL)
                .put("failureSelectionMatch", when {
                    selection.selected == null -> "none"
                    selection.selectedRelaxedMatch -> "relaxed"
                    else -> "strict"
                })
                .put("failureSelectionConfirmed", selection.playbackConfirmed)
                .put("failureSelectionPartial", selection.partial)
                .put("failureSelectionCandidateCount", selection.candidates.size)
                .put("failurePlaying", playback.playing)
                .put("candidateAssessments", JSONArray(selection.candidates.map { c -> JSONObject()
                    .put("platform", c.song.key.platform.name).put("title", c.song.title)
                    .put("artists", JSONArray(c.song.artists)).put("durationMs", c.song.durationMs)
                    .put("reason", selection.original?.let { tech.musxi.core.playback.SourceMatchPolicy.assess(it, c.song).name }) }))
                .put("failureReasons", JSONObject(
                    selection.failureReasons.map { (platform, reason) -> platform.name to reason.name }.toMap(),
                ))
        } finally {
            var restoreFailure: Throwable? = null
            try {
                val original = saved
                if (original != null) {
                    restoreQueue(
                        app,
                        PlaybackQueue(original.songs, original.index, queueGeneration),
                        original,
                    )
                    compose.waitUntil(30_000) {
                        app.playback.state.value.let {
                            it.connected && !it.playing && it.queueSize == original.songs.size
                        }
                    }
                    val checkpoint = CompletableFuture<Result<Unit>>()
                    compose.runOnIdle {
                        app.playback.checkpointBeforeInstall { checkpoint.complete(it) }
                    }
                    val checkpointResult = checkpoint.get(15, TimeUnit.SECONDS)
                    report.put("checkpointSuccess", checkpointResult.isSuccess)
                    checkpointResult.getOrThrow()
                    val persisted = app.playbackJournal.state.value.getValue(originalPlatform).queue
                    report.put("queueAfter", persisted?.songs?.size ?: JSONObject.NULL)
                        .put("queueIndexAfter", persisted?.index ?: JSONObject.NULL)
                        .put("queueSongsRestored", persisted?.songs == original.songs)
                        .put("queueIndexRestored", persisted?.index == original.index)
                        .put("positionBeforeMs", original.positionMs)
                        .put("positionAfterMs", persisted?.positionMs)
                        .put("playModesRestored", persisted?.repeatMode == original.repeatMode && persisted?.shuffleEnabled == original.shuffleEnabled)
                        .put("pausedAfterRestore", !app.playback.state.value.playing)
                    assertEquals(original.songs, persisted?.songs)
                    assertEquals(original.index, persisted?.index)
                    assertEquals(original.positionMs, persisted?.positionMs)
                    assertFalse(app.playback.state.value.playing)
                }
            } catch (failure: Throwable) {
                restoreFailure = failure
                report.put("restoreFailureClass", failure.javaClass.simpleName)
            } finally {
                try {
                    returnQueue?.let { original ->
                        val platform = original.songs.first().key.platform
                        restoreQueue(app, PlaybackQueue(original.songs, original.index, returnGeneration), original)
                        val checkpoint = CompletableFuture<Result<Unit>>()
                        compose.runOnIdle { app.playback.checkpointBeforeInstall { checkpoint.complete(it) } }
                        checkpoint.get(15, TimeUnit.SECONDS).getOrThrow()
                        val persisted = app.playbackJournal.state.value.getValue(platform).queue
                        val restored = persisted?.songs == original.songs && persisted.index == original.index &&
                            persisted.positionMs == original.positionMs && persisted.repeatMode == original.repeatMode &&
                            persisted.shuffleEnabled == original.shuffleEnabled && !app.playback.state.value.playing
                        report.put("activeQueueRestored", restored)
                            .put("activeQueueSizeAfter", persisted?.songs?.size)
                            .put("activeQueueIndexAfter", persisted?.index)
                            .put("activePositionAfterMs", persisted?.positionMs)
                        assertTrue("Current user queue must be restored separately from the Kugou test queue", restored)
                    }
                } catch (failure: Throwable) {
                    if (restoreFailure == null) restoreFailure = failure
                    report.put("activeQueueRestoreFailureClass", failure.javaClass.simpleName)
                }
                try {
                    originalSettings?.let { settings ->
                        compose.runOnIdle {
                            app.settingsRepository.setAutoSwitchSource(
                                settings.autoSwitchSource,
                            )
                            app.settingsRepository.setRelaxSourceDuration(
                                settings.relaxSourceDuration,
                            )
                        }
                        report.put("settingsRestored",
                            app.settings.value.autoSwitchSource ==
                                settings.autoSwitchSource &&
                                app.settings.value.relaxSourceDuration ==
                                settings.relaxSourceDuration)
                    }
                } catch (failure: Throwable) {
                    if (restoreFailure == null) restoreFailure = failure
                    report.put("settingsRestoreFailureClass", failure.javaClass.simpleName)
                }
                File(app.filesDir, "batch4-kugou-switch-device.json")
                    .writeText(report.toString(2))
            }
            restoreFailure?.let { throw it }
        }

        scenarioFailure?.let { throw it }
        assertTrue(report.optBoolean("queueSongsRestored"))
        assertTrue(report.optBoolean("queueIndexRestored"))
        assertTrue(report.optBoolean("checkpointSuccess"))
        assertTrue(report.optBoolean("settingsRestored"))
    }

    private fun restoreQueue(
        app: MusxiApplication,
        queue: PlaybackQueue,
        saved: SavedPlaybackQueue,
    ) {
        val restored = CompletableFuture<Result<Unit>>()
        compose.runOnIdle {
            app.playback.restoreQueue(queue, saved) { restored.complete(it) }
        }
        restored.get(30, TimeUnit.SECONDS).getOrThrow()
    }

    private companion object {
        val KNOWN_COPYRIGHTED_TITLES = setOf(
            "晴天", "稻香", "七里香", "夜曲", "青花瓷", "告白气球", "最伟大的作品",
            "说好不哭", "搁浅", "简单爱", "一路向北", "东风破", "发如雪", "菊花台",
        )
    }
}
