package tech.musxi.player.ui.search

import android.content.SharedPreferences
import org.json.JSONArray

/** Recent search terms, newest first. Stored only on this device. */
class RecentSearches(private val prefs: SharedPreferences) {

    fun load(): List<String> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        // A damaged value must never break the search page: treat it as "no history".
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.optString(it).trim() }.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList()).take(LIMIT)
    }

    fun record(term: String): List<String> {
        if (term.isBlank()) return load()
        return save(mergeRecent(load(), term))
    }

    fun remove(term: String): List<String> =
        save(load().filterNot { it.equals(term.trim(), ignoreCase = true) })

    fun clear(): List<String> = save(emptyList())

    private fun save(terms: List<String>): List<String> {
        val array = JSONArray()
        terms.forEach(array::put)
        prefs.edit().putString(KEY, array.toString()).apply()
        return terms
    }

    private companion object {
        const val KEY = "terms"
        const val LIMIT = 10
    }
}

/**
 * Puts [term] first in [existing]. The term is trimmed and cut to [maxLength]; a blank term leaves
 * the list unchanged. An older entry that differs only in letter case is replaced, and the list is
 * cut to [limit] by dropping the oldest.
 */
internal fun mergeRecent(
    existing: List<String>,
    term: String,
    limit: Int = 10,
    maxLength: Int = 64,
): List<String> {
    val clean = clip(term.trim(), maxLength).trim()
    if (clean.isEmpty()) return existing
    return (listOf(clean) + existing.filterNot { it.equals(clean, ignoreCase = true) }).take(limit)
}

/** Cuts to [maxLength] UTF-16 units without leaving half of a surrogate pair at the end. */
private fun clip(text: String, maxLength: Int): String {
    if (text.length <= maxLength) return text
    val end = if (Character.isHighSurrogate(text[maxLength - 1])) maxLength - 1 else maxLength
    return text.substring(0, end)
}
