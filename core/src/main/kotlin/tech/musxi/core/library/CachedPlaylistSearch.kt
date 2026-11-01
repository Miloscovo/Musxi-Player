package tech.musxi.core.library

import java.text.Normalizer
import java.util.Locale

data class CachedPlaylistSearchResult(
    val items: List<SongSummary>,
    val loadedCount: Int,
    val total: Int?,
    val complete: Boolean,
    val loadedOffsets: Set<Int>,
    val oldestFetchedAtEpochMs: Long?,
)

object CachedPlaylistSearch {
    fun search(pages: List<CachedTrackPage>, query: String): CachedPlaylistSearchResult {
        val ordered = pages.sortedBy { it.offset }
        val loadedOffsets = ordered.mapTo(linkedSetOf()) { it.offset }
        val allItems = ordered.flatMap { it.saved.page.items }.distinctBy { it.key.stableId }
        val needle = normalize(query)
        val items = if (needle.isEmpty()) allItems else allItems.filter { song ->
            normalize(song.title).contains(needle) ||
                song.artists.any { normalize(it).contains(needle) } ||
                normalize(song.album).contains(needle)
        }
        val expectedTotal = ordered.firstOrNull()?.saved?.page?.total
        val totalStable = expectedTotal != null && ordered.all { it.saved.page.total == expectedTotal }
        return CachedPlaylistSearchResult(
            items = items,
            loadedCount = allItems.size,
            total = expectedTotal.takeIf { totalStable },
            complete = totalStable && followsNextOffsetChain(ordered) && allItems.size == expectedTotal,
            loadedOffsets = loadedOffsets,
            oldestFetchedAtEpochMs = ordered.minOfOrNull { it.saved.fetchedAtEpochMs },
        )
    }

    private fun followsNextOffsetChain(pages: List<CachedTrackPage>): Boolean {
        if (pages.firstOrNull()?.offset != 0) return false
        val seen = mutableSetOf<Int>()
        for ((index, cached) in pages.withIndex()) {
            if (!seen.add(cached.offset)) return false
            val next = cached.saved.page.nextOffset
            if (next == null) return index == pages.lastIndex
            if (next <= cached.offset || next != pages.getOrNull(index + 1)?.offset) return false
        }
        return false
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .filterNot(Char::isWhitespace)
}
