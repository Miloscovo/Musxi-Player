package tech.musxi.player

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import tech.musxi.accounts.LyricsRepository
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.SongSummary
import tech.musxi.core.lyrics.*

/** Fixed read-only YRC/absence samples; no audio, platform writes, binding or queue changes. */
class NeteaseWordLyricsDeviceTest {
    @Test fun nativeYrcAndAbsentFallbackInitialPreviewRefreshAndCache() {
        val phone = BackendBatch5DeviceTest.PhoneDriver()
        val app = phone.activity.application as MusxiApplication
        fun <T> await(block: ((Result<T>) -> Unit) -> Unit): T {
            val promise = CompletableFuture<Result<T>>()
            phone.runOnIdle { block { promise.complete(it) } }
            return promise.get(70, TimeUnit.SECONDS).getOrThrow()
        }
        val restoring = setOf(AccountPhase.PREPARING, AccountPhase.VERIFYING, AccountPhase.REVALIDATING)
        phone.waitUntil(60_000) {
            app.accounts.states.value.getValue(Platform.NETEASE).phase == AccountPhase.CONNECTED &&
                app.accounts.states.value.values.none { it.phase in restoring } && app.playback.state.value.connected
        }
        val identities = Platform.entries.associateWith { app.accounts.playbackIdentity(it) }
        val queue = app.playback.queueState.value
        val repository = LyricsRepository(app.accounts)
        val samples = JSONArray()
        val report = File(app.filesDir, "netease-word-lyrics-device.json")
        fun progress() = report.writeText(JSONObject().put("status", "running").put("samples", samples).toString(2))
        val scratch = File(app.cacheDir, "netease-word-lyrics-device-${java.util.UUID.randomUUID()}")
        progress()
        try {
            for ((id, expectedTitle, expectedWords) in listOf(Triple("35847388", "Hello", true),
                Triple("233931", "泡沫", true), Triple("536622304", "Lemon", false))) {
                val key = MediaKey(Platform.NETEASE, id)
                val song = await<SongSummary> { app.library.song(key, it) }
                assertEquals(expectedTitle, song.title)
                val preview = await<ParsedLyrics> { repository.preview(key, { true }, it) }
                assertTrue("No original lyric rows", preview.lines.isNotEmpty())
                assertEquals("Unexpected YRC availability", expectedWords, preview.lines.any { it.words.isNotEmpty() })
                assertEquals(1, preview.timingRevision)
                val original = await<ParsedLyrics> { repository.fetchOriginal(key, it) }
                assertEquals(1, original.timingRevision)
                assertEquals(expectedWords, original.lines.any { it.words.isNotEmpty() })
                val initial = await<ParsedLyrics> { repository.fetchTranslation(key, original, false, it) }
                val refreshed = await<ParsedLyrics> { repository.fetchTranslation(key, original, true, it) }
                for (lyrics in listOf(preview, original, initial, refreshed)) {
                    assertTrue(lyrics.lines.all { LyricsWordTimeline.valid(it.text, it.words) })
                    assertEquals(original.lines.map { it.timeMs to it.text }, lyrics.lines.map { it.timeMs to it.text })
                }
                assertEquals(expectedWords, initial.lines.any { it.words.isNotEmpty() })
                assertEquals(expectedWords, refreshed.lines.any { it.words.isNotEmpty() })
                if (expectedTitle == "Hello") assertTrue("Matching ytlrc translation missing", refreshed.lines.any { it.translation != null })
                val entry = JSONObject().put("sample", expectedTitle).put("nativeYrc", expectedWords)
                    .put("rows", refreshed.lines.size).put("wordRows", refreshed.lines.count { it.words.isNotEmpty() })
                    .put("words", refreshed.lines.sumOf { it.words.size })
                    .put("translatedRows", refreshed.lines.count { it.translation != null }).put("timingRevision", refreshed.timingRevision)
                    .put("initialPreviewRefreshPassed", true).put("cacheRoundTrip", false)
                samples.put(entry)
                progress()
                val owner = requireNotNull(identities[Platform.NETEASE]).owner
                FileLyricsCacheStore(scratch).write(owner, key, refreshed)
                val cold = requireNotNull(FileLyricsCacheStore(scratch).read(owner, key)).lyrics
                assertEquals(refreshed, cold)
                if (expectedWords) {
                    val row = cold.lines.first { it.words.isNotEmpty() }
                    assertTrue(LyricsWordTimeline.sample(row.words, row.words.first().startTimeMs).index >= 0)
                }
                phone.runOnIdle { repository.releaseOriginal(original) }
                entry.put("cacheRoundTrip", true)
                progress()
            }
            assertEquals(identities, Platform.entries.associateWith { app.accounts.playbackIdentity(it) })
            assertEquals(queue, app.playback.queueState.value)
            report.writeText(JSONObject().put("status", "passed").put("samples", samples)
                .put("accountsAndQueueUnchanged", true).toString(2))
        } finally {
            scratch.deleteRecursively()
        }
    }
}
