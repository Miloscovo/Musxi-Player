package tech.musxi.player.ui.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure text rules behind the keyword highlight in search results; no Android runtime needed. */
class HighlightTest {

    private val accent = Color(0xFF3366CC)
    private val none = emptyList<Pair<Int, Int>>()

    /** The highlighted ranges as (start, endExclusive) pairs, in order. */
    private fun spans(text: String, keyword: String): List<Pair<Int, Int>> =
        highlighted(text, keyword, accent).spanStyles.map { it.start to it.end }

    @Test fun matchIgnoresCase() {
        assertEquals(listOf(0 to 5), spans("Hello World", "HELLO"))
        assertEquals(listOf(6 to 11), spans("Hello World", "world"))
    }

    @Test fun textIsKeptAsIs() {
        assertEquals("Hello World", highlighted("Hello World", "hello", accent).text)
    }

    @Test fun everyOccurrenceIsMarked() {
        assertEquals(listOf(0 to 2, 3 to 5, 6 to 8), spans("abxabxab", "ab"))
        assertEquals(listOf(0 to 2, 3 to 5, 6 to 8), spans("ab ab ab", "ab"))
    }

    @Test fun eachWordOfAMultiWordKeywordIsMarked() {
        assertEquals(listOf(0 to 3, 4 to 6), spans("周杰伦 晴天", "周杰伦 晴天"))
    }

    @Test fun wordsMayAppearInAnyOrderInTheText() {
        assertEquals(listOf(0 to 2, 3 to 6), spans("晴天 周杰伦", "周杰伦 晴天"))
    }

    @Test fun wordsFarApartAreMarkedSeparately() {
        // 周杰伦 at 0..3, 晴天 at 11..13.
        assertEquals(listOf(0 to 3, 11 to 13), spans("周杰伦 - 一首 - 晴天", "晴天 周杰伦"))
    }

    @Test fun blankKeywordLeavesTextPlain() {
        assertEquals(none, spans("晴天", ""))
        assertEquals(none, spans("晴天", "   "))
        assertEquals("晴天", highlighted("晴天", "  ", accent).text)
    }

    @Test fun noMatchLeavesTextPlain() {
        assertEquals(none, spans("Hello World", "xyz"))
        assertEquals("Hello World", highlighted("Hello World", "xyz", accent).text)
    }

    @Test fun emptyTextHasNothingToMark() {
        assertEquals(none, spans("", "晴天"))
    }

    @Test fun chineseKeywordIsFoundInsideALongerTitle() {
        assertEquals(listOf(2 to 4), spans("一场晴天雨", "晴天"))
    }

    @Test fun surroundingSpacesInTheKeywordAreIgnored() {
        assertEquals(listOf(0 to 2), spans("晴天", "  晴天  "))
    }

    @Test fun overlappingHitsFromDifferentWordsBecomeOneSpan() {
        // "abc" and "bcd" overlap inside "abcd": one span, not two stacked on the same characters.
        assertEquals(listOf(0 to 4), spans("abcd", "abc bcd"))
    }

    @Test fun touchingHitsAreMerged() {
        assertEquals(listOf(0 to 4), spans("abcd", "ab cd"))
    }

    @Test fun duplicateWordsDoNotDoubleUp() {
        assertEquals(listOf(0 to 2), spans("ab", "ab ab"))
    }

    @Test fun spanCarriesColourAndSemiBold() {
        val style = highlighted("晴天", "晴", accent).spanStyles.single().item
        assertEquals(accent, style.color)
        assertEquals(FontWeight.SemiBold, style.fontWeight)
    }
}
