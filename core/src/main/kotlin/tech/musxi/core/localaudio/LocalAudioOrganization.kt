package tech.musxi.core.localaudio

enum class LocalAudioSort { MANUAL, TITLE, FILE_NAME, ADDED_AT, DURATION, LAST_PROGRESS }
enum class LocalAudioProgressFilter { ANY, UNPLAYED, IN_PROGRESS, COMPLETED }
enum class LocalAudioBatchAction { ADD, REMOVE, MOVE }

data class LocalAudioQuery(
    val text: String = "",
    val folderId: String? = null,
    val unfiledOnly: Boolean = false,
    val mode: LocalImportMode? = null,
    val availability: LocalAudioAvailability? = null,
    val progress: LocalAudioProgressFilter = LocalAudioProgressFilter.ANY,
    val bookmarkedOnly: Boolean = false,
    val sort: LocalAudioSort = LocalAudioSort.MANUAL,
    val descending: Boolean = false,
    val offset: Int = 0,
    val limit: Int = 100,
    val expectedRevision: Long? = null,
    val expectedToken: LocalAudioQueryToken? = null,
) {
    init {
        require(text.length <= 256 && text.none { it.isISOControl() && !it.isWhitespace() })
        folderId?.let(LocalAudioLimits::requireId)
        require(!unfiledOnly || folderId == null)
        require(offset in 0..LocalAudioLimits.MAX_ITEMS && limit in 1..200)
        require(expectedRevision == null || expectedRevision >= 0)
    }

    fun isCurrent(state: LocalAudioLibraryState, page: LocalAudioSearchPage): Boolean =
        LocalAudioSearch.isCurrent(state, this, page)

    /** True when progress rows can change the matched items or their order. */
    internal fun dependsOnProgress(): Boolean =
        progress != LocalAudioProgressFilter.ANY || sort == LocalAudioSort.LAST_PROGRESS
}

/**
 * A continuation token for one query identity. Offset, limit and the legacy revision check are
 * deliberately absent; the remaining filters and sort determine which query may use the token.
 */
data class LocalAudioQueryToken(
    val query: LocalAudioQuery,
    val organizationRevision: Long,
    val progressRevision: Long?,
    val legacyRevision: Long? = null,
) {
    init {
        require(query.offset == 0 && query.limit == 1)
        require(query.expectedRevision == null && query.expectedToken == null)
        require(organizationRevision >= 0)
        require(progressRevision == null || progressRevision >= 0)
        require(legacyRevision == null || legacyRevision >= 0)
        require((query.dependsOnProgress()) == (progressRevision != null))
    }
}

data class LocalAudioSearchPage(
    val revision: Long,
    val total: Int,
    val offset: Int,
    val items: List<LocalAudioItem>,
    val nextOffset: Int?,
    val token: LocalAudioQueryToken? = null,
) {
    fun isCurrent(state: LocalAudioLibraryState): Boolean = LocalAudioSearch.isCurrent(state, this)
}

data class LocalAudioSearchState(
    val loading: Boolean = false,
    val page: LocalAudioSearchPage? = null,
    val error: LocalAudioError? = null,
)

data class LocalAudioBatchResult(val action: LocalAudioBatchAction, val selected: Int,
    val addedMemberships: Int, val removedMemberships: Int)

data class LocalAudioSortResult(val folderId: String, val members: Int, val changed: Boolean)
