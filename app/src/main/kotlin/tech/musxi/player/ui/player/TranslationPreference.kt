package tech.musxi.player.ui.player

import tech.musxi.core.lyrics.LyricLine
import tech.musxi.core.lyrics.LyricsState

/**
 * Chooses the initial translation visibility from the original lyric text only.
 *
 * The platform does not provide a language code, so this is a deliberately conservative script
 * check, not language identification. Kana or Hangul is clear evidence of a foreign-language
 * line. Other non-empty original lines are weighted by their letter scripts; Han wins ties so an
 * unfamiliar mixed document does not unexpectedly turn translations on. Timed translations and
 * song metadata never participate.
 */
fun defaultTranslationEnabled(state: LyricsState): Boolean =
    defaultTranslationEnabled(state.lines, state.plainText)

/** The same decision for callers that cache on exactly these two original-text inputs. */
fun defaultTranslationEnabled(lines: List<LyricLine>, plainText: String): Boolean {
    val originalLines = lines
        .asSequence()
        .map { it.text }
        .filter { it.isNotBlank() }
        .toList()
        .ifEmpty { plainText.lineSequence().filter { it.isNotBlank() }.toList() }
    if (originalLines.isEmpty()) return true

    val letters = originalLines.map(::scriptCounts)
    val hanWeight = letters.filterNot { it.hasKanaOrHangul }.sumOf { it.han }
    // A line with kana or Hangul is foreign as a whole: its Han characters must not be counted as
    // evidence for a Chinese original. This keeps kana-adjacent kanji from masquerading as Chinese.
    val foreignWeight = letters.sumOf {
        if (it.hasKanaOrHangul) it.han + it.nonHan else it.nonHan
    }
    return hanWeight + foreignWeight == 0 || foreignWeight > hanWeight
}

/** Applies a per-source manual choice before consulting the current original's default. */
fun translationVisible(manualOverride: Boolean?, state: LyricsState): Boolean =
    manualOverride ?: defaultTranslationEnabled(state)

private data class ScriptCounts(
    val han: Int,
    val nonHan: Int,
    val hasKanaOrHangul: Boolean,
)

private fun scriptCounts(text: String): ScriptCounts {
    var han = 0
    var nonHan = 0
    var hasKanaOrHangul = false
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        if (Character.isLetter(codePoint)) {
            when (Character.UnicodeScript.of(codePoint)) {
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HANGUL -> {
                    nonHan++
                    hasKanaOrHangul = true
                }
                else -> nonHan++
            }
        }
        index += Character.charCount(codePoint)
    }
    return ScriptCounts(han, nonHan, hasKanaOrHangul)
}
