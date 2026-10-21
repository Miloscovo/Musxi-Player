package tech.musxi.player

import kotlinx.coroutines.*
import tech.musxi.core.account.*
import tech.musxi.core.library.*
import tech.musxi.core.playback.*

/** Platform order is stable, not determined by network completion time. No audio URLs here. */
class CrossPlatformSourceSearch(private val app: MusxiApplication) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    fun find(original: SongSummary, result: (Result<AlternativeSources>) -> Unit): Job = scope.launch {
            val response = runCatching {
                withTimeout(20_000) {
                    val metadata = if (original.title == "待加载歌曲" || original.title.isBlank())
                        awaitSong(original.key) else original
                    val targets = listOf(Platform.NETEASE, Platform.QQ, Platform.KUGOU)
                        .filter { it != original.key.platform }.mapNotNull { platform ->
                            app.accounts.states.value.getValue(platform).let {
                                if (it.phase == AccountPhase.CONNECTED) platform to it.generation else null
                            }
                        }
                    val keyword = (metadata.title + " " + metadata.artists.firstOrNull().orEmpty()).trim().take(120)
                    val pages = targets.map { (platform, generation) -> async {
                        runCatching {
                            withTimeout(12_000) { search(platform, keyword) }.filter { it.key.platform == platform }
                                .take(10).map { SourceCandidate(it, generation) }
                        }.also { page ->
                            if (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                                val error = page.exceptionOrNull()
                                val category = if (error is TimeoutCancellationException) "timeout" else
                                    (error as? LibraryPageFailure)?.category?.takeIf {
                                        it in setOf("network", "protocol", "expired", "permission", "unavailable", "risk", "rate", "http")
                                    } ?: if (error == null) "none" else "other"
                                android.util.Log.d("MusxiQueue", "search=$platform count=${page.getOrNull()?.size ?: 0} failure=$category")
                            }
                        }
                    } }.awaitAll()
                    val candidates = pages.flatMap { it.getOrDefault(emptyList()) }.filter {
                        val current = app.accounts.states.value.getValue(it.song.key.platform)
                        current.phase == AccountPhase.CONNECTED && current.generation == it.accountGeneration
                    }
                    AlternativeSources(metadata, candidates, pages.any { it.isFailure })
                }
            }
            ensureActive()
            result(response.fold({ Result.success(it) }, { Result.failure(LibraryPageFailure(
                if (it is TimeoutCancellationException) "timeout" else "unavailable")) }))
    }
    private suspend fun awaitSong(key: MediaKey): SongSummary = suspendCancellableCoroutine { continuation ->
        val callback: (Result<SongSummary>) -> Unit = { if (continuation.isActive) continuation.resumeWith(it) }
        when (key.platform) {
            Platform.NETEASE -> app.library.song(key, callback)
            Platform.QQ -> app.qqLibrary.song(key, callback)
            Platform.KUGOU -> app.kugouLibrary.song(key, callback)
        }
    }
    private suspend fun search(platform: Platform, keyword: String): List<SongSummary> = suspendCancellableCoroutine { continuation ->
        val callback: (Result<List<SongSummary>>) -> Unit = { if (continuation.isActive) continuation.resumeWith(it) }
        when (platform) {
            Platform.NETEASE -> app.library.search(keyword, callback)
            Platform.QQ -> app.qqLibrary.search(keyword, callback)
            Platform.KUGOU -> app.kugouLibrary.search(keyword, callback)
        }
    }
}
