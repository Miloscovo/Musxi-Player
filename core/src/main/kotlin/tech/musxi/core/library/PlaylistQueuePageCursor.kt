package tech.musxi.core.library

/** Revalidates the displayed prefix before appending fresh pages. Serial worker use only.
 * Without a server revision this cannot promise an atomic snapshot across remote edits.
 */
class PlaylistQueuePageCursor(knownPrefix: List<SongSummary>, private val pageSize: Int = 100) {
    private val known = knownPrefix.map { it.key }
    private val platform = known.firstOrNull()?.platform
    private var offset = 0
    private var total: Int? = null
    var finished = false
        private set

    init { require(known.size <= 20_000 && pageSize in 1..100 && known.all { it.platform == platform }) }

    fun request(): QueuePageRequest {
        check(!finished)
        return QueuePageRequest(offset, pageSize, 0)
    }

    /** Validates everything before advancing; a rejected page cannot poison a retry. */
    fun accept(page: Page<SongSummary>): List<SongSummary> {
        if (finished) throw LibraryPageFailure("incomplete")
        val count = page.total ?: throw LibraryPageFailure("incomplete")
        val end = offset + page.items.size
        if (count !in known.size..20_000 || (total != null && count != total) ||
            page.items.size > pageSize || end > count ||
            page.items.any { platform != null && it.key.platform != platform } ||
            (page.nextOffset != null && (page.items.size != pageSize || page.nextOffset != end || end >= count)) ||
            (page.nextOffset == null && end != count)) throw LibraryPageFailure("incomplete")
        val overlap = (known.size - offset).coerceIn(0, page.items.size)
        if ((0 until overlap).any { known[offset + it] != page.items[it].key })
            throw LibraryPageFailure("incomplete")
        val added = page.items.drop(overlap)
        total = count
        offset = end
        finished = page.nextOffset == null
        return added
    }
}
