package tech.musxi.player

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import tech.musxi.core.account.*
import tech.musxi.core.library.*
import tech.musxi.core.playback.*
import tech.musxi.playback.*

/** Fixed real-network steps. Requires live_prepare; never persists a stream URL or a song ID. */
internal object PlaybackRemainingValidationRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = false
    fun run(app: MusxiApplication, step: String): Boolean {
        if (step !in setOf("remaining_quality_kugou", "remaining_expiry", "remaining_cancel", "remaining_kugou_diagnostic", "remaining_natural_expiry")) return false
        if (running) return true
        running = true
        scope.launch {
            val report = JSONObject().put("status", "running").put("started", System.currentTimeMillis())
            val file = File(app.filesDir, "$step.json")
            fun flush() = file.writeText(report.toString(2))
            val prefs = app.getSharedPreferences("playback-preferences-v1", Context.MODE_PRIVATE)
            val originals = Platform.entries.associateWith { prefs.getString("quality.${it.name.lowercase()}", null) }
            flush()
            try {
                withTimeout(if (step == "remaining_natural_expiry") 1_800_000 else 300_000) {
                    check(File(app.filesDir, "backend-live-baseline/active-platform").exists())
                    until { app.playback.state.value.connected && app.accounts.states.value.values.none { it.phase == AccountPhase.REVALIDATING } }
                    app.playback.pause()
                    when (step) {
                        "remaining_quality_kugou" -> quality(app, report, ::flush)
                        "remaining_expiry" -> expiry(app, report, ::flush)
                        "remaining_cancel" -> cancel(app, report, ::flush)
                        "remaining_kugou_diagnostic" -> diagnostic(app, report)
                        "remaining_natural_expiry" -> naturalExpiry(app, report, ::flush)
                    }
                }
                report.put("status", "complete")
            } catch (e: Exception) {
                report.put("status", "failed").put("errorType", e.javaClass.simpleName)
                    .put("category", (e as? LibraryPageFailure)?.category)
                    .put("failure", app.playback.state.value.failure?.name)
            } finally {
                app.playback.pause()
                prefs.edit().apply { originals.forEach { (p, value) ->
                    val key = "quality.${p.name.lowercase()}"
                    if (value == null) remove(key) else putString(key, value)
                } }.commit()
                report.put("finished", System.currentTimeMillis()); flush(); running = false
            }
        }
        return true
    }

    private fun provider(app: MusxiApplication, p: Platform): PlaybackSourceProvider = when (p) {
        Platform.KUGOU -> KugouPlaybackProvider(app.accounts, app.kugouLibrary)
        Platform.QQ -> QqPlaybackProvider(app.accounts, app.qqLibrary)
        Platform.NETEASE -> NeteasePlaybackProvider(app.accounts, app.library)
    }
    private suspend fun songs(app: MusxiApplication, p: Platform): List<SongSummary> {
        until { !app.playbackJournal.state.value.getValue(p).loading }
        val saved = app.playbackJournal.state.value.getValue(p).queue?.songs
        if (!saved.isNullOrEmpty()) return saved.take(12)
        val api = when (p) { Platform.KUGOU -> app.kugouLibrary; Platform.QQ -> app.qqLibrary; Platform.NETEASE -> app.library }
        val lists = await<Page<PlaylistSummary>> { api.playlists(0, it) }
        val list = requireNotNull(lists.items.filter { it.trackCount > 0 }.maxByOrNull { it.trackCount })
        return await<Page<SongSummary>> { api.tracks(list.key, 0, it) }.items.take(12)
    }

    private suspend fun quality(app: MusxiApplication, r: JSONObject, flush: () -> Unit) {
        val p = Platform.KUGOU
        val source = provider(app, p)
        val generation = requireNotNull(source.currentGeneration())
        r.put("membership", app.accounts.states.value.getValue(p).membership.level.name)
        val scans = JSONArray(); r.put("samples", scans)
        var chosen: SongSummary? = null
        var choices: QualityOptions? = null
        for ((index, song) in songs(app, p).withIndex()) {
            val sample = JSONObject().put("sampleIndex", index); scans.put(sample); flush()
            val result = runCatching { withContext(Dispatchers.IO) { source.qualityOptions(song.key.stableId, generation) } }
            val options = result.getOrNull()
            sample.put("qualities", JSONArray(options?.options?.map { it.id } ?: emptyList<String>()))
                .put("failure", (result.exceptionOrNull() as? PlaybackSourceException)?.failure?.name)
            flush()
            if ((options?.options?.size ?: 0) >= 2) { chosen = song; choices = options; break }
            if ((result.exceptionOrNull() as? PlaybackSourceException)?.failure in
                setOf(PlaybackFailure.RATE_LIMIT, PlaybackFailure.AUTH_REQUIRED, PlaybackFailure.NETWORK, PlaybackFailure.TIMEOUT)) break
        }
        val song = chosen ?: run { r.put("multiQuality", "NO_MULTIPLE_QUALITIES_IN_BOUNDED_SAMPLE"); return }
        val options = requireNotNull(choices).options
        app.accounts.rememberPreferredQuality(p, options.first().id)
        app.queuePlayback.playSong(song)
        until { !app.queuePlayback.state.value.loading }
        check(app.queuePlayback.state.value.error == null)
        app.playback.resume()
        until { app.playback.state.value.let { it.playing && it.sourceKey == song.key } }
        app.playback.pause(); delay(300)
        val ticket = app.playback.currentQueueTicket()
        val position = app.playback.state.value.positionMs
        val target = options.last().id
        await<Unit> { app.playback.setQuality(target, it) }
        delay(300)
        check(ticket == app.playback.currentQueueTicket() && !app.playback.state.value.playWhenReady &&
            kotlin.math.abs(position - app.playback.state.value.positionMs) < 1500)
        app.playback.resume()
        until { app.playback.state.value.playing }
        val before = app.playback.state.value.positionMs; delay(2000)
        check(app.playback.state.value.playing && app.playback.state.value.positionMs > before + 500)
        app.playback.pause()
        r.put("multiQuality", "REAL_SWITCH_PASSED").put("from", options.first().id).put("to", target)
            .put("ticketPositionPausePreserved", true).put("realPlaybackAdvanced", true)
    }

    private suspend fun expiry(app: MusxiApplication, r: JSONObject, flush: () -> Unit) = coroutineScope {
        // Both providers impose a real 60-second local capability deadline; do not alter time or TTL.
        val cases = JSONArray(); r.put("cases", cases)
        val jobs = listOf(Platform.QQ, Platform.KUGOU).map { p -> async {
            val song = songs(app, p).first()
            val delegate = provider(app, p)
            val resolves = AtomicInteger()
            var first: StreamLease? = null
            val counted = object : PlaybackSourceProvider by delegate {
                override fun resolve(mediaId: String, generation: Long): StreamLease {
                    resolves.incrementAndGet()
                    return delegate.resolve(mediaId, generation).also { if (first == null) first = it }
                }
            }
            val queue = QueueSources(counted)
            val uri = queue.replace(listOf(song.key.stableId), requireNotNull(delegate.currentGeneration())).single()
            val original = withContext(Dispatchers.IO) { queue.resolve(uri) }
            val oldUrl = original.url
            val sample = JSONObject().put("platform", p.name).put("stage", "WAIT_LOCAL_TTL")
            cases.put(sample); flush()
            val began = SystemClock.elapsedRealtime()
            delay(65_000)
            val failure = runCatching { requireNotNull(first).readUrl() }.exceptionOrNull() as? PlaybackSourceException
            check(failure?.failure == PlaybackFailure.SOURCE_EXPIRED)
            val renewed = withContext(Dispatchers.IO) { queue.resolve(uri) }
            check(resolves.get() == 2 && original.isCurrent() && renewed.isCurrent())
            val http = withContext(Dispatchers.IO) { rangeStatus(oldUrl) }
            sample.put("stage", "COMPLETE").put("elapsedWaitMs", SystemClock.elapsedRealtime() - began)
                .put("localCapabilityExpiredNaturally", true).put("sameTicketRenewed", true)
                .put("realResolveCount", resolves.get()).put("oldCdnHttpStatus", http)
                .put("cdnRejectedOldUrl", http in listOf(401, 403, 404, 410))
                .put("naturalCdnExpiryTested", false)
            flush()
            song
        } }
        val selected = jobs.awaitAll()
        // Actual player opens a fresh platform source after the local-expiry checks.
        selected.forEach { song ->
            r.put("stage", "PLAY_FRESH_${song.key.platform.name}"); flush()
            app.accounts.rememberPreferredQuality(song.key.platform, if (song.key.platform == Platform.QQ) "qq:standard" else "kugou:128")
            app.queuePlayback.playSong(song)
            until { !app.queuePlayback.state.value.loading }
            check(app.queuePlayback.state.value.error == null)
            app.playback.resume()
            until { app.playback.state.value.let { it.playing && it.sourceKey == song.key } }
            delay(1200); check(app.playback.state.value.playing); app.playback.pause()
        }
        r.put("freshStreamsPlayedByRealService", true)
        r.put("stage", "NETEASE_TTL"); flush()
        val song = songs(app, Platform.NETEASE).first()
        val cap = await<tech.musxi.accounts.NeteaseLibraryRepository.AudioCapability> {
            app.library.resolveAudio(song.key, "netease:standard", it)
        }
        val field = cap.javaClass.getDeclaredField("expiresAt").apply { isAccessible = true }
        val ttl = field.getLong(cap) - SystemClock.elapsedRealtime()
        r.put("neteaseRemainingCapabilityMs", ttl).put("neteaseNaturalExpiry", "DEFERRED_SERVER_TTL")
    }

    private suspend fun diagnostic(app: MusxiApplication, r: JSONObject) {
        val song = songs(app, Platform.KUGOU).first()
        val result = CompletableDeferred<Result<JSONObject>>()
        val method = app.accounts.javaClass.declaredMethods.single {
            it.name.startsWith("withKugouSession") && it.parameterCount == 4
        }
        method.isAccessible = true
        val work: (tech.musxi.loginpoc.KugouApi, tech.musxi.loginpoc.KugouApi.Session) -> JSONObject = { api, session ->
            tech.musxi.loginpoc.KugouAudioDiagnostic.inspect(api, session, song.key.id)
        }
        val callback: (Result<JSONObject>) -> Unit = { result.complete(it) }
        method.invoke(app.accounts, work, callback, tech.musxi.core.diagnostics.RequestOperation.OTHER, false)
        r.put("diagnostic", result.await().getOrThrow())
    }

    private suspend fun naturalExpiry(app: MusxiApplication, r: JSONObject, flush: () -> Unit) = coroutineScope {
        val rows = JSONArray(); r.put("cases", rows)
        Platform.entries.map { p -> async {
            val song = songs(app, p).first()
            val source = provider(app, p)
            val generation = requireNotNull(source.currentGeneration())
            val standard = when(p) { Platform.NETEASE -> "netease:standard"; Platform.QQ -> "qq:standard"; Platform.KUGOU -> "kugou:128" }
            var captured: StreamLease? = null
            val calls = AtomicInteger()
            val counted = object : PlaybackSourceProvider by source {
                override fun resolve(mediaId: String, generation: Long): StreamLease {
                    calls.incrementAndGet()
                    return source.resolve(mediaId, generation, standard).also { if(captured == null) captured = it }
                }
            }
            val queue = QueueSources(counted)
            val uri = queue.replace(listOf(song.key.stableId), generation).single()
            val resolved = withContext(Dispatchers.IO) { queue.resolve(uri) }
            val oldUrl = resolved.url
            val began = SystemClock.elapsedRealtime()
            // Inspect the actual capability captured by the provider; no TTL or clock edits.
            val lease = requireNotNull(captured)
            val reader = lease.javaClass.getDeclaredField("reader").apply { isAccessible = true }.get(lease)
            val capability = reader.javaClass.declaredFields.mapNotNull { field ->
                field.isAccessible = true
                field.get(reader)?.takeIf { it.javaClass.simpleName == "AudioCapability" }
            }.single()
            val expiresField = capability.javaClass.getDeclaredField("expiresAt").apply { isAccessible = true }
            val deadline = expiresField.getLong(capability)
            val row = JSONObject().put("platform",p.name).put("stage","WAIT_ACTUAL_TTL")
                .put("initialRemainingMs",deadline - began)
            rows.put(row); flush()
            check(deadline > began && deadline - began <= 1_500_000)
            while(SystemClock.elapsedRealtime() < deadline + 1500) {
                row.put("remainingMs",(deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)); flush()
                delay(minOf(15_000, (deadline + 1500 - SystemClock.elapsedRealtime()).coerceAtLeast(1)))
            }
            check((runCatching { lease.readUrl() }.exceptionOrNull() as? PlaybackSourceException)?.failure == PlaybackFailure.SOURCE_EXPIRED)
            val oldStatus = withContext(Dispatchers.IO) { rangeStatus(oldUrl) }
            val refreshed = withContext(Dispatchers.IO) { queue.resolve(uri) }
            check(calls.get() == 2 && resolved.isCurrent() && refreshed.isCurrent())
            val freshStatus = withContext(Dispatchers.IO) { rangeStatus(refreshed.url) }
            check(freshStatus in listOf(200,206))
            row.put("stage","COMPLETE").put("waitedMs",SystemClock.elapsedRealtime()-began)
                .put("localCapabilityNaturallyExpired",true).put("sameTicketRenewedOnce",true)
                .put("oldCdnStatus",oldStatus).put("freshCdnStatus",freshStatus)
                .put("oldCdnRejected",oldStatus in listOf(401,403,404,410))
            flush()
        } }.awaitAll()
    }

    private fun rangeStatus(url: String): Int {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000; connection.readTimeout = 5000
            connection.setRequestProperty("Range", "bytes=0-0")
            val code = connection.responseCode
            if (code in listOf(200, 206)) connection.inputStream.use { it.read() }
            code
        } catch (_: Exception) { -1 } finally { connection.disconnect() }
    }

    private suspend fun cancel(app: MusxiApplication, r: JSONObject, flush: () -> Unit) {
        val cases = JSONArray(); r.put("cases", cases)
        for (p in Platform.entries) {
            val song = songs(app, p).first()
            val active = AtomicBoolean(true); val checkpoints = AtomicInteger()
            val response = CompletableDeferred<Result<Any>>()
            val current = { checkpoints.incrementAndGet(); active.get() }
            fun done(result: Result<Any>) { response.complete(result) }
            when (p) {
                Platform.QQ -> app.qqLibrary.resolveAudio(song.key, null, ::done, current)
                Platform.KUGOU -> app.kugouLibrary.resolveAudio(song.key, null, ::done, current)
                Platform.NETEASE -> app.library.resolveAudio(song.key, null, ::done, current)
            }
            until { checkpoints.get() >= 2 || response.isCompleted }
            delay(100)
            val alreadyCompleted = response.isCompleted
            active.set(false)
            val result = response.await()
            val category = (result.exceptionOrNull() as? LibraryPageFailure)?.category
            val row = JSONObject().put("platform", p.name).put("completedBeforeCancel", alreadyCompleted)
                .put("callbackCategory", category).put("checkpoints", checkpoints.get())
            cases.put(row); flush()
            if (!alreadyCompleted) check(category == "cancelled")
            // Separate actual service STOP with an unmodified provider and real platform request.
            val debug = app as DebugMusxiApplication
            debug.validationSources.arm("reset")
            app.queuePlayback.playSong(song)
            until { debug.validationSources.resolveCount > 0 }
            app.playback.stop()
            delay(5000)
            check(app.playback.queueState.value.songs.isEmpty() && !app.playback.state.value.playWhenReady)
            row.put("realProviderStopNoLatePlayback", true).put("cancelMode", "REAL_PROVIDER_AFTER_RESOLVE_DISPATCH")
            flush()
        }
    }
    private suspend fun until(test: () -> Boolean) = withTimeout(90_000) { while (!test()) delay(50) }
    private suspend fun <T> await(block: ((Result<T>) -> Unit) -> Unit): T =
        suspendCancellableCoroutine { c -> block { if (c.isActive) c.resumeWith(it) } }
}
