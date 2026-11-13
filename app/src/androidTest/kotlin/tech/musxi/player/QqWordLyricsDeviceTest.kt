package tech.musxi.player

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import tech.musxi.accounts.LyricsRepository
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.Platform
import tech.musxi.core.library.SongSummary
import tech.musxi.core.lyrics.*
import tech.musxi.qqloginpoc.QqLibraryApi
import tech.musxi.qqloginpoc.QrcLyricsDecoder

/** Read-only lyric samples; no audio, platform writes, bindings, account or queue mutations. */
class QqWordLyricsDeviceTest {
    @Test fun realQrcWordsPreviewRefreshAndPrivateCacheRoundTrip() {
        val phone = BackendBatch5DeviceTest.PhoneDriver()
        val app = phone.activity.application as MusxiApplication
        fun <T> await(block: ((Result<T>) -> Unit) -> Unit): T {
            val promise = CompletableFuture<Result<T>>()
            phone.runOnIdle { block { promise.complete(it) } }
            return promise.get(70, TimeUnit.SECONDS).getOrThrow()
        }
        val restoring = setOf(AccountPhase.PREPARING, AccountPhase.VERIFYING, AccountPhase.REVALIDATING)
        phone.waitUntil(60_000) {
            app.accounts.states.value.getValue(Platform.QQ).phase == AccountPhase.CONNECTED &&
                app.accounts.states.value.values.none { it.phase in restoring } && app.playback.state.value.connected
        }
        val identities = Platform.entries.associateWith { app.accounts.playbackIdentity(it) }
        val queue = app.playback.queueState.value
        val repository = LyricsRepository(app.accounts)
        val report = JSONArray()
        val events = JSONArray()
        val reportFile = File(app.filesDir, "qq-word-lyrics-device.json")
        fun progress() = reportFile.writeText(JSONObject().put("status", "running").put("samples", report)
            .put("protocol", events).toString(2))
        val cacheDirectory = File(app.cacheDir, "qq-word-lyrics-device-${java.util.UUID.randomUUID()}")
        val restoreTransport = observeLyrics(app, events, ::progress)
        progress()
        try {
            for (query in listOf("Lemon 米津玄師", "Hello Adele")) {
                val songs = await<List<SongSummary>> { app.qqLibrary.search(query, it) }
                val title = query.substringBefore(' ')
                val song = songs.first { it.title.equals(title, ignoreCase = true) }
                val preview = await<ParsedLyrics> { repository.preview(song.key, { true }, it) }
                assertTrue("Preview has no verified QRC words", preview.lines.any { it.words.isNotEmpty() })
                assertTrue(preview.lines.all { LyricsWordTimeline.valid(it.text, it.words) })
                val original = await<ParsedLyrics> { repository.fetchOriginal(song.key, it) }
                val initial = await<ParsedLyrics> { repository.fetchTranslation(song.key, original, false, it) }
                assertTrue("Initial lookup has no verified QRC words", initial.lines.any { it.words.isNotEmpty() })
                val refreshed = await<ParsedLyrics> { repository.fetchTranslation(song.key, original, true, it) }
                assertTrue("Refresh has no verified QRC words", refreshed.lines.any { it.words.isNotEmpty() })
                assertEquals(original.lines.map { it.timeMs to it.text }, refreshed.lines.map { it.timeMs to it.text })
                assertTrue(refreshed.lines.all { LyricsWordTimeline.valid(it.text, it.words) })
                val sampleRow = JSONObject().put("sample", title).put("rows", refreshed.lines.size)
                    .put("wordRows", refreshed.lines.count { it.words.isNotEmpty() })
                    .put("words", refreshed.lines.sumOf { it.words.size })
                    .put("translatedRows", refreshed.lines.count { it.translation != null })
                    .put("initialWordRows", initial.lines.count { it.words.isNotEmpty() })
                    .put("previewWordRows", preview.lines.count { it.words.isNotEmpty() }).put("cacheRoundTrip", false)
                report.put(sampleRow)
                progress()
                val owner = requireNotNull(identities[Platform.QQ]).owner
                FileLyricsCacheStore(cacheDirectory).write(owner, song.key, refreshed)
                val cold = requireNotNull(FileLyricsCacheStore(cacheDirectory).read(owner, song.key)).lyrics
                assertEquals(refreshed, cold)
                val word = cold.lines.first { it.words.isNotEmpty() }.words.first()
                assertTrue(LyricsWordTimeline.sample(cold.lines.first { it.words.isNotEmpty() }.words, word.startTimeMs).index >= 0)
                phone.runOnIdle { repository.releaseOriginal(original) }
                sampleRow.put("cacheRoundTrip", true)
                progress()
            }
            assertEquals(identities, Platform.entries.associateWith { app.accounts.playbackIdentity(it) })
            assertEquals(queue, app.playback.queueState.value)
            reportFile.writeText(JSONObject().put("status", "passed").put("samples", report)
                .put("protocol", events).put("accountsAndQueueUnchanged", true).toString(2))
        } finally {
            restoreTransport()
            cacheDirectory.deleteRecursively()
        }
    }

    /** Only fixed protocol metadata/counts leave memory; never raw response or session fields. */
    private fun observeLyrics(app: MusxiApplication, events: JSONArray, progress: () -> Unit): () -> Unit {
        fun field(instance: Any, name: String): Any = requireNotNull(instance.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(instance))
        @Suppress("UNCHECKED_CAST")
        val drivers = (field(app.accounts, "drivers\$delegate") as Lazy<Map<Platform, Any>>).value
        val api = field(drivers.getValue(Platform.QQ), "api")
        val transport = api.javaClass.getDeclaredField("transport").apply { isAccessible = true }
        val originalTransport = transport.get(api)
        val execute = transport.type.declaredMethods.single().apply { isAccessible = true }
        val parseOriginal = QqLibraryApi::class.java.getDeclaredMethod("parseLyricsDocument", JSONObject::class.java)
            .apply { isAccessible = true }
        var original: String? = null
        val proxy = Proxy.newProxyInstance(transport.type.classLoader, arrayOf(transport.type)) { _, method, arguments ->
            if (method.name != "execute") return@newProxyInstance method.invoke(originalTransport, *(arguments ?: emptyArray()))
            val request = requireNotNull(arguments?.firstOrNull())
            val operation = (field(request, "body") as? String)?.let { JSONObject(it).optJSONObject("req_0") }
            val response = try { execute.invoke(originalTransport, request) } catch (error: InvocationTargetException) {
                throw error.targetException
            }
            if (operation?.optString("method") == "GetPlayLyricInfo") {
                val param = operation.getJSONObject("param")
                val data = JSONObject(String(field(response, "body") as ByteArray, Charsets.UTF_8))
                    .optJSONObject("req_0")?.optJSONObject("data")
                val event = JSONObject().put("requestQrc", param.optInt("qrc"))
                    .put("publicSampleMid", param.optString("songMid"))
                    .put("responseQrc", data?.opt("qrc")).put("responseCrypt", data?.opt("crypt"))
                val lyric = data?.opt("lyric") as? String
                event.put("lyricLength", lyric?.length ?: -1)
                if (param.optInt("qrc") == 0 && data != null) {
                    original = (parseOriginal.invoke(null, data) as QqLibraryApi.LyricsDocument).original
                } else if (lyric != null) {
                    val doc = QrcLyricsDecoder.decode(lyric)
                    event.put("decoderStatus", doc.status.name).put("qrcRows", doc.rows.size)
                        .put("qrcWords", doc.rows.sumOf { it.words.size })
                    original?.let { raw ->
                        val parsed = LrcParser.parse(raw)
                        event.put("lrcRows", parsed.lines.size)
                        val rows = doc.rows.map { row -> KrcTranslationAligner.Row(row.timeMs, row.text,
                            words = row.words.map { LyricWord(it.textStart, it.textEnd, it.startTimeMs, it.endTimeMs) }) }
                        val aligned = KrcTranslationAligner.parse(raw, rows)
                        event.put("alignment", aligned.translation.lookup.reason?.name ?: aligned.translation.lookup.status.name)
                        if (doc.rows.size == parsed.lines.size) {
                            event.put("exactTextMatches", doc.rows.indices.count { doc.rows[it].text == parsed.lines[it].text })
                            event.put("maximumTimeDeltaMs", doc.rows.indices.maxOfOrNull {
                                kotlin.math.abs(doc.rows[it].timeMs - parsed.lines[it].timeMs) })
                        }
                    }
                }
                events.put(event)
                progress()
            }
            response
        }
        transport.set(api, proxy)
        return { transport.set(api, originalTransport) }
    }
}
