package tech.musxi.player.ui.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

/**
 * [text] with every occurrence of the search words picked out in [color]. Pass [Color.Unspecified] to
 * set them in semi-bold only and keep the text colour of whatever draws the string.
 *
 * The keyword is trimmed and split on whitespace, so "周杰伦 晴天" marks both words wherever each
 * appears. Matching ignores case. Overlapping or touching hits from different words are merged into
 * one span, so the result never carries two spans over the same characters. A blank keyword, or one
 * that matches nothing, returns the text unstyled.
 */
internal fun highlighted(text: String, keyword: String, color: Color): AnnotatedString {
    val words = keyword.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.distinct()
    if (words.isEmpty() || text.isEmpty()) return AnnotatedString(text)

    val hits = mutableListOf<IntRange>()
    for (word in words) {
        var from = 0
        while (from <= text.length - word.length) {
            val at = text.indexOf(word, from, ignoreCase = true)
            if (at < 0) break
            hits += at until at + word.length
            from = at + word.length
        }
    }
    if (hits.isEmpty()) return AnnotatedString(text)

    val merged = mutableListOf<IntRange>()
    for (hit in hits.sortedBy { it.first }) {
        val last = merged.lastOrNull()
        if (last != null && hit.first <= last.last + 1) {
            merged[merged.lastIndex] = last.first..maxOf(last.last, hit.last)
        } else {
            merged += hit
        }
    }

    val style = SpanStyle(color = color, fontWeight = FontWeight.SemiBold)
    return buildAnnotatedString {
        append(text)
        merged.forEach { addStyle(style, it.first, it.last + 1) }
    }
}
