package tech.musxi.player

import android.os.SystemClock
import kotlinx.coroutines.*
import tech.musxi.core.account.*
import tech.musxi.core.library.*
import tech.musxi.core.playback.*

/** Search is cancellable and never returns credentials or audio URLs. */
class CrossPlatformSourceSearch(private val app: MusxiApplication) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val finder = AlternativeSourceFinder({ platform ->
        app.accounts.states.value.getValue(platform).takeIf { it.phase == AccountPhase.CONNECTED }?.generation
    }, ::awaitSong, ::search, SystemClock::elapsedRealtime, preferences = app::sourceSwitchPreferences)
    private val rejectedMappings = mutableSetOf<Pair<MediaKey, MediaKey>>()

    fun remember(original: SongSummary, generation: Long, source: SourceCandidate) = finder.remember(original, generation, source)
    fun forget(original: MediaKey, source: MediaKey) {
        finder.forget(original, source)
        rejectedMappings += original to source
    }
    fun find(
        original: SongSummary,
        result: (Result<AlternativeSources>) -> Unit,
        excluded: Set<String> = emptySet(),
        mode: SourceSelectionMode = SourceSelectionMode.AUTO,
        revision: Long? = null,
        currentRevision: (() -> Long?)? = null,
        onProgress: SourceSearchProgressListener? = null,
    ): Job = scope.launch {
        fun revisionCurrent() = revision == null || currentRevision?.invoke() == revision
        if (!revisionCurrent()) return@launch
        val started = SystemClock.elapsedRealtime()
        val response = runCatching {
            withTimeout(20_000) {
                val metadata = if (original.title.isBlank() || original.title == "待加载歌曲" ||
                    original.artists.isEmpty() || original.durationMs <= 0) awaitSong(original.key) else original
                if (metadata.key != original.key) throw LibraryPageFailure("identity")
                if (mode == SourceSelectionMode.AUTO) restoreSuccessfulMapping(metadata, excluded)
                finder.find(
                    original = metadata,
                    excluded = excluded,
                    mode = mode,
                    revision = revision,
                    currentRevision = currentRevision,
                    onProgress = onProgress,
                )
            }
        }
        ensureActive()
        if (!revisionCurrent()) return@launch
        if (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            android.util.Log.d("MusxiQueue", "source_search_ms=${SystemClock.elapsedRealtime() - started} cache=${finder.lastCacheHit} candidates=${response.getOrNull()?.candidates?.size ?: 0}")
        }
        result(response.fold({ Result.success(it) }, { Result.failure(LibraryPageFailure(
            if (it is TimeoutCancellationException) "timeout" else (it as? LibraryPageFailure)?.category ?: "unavailable")) }))
    }
    /** The journal persists only metadata from streams that actually played, scoped to the original account. */
    private suspend fun restoreSuccessfulMapping(original: SongSummary, excluded: Set<String>) {
        val identity = app.accounts.playbackIdentity(original.key.platform)?.takeIf { it.verified } ?: return
        val journal = suspendCancellableCoroutine<PlaybackJournal?> { continuation ->
            app.accounts.playbackJournal(original.key.platform, identity) {
                if (continuation.isActive) continuation.resumeWith(Result.success(it.getOrNull()))
            }
        } ?: return
        val recent = journal.recent.firstOrNull {
            it.original.key == original.key && it.source.key.platform != original.key.platform &&
                it.source.key.stableId !in excluded && (original.key to it.source.key) !in rejectedMappings &&
                System.currentTimeMillis() - it.playedAtEpochMs in 0..604_800_000 &&
                SourceMatchPolicy.eligible(original, it.source, app.sourceSwitchPreferences().relaxDuration)
        } ?: return
        val sourceAccount = app.accounts.states.value.getValue(recent.source.key.platform)
        if (identity != app.accounts.playbackIdentity(original.key.platform) || sourceAccount.phase != AccountPhase.CONNECTED) return
        finder.remember(original, identity.generation, SourceCandidate(recent.source, sourceAccount.generation))
    }
    private suspend fun awaitSong(key: MediaKey): SongSummary = suspendCancellableCoroutine { continuation ->
        val callback: (Result<SongSummary>) -> Unit = { if (continuation.isActive) continuation.resumeWith(it) }
        val current = { continuation.isActive }
        when (key.platform) {
            Platform.NETEASE -> app.library.song(key, callback, playbackSearch = true, isCurrent = current)
            Platform.QQ -> app.qqLibrary.song(key, callback, playbackSearch = true, isCurrent = current)
            Platform.KUGOU -> app.kugouLibrary.song(key, callback, playbackSearch = true, isCurrent = current)
        }
    }
    private suspend fun search(platform: Platform, keyword: String): List<SongSummary> = suspendCancellableCoroutine { continuation ->
        val callback: (Result<List<SongSummary>>) -> Unit = { if (continuation.isActive) continuation.resumeWith(it) }
        val current = { continuation.isActive }
        when (platform) {
            Platform.NETEASE -> app.library.search(keyword, callback, playbackSearch = true, isCurrent = current)
            Platform.QQ -> app.qqLibrary.search(keyword, callback, playbackSearch = true, isCurrent = current)
            Platform.KUGOU -> app.kugouLibrary.search(keyword, callback, playbackSearch = true, isCurrent = current)
        }
    }
}
