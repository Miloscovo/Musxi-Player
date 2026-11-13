package tech.musxi.core.localaudio

import java.text.Normalizer
import java.util.Locale

/**
 * Pure, cancellable query and ordering over a ready [LocalAudioLibraryState].
 *
 * Never touches files, document URIs or the store: callers only get public
 * [LocalAudioItem] metadata, and nothing here can change which source an audio plays.
 */
object LocalAudioSearch {

    fun query(
        state: LocalAudioLibraryState,
        request: LocalAudioQuery,
        checkCancelled: () -> Unit = {},
    ): LocalAudioSearchPage {
        requireReadable(state, request.folderId, request.expectedRevision, checkCancelled)
        val visible = visibleScope(state, request.folderId, request.unfiledOnly, checkCancelled)
        val progress = progressById(state, checkCancelled)
        val bookmarked = HashSet<String>()
        if (request.bookmarkedOnly) for (bookmark in state.bookmarks) {
            checkCancelled()
            bookmarked.add(bookmark.audioId)
        }
        val tokens = searchTokens(request.text)

        val matched = ArrayList<LocalAudioItem>()
        for (item in state.items) {
            checkCancelled()
            if (!visible.contains(item)) continue
            if (request.mode != null && item.mode != request.mode) continue
            if (request.availability != null && item.availability != request.availability) continue
            if (request.bookmarkedOnly && item.id !in bookmarked) continue
            if (!matchesProgress(progress[item.id], request.progress)) continue
            if (tokens.isNotEmpty()) {
                val values = listOfNotNull(item.displayName, item.metadata.title, item.metadata.fileName,
                    item.metadata.creator).mapNotNull(::normalize)
                // Every token must appear literally in at least one field of this item.
                if (tokens.any { token -> values.none { it.contains(token) } }) continue
            }
            matched += item
        }

        val sorted = sortItems(matched, request.folderId, request.sort, request.descending, visible, progress,
            checkCancelled)
        val total = sorted.size
        val offset = request.offset
        val page: List<LocalAudioItem> = if (offset >= total) {
            emptyList()
        } else {
            sorted.subList(offset, minOf(offset + request.limit, total)).toList()
        }
        return LocalAudioSearchPage(
            revision = state.revision,
            total = total,
            offset = offset,
            items = page,
            nextOffset = (offset + page.size).takeIf { page.isNotEmpty() && it < total },
        )
    }

    fun ordered(
        state: LocalAudioLibraryState,
        folderId: String?,
        sort: LocalAudioSort,
        descending: Boolean,
        checkCancelled: () -> Unit = {},
    ): List<LocalAudioItem> {
        requireReadable(state, folderId, expectedRevision = null, checkCancelled = checkCancelled)
        val visible = visibleScope(state, folderId, unfiledOnly = false, checkCancelled = checkCancelled)
        val items = state.items.filter { item ->
            checkCancelled()
            visible.contains(item)
        }
        return sortItems(items, folderId, sort, descending, visible, progressById(state, checkCancelled), checkCancelled)
    }

    private fun requireReadable(
        state: LocalAudioLibraryState,
        folderId: String?,
        expectedRevision: Long?,
        checkCancelled: () -> Unit,
    ) {
        checkCancelled()
        if (!state.ready) throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
        if (folderId != null && state.folders.none { it.id == folderId }) {
            throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
        }
        if (expectedRevision != null && expectedRevision != state.revision) {
            throw LocalAudioFailure(LocalAudioError.STALE_STATE)
        }
    }

    /**
     * The audio visible in one scope: a folder's members, unfiled audio, or the whole library.
     * [positions] is only filled for a folder and drives its manual order.
     */
    private class Scope(
        private val folderId: String?,
        private val unfiledOnly: Boolean,
        val positions: Map<String, Int>,
        private val filed: Set<String>,
    ) {
        fun contains(item: LocalAudioItem): Boolean = when {
            folderId != null -> positions.containsKey(item.id)
            unfiledOnly -> item.id !in filed
            else -> true
        }
    }

    private fun visibleScope(
        state: LocalAudioLibraryState,
        folderId: String?,
        unfiledOnly: Boolean,
        checkCancelled: () -> Unit,
    ): Scope {
        val positions = HashMap<String, Int>()
        val filed = HashSet<String>()
        for (member in state.members) {
            checkCancelled()
            filed += member.audioId
            if (folderId != null && member.folderId == folderId) {
                positions.putIfAbsent(member.audioId, member.position)
            }
        }
        return Scope(folderId, unfiledOnly, positions, filed)
    }

    private fun progressById(state: LocalAudioLibraryState, checkCancelled: () -> Unit): Map<String, LocalAudioProgress> {
        val progress = HashMap<String, LocalAudioProgress>()
        for (value in state.progress) {
            checkCancelled()
            if (progress[value.audioId]?.updatedAtMs?.let { it > value.updatedAtMs } != true) progress[value.audioId] = value
        }
        return progress
    }

    private fun matchesProgress(value: LocalAudioProgress?, filter: LocalAudioProgressFilter): Boolean =
        when (filter) {
            LocalAudioProgressFilter.ANY -> true
            LocalAudioProgressFilter.UNPLAYED -> value == null || (!value.completed && value.positionMs == 0L)
            LocalAudioProgressFilter.IN_PROGRESS -> value != null && !value.completed && value.positionMs > 0L
            LocalAudioProgressFilter.COMPLETED -> value != null && value.completed
        }

    private fun searchTokens(text: String): List<String> =
        normalize(text)?.split(WHITESPACE)?.filter { it.isNotEmpty() } ?: emptyList()

    /** NFKC, root-locale lowercase and trim; fields are normalized separately so tokens never bridge them. */
    private fun normalize(value: String): String? = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .trim()
        .takeIf { it.isNotEmpty() }

    private class Ranked(val item: LocalAudioItem, val index: Int)

    private fun sortItems(
        items: List<LocalAudioItem>,
        folderId: String?,
        sort: LocalAudioSort,
        descending: Boolean,
        scope: Scope,
        progress: Map<String, LocalAudioProgress>,
        checkCancelled: () -> Unit,
    ): List<LocalAudioItem> {
        checkCancelled()
        val ranked = items.mapIndexed { index, item ->
            checkCancelled()
            Ranked(item, index)
        }
        val comparator = Comparator<Ranked> { a, b ->
            checkCancelled()
            val order = when (sort) {
                // Library order is the global manual order; a folder order is its membership.
                LocalAudioSort.MANUAL -> if (folderId == null) {
                    0
                } else {
                    directed(compareValues(scope.positions[a.item.id], scope.positions[b.item.id]), descending)
                }
                LocalAudioSort.TITLE -> directed(LocalAudioNames.compare(a.item.title, b.item.title), descending)
                LocalAudioSort.FILE_NAME -> directed(
                    LocalAudioNames.compare(a.item.metadata.fileName, b.item.metadata.fileName), descending,
                )
                LocalAudioSort.ADDED_AT -> directed(a.item.addedAtMs.compareTo(b.item.addedAtMs), descending)
                LocalAudioSort.DURATION -> unknownLast(
                    a.item.metadata.durationMs, b.item.metadata.durationMs, descending,
                )
                LocalAudioSort.LAST_PROGRESS -> unknownLast(
                    progress[a.item.id]?.updatedAtMs, progress[b.item.id]?.updatedAtMs, descending,
                )
            }
            // Only the primary key reverses; ties keep the original scope order in both directions.
            if (order != 0) order else {
                val manual = if (folderId != null) compareValues(scope.positions[a.item.id], scope.positions[b.item.id]) else 0
                if (manual != 0) manual else a.index.compareTo(b.index)
            }
        }
        val result = ranked.sortedWith(comparator).map { it.item }
        checkCancelled()
        return result
    }

    private fun directed(order: Int, descending: Boolean): Int = when {
        !descending || order == 0 -> order
        order > 0 -> -1
        else -> 1
    }

    /** Unknown duration/progress stays at the end in either direction. */
    private fun unknownLast(a: Long?, b: Long?, descending: Boolean): Int = when {
        a == null && b == null -> 0
        a == null -> 1
        b == null -> -1
        else -> directed(a.compareTo(b), descending)
    }

    private val WHITESPACE = Regex("\\s+")
}
