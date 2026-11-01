package tech.musxi.core.library

import tech.musxi.core.account.Platform

enum class CatalogKind { ARTIST, ALBUM }

/** Separate from playlist keys and their cache validation rules. */
data class CatalogKey(val platform: Platform, val kind: CatalogKind, val id: String) {
    init { require(id.isNotBlank() && id.length <= 128 && id.none(Char::isISOControl)) }
}

fun interface CatalogTrackFetch {
    fun tracks(key: CatalogKey, offset: Int, result: (Result<Page<SongSummary>>) -> Unit)
}
