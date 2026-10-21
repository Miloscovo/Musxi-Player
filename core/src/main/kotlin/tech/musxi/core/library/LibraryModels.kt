package tech.musxi.core.library

import tech.musxi.core.account.Platform

data class MediaKey(val platform: Platform, val id: String) {
    init { require(id.isNotBlank() && id.length <= 128 && id.none { it.isISOControl() }) }
    val stableId: String get() = "${platform.name.lowercase()}:$id"
}

data class PlaylistSummary(val key: MediaKey, val title: String, val coverUrl: String?, val trackCount: Int)
data class SongSummary(val key: MediaKey, val title: String, val artists: List<String>,
    val album: String, val durationMs: Long, val coverUrl: String? = null)
data class Page<T>(val items: List<T>, val nextOffset: Int?, val total: Int?)
