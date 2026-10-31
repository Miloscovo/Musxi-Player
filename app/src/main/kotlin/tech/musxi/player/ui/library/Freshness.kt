package tech.musxi.player.ui.library

/**
 * How old the list on screen is, in the words a listener uses.
 *
 * Replaces "本机缓存": that said where the rows came from, not whether they were current, and it
 * could not tell a page saved a minute ago from one saved last month.
 */
internal fun freshnessLabel(fetchedAtEpochMs: Long?, nowEpochMs: Long): String? {
    val fetchedAt = fetchedAtEpochMs ?: return null
    val minutes = ((nowEpochMs - fetchedAt).coerceAtLeast(0)) / 60_000
    return when {
        minutes < 1 -> "刚刚更新"
        minutes < 60 -> "$minutes 分钟前更新"
        minutes < 24 * 60 -> "${minutes / 60} 小时前更新"
        else -> "${minutes / (24 * 60)} 天前更新"
    }
}
