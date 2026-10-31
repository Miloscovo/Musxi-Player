package tech.musxi.player

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*
import tech.musxi.core.playback.*

/** Bounded parallel discovery. Only confirmed successful metadata mappings are remembered. */
internal class AlternativeSourceFinder(
    private val generation: (Platform) -> Long?,
    private val song: suspend (MediaKey) -> SongSummary,
    private val search: suspend (Platform, String) -> List<SongSummary>,
    private val now: () -> Long,
    private val priorityGraceMs: Long = 250,
    private val platformTimeoutMs: Long = 12_000,
) {
    private data class Remembered(val originalGeneration: Long, val candidate: SourceCandidate, val at: Long)
    private data class Target(val platform: Platform, val accountGeneration: Long?)
    private data class Page(val result: Result<List<SourceCandidate>>)
    private val successful = linkedMapOf<MediaKey, Remembered>()
    var lastCacheHit = false; private set

    fun remember(original: SongSummary, originalGeneration: Long, candidate: SourceCandidate) {
        if (generation(original.key.platform) != originalGeneration ||
            generation(candidate.song.key.platform) != candidate.accountGeneration ||
            original.key.platform == candidate.song.key.platform ||
            SourceMatchPolicy.assess(original, candidate.song) != SourceMatchReason.ELIGIBLE) return
        successful.remove(original.key)
        successful[original.key] = Remembered(originalGeneration,
            candidate.copy(song = candidate.song.copy(artists = candidate.song.artists.toList())), now())
        while (successful.size > 64) successful.remove(successful.keys.first())
    }

    fun forget(original: MediaKey, source: MediaKey) {
        if (successful[original]?.candidate?.song?.key == source) successful.remove(original)
    }

    suspend fun find(
        original: SongSummary,
        excluded: Set<String> = emptySet(),
        mode: SourceSelectionMode = SourceSelectionMode.AUTO,
        revision: Long? = null,
        currentRevision: (() -> Long?)? = null,
        onProgress: SourceSearchProgressListener? = null,
    ): AlternativeSources {
        lastCacheHit = false
        fun revisionCurrent() = revision == null || currentRevision?.invoke() == revision
        fun accountCurrent(owner: Long) = generation(original.key.platform) == owner
        suspend fun ensureCurrent(owner: Long) {
            currentCoroutineContext().ensureActive()
            if (!revisionCurrent()) throw LibraryPageFailure("stale_revision")
            if (!accountCurrent(owner)) throw LibraryPageFailure("account_changed")
        }

        if (!revisionCurrent()) throw LibraryPageFailure("stale_revision")
        val owner = generation(original.key.platform) ?: throw LibraryPageFailure("account_changed")
        val metadata = if (original.title.isBlank() || original.title == "待加载歌曲" ||
            original.artists.isEmpty() || original.durationMs <= 0) song(original.key) else original
        check(metadata.key == original.key)
        ensureCurrent(owner)
        if (mode == SourceSelectionMode.AUTO) {
            successful[original.key]?.let { saved ->
                if (saved.originalGeneration == owner && now() - saved.at in 0..900_000 &&
                    generation(saved.candidate.song.key.platform) == saved.candidate.accountGeneration &&
                    saved.candidate.song.key.stableId !in excluded &&
                    SourceMatchPolicy.assess(metadata, saved.candidate.song) == SourceMatchReason.ELIGIBLE &&
                    revisionCurrent()) {
                    lastCacheHit = true
                    return AlternativeSources(metadata, listOf(saved.candidate))
                }
                successful.remove(original.key)
            }
        }

        val platforms = listOf(Platform.NETEASE, Platform.QQ, Platform.KUGOU)
            .filter { it != original.key.platform }
        val targets = platforms.mapNotNull { platform ->
            generation(platform)?.let { Target(platform, it) }
                ?: if (mode == SourceSelectionMode.MANUAL) Target(platform, null) else null
        }
        if (targets.isEmpty()) return AlternativeSources(metadata, emptyList())
        val keyword = (metadata.title + " " + metadata.artists.firstOrNull().orEmpty()).trim().take(120)
        val progress = linkedMapOf<Platform, SourceSearchProgress>()
        fun notify(
            target: Target,
            status: SourceSearchStatus,
            completed: Int,
            candidateCount: Int = 0,
            error: String? = null,
        ) {
            if (!revisionCurrent()) throw LibraryPageFailure("stale_revision")
            val item = SourceSearchProgress(
                platform = target.platform,
                status = status,
                completedPlatforms = completed,
                totalPlatforms = targets.size,
                candidateCount = candidateCount,
                error = error,
            )
            progress[target.platform] = item
            try {
                onProgress?.onProgress(item)
            } catch (_: Exception) {
                // Progress reporting must not abort the search or expose callback details.
            }
        }
        targets.forEach { notify(it, SourceSearchStatus.SEARCHING, 0) }
        var completed = targets.count {
            it.accountGeneration == null && run {
                notify(it, SourceSearchStatus.FAILURE, 0, error = "account_unavailable")
                true
            }
        }
        if (completed > 0) {
            targets.filter { progress[it.platform]?.status == SourceSearchStatus.FAILURE }
                .forEach { target ->
                    val item = progress.getValue(target.platform)
                    notify(target, SourceSearchStatus.FAILURE, completed,
                        error = item.error ?: "account_unavailable")
                }
        }

        return coroutineScope {
            val responses = Channel<Pair<Int, Page>>(targets.count { it.accountGeneration != null })
            val pages = arrayOfNulls<Page>(targets.size)
            val jobs = targets.mapIndexedNotNull { index, target ->
                val accountGeneration = target.accountGeneration ?: return@mapIndexedNotNull null
                launch {
                    val response = runCatching {
                        withTimeout(platformTimeoutMs) { search(target.platform, keyword) }
                            .filter {
                                it.key.platform == target.platform &&
                                    it.key.stableId !in excluded &&
                                    (mode != SourceSelectionMode.MANUAL || SourceMatchPolicy.assess(metadata, it) == SourceMatchReason.ELIGIBLE)
                            }
                            .take(10)
                            .map { SourceCandidate(it, accountGeneration) }
                            .also { candidates ->
                                if (generation(target.platform) != accountGeneration) {
                                    throw LibraryPageFailure("account_changed")
                                }
                                candidates
                            }
                    }
                    ensureActive()
                    responses.send(index to Page(response))
                }
            }
            var firstEligibleAt: Long? = null
            try {
                while (completed < targets.size) {
                    val eligible = pages.indexOfFirst { page ->
                        page?.result?.getOrNull()?.takeIf { it.isNotEmpty() }?.any {
                            generation(it.song.key.platform) == it.accountGeneration &&
                                SourceMatchPolicy.assess(metadata, it.song) == SourceMatchReason.ELIGIBLE
                        } == true
                    }
                    val wait = if (mode == SourceSelectionMode.AUTO && eligible >= 0) {
                        if ((0 until eligible).all { pages[it] != null }) break
                        val since = firstEligibleAt ?: now().also { firstEligibleAt = it }
                        (priorityGraceMs - (now() - since)).coerceAtLeast(0)
                    } else null
                    if (wait == 0L) break
                    val next = if (wait == null) responses.receive()
                        else withTimeoutOrNull(wait) { responses.receive() } ?: break
                    val target = targets[next.first]
                    val result = next.second.result
                    pages[next.first] = next.second
                    completed++
                    ensureCurrent(owner)
                    if (result.isSuccess) {
                        val currentCandidates = result.getOrThrow().filter {
                            generation(it.song.key.platform) == it.accountGeneration
                        }
                        val staleAccount = result.getOrThrow().size != currentCandidates.size
                        if (staleAccount) {
                            notify(target, SourceSearchStatus.FAILURE, completed,
                                candidateCount = currentCandidates.size, error = "account_changed")
                        } else {
                            notify(target, SourceSearchStatus.COMPLETE, completed,
                                candidateCount = currentCandidates.size)
                        }
                    } else {
                        notify(target, SourceSearchStatus.FAILURE, completed,
                            error = safeFailureCategory(result.exceptionOrNull()))
                    }
                }
                ensureCurrent(owner)
                targets.forEachIndexed { index, target ->
                    val expected = target.accountGeneration ?: return@forEachIndexed
                    val page = pages[index] ?: return@forEachIndexed
                    if (page.result.isSuccess && generation(target.platform) != expected) {
                        pages[index] = Page(Result.failure(LibraryPageFailure("account_changed")))
                        notify(target, SourceSearchStatus.FAILURE, targets.size,
                            error = "account_changed")
                    }
                }
                val candidates = pages.flatMap { it?.result?.getOrDefault(emptyList()).orEmpty() }
                    .filter { generation(it.song.key.platform) == it.accountGeneration }
                    .distinctBy { it.song.key }
                val partial = mode == SourceSelectionMode.AUTO && completed < targets.size ||
                    pages.any { it?.result?.isFailure == true } ||
                    targets.any { it.accountGeneration == null }
                AlternativeSources(
                    original = metadata,
                    candidates = candidates,
                    partial = partial,
                    platformProgress = targets.mapNotNull { progress[it.platform] },
                )
            } finally {
                jobs.forEach { it.cancel() }
                responses.cancel()
            }
        }
    }

    private fun safeFailureCategory(error: Throwable?): String = when (error) {
        is TimeoutCancellationException -> "timeout"
        is LibraryPageFailure -> error.category.takeIf {
            it in setOf(
                "account_changed",
                "account_unavailable",
                "account_unverified",
                "network",
                "protocol",
                "request",
                "timeout",
                "unavailable",
                "permission", "copyright", "membership", "http", "risk", "rate", "expired",
            )
        } ?: "unavailable"
        else -> "unavailable"
    }
}
