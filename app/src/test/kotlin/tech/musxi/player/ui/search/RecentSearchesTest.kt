package tech.musxi.player.ui.search

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure list rules behind the "recent searches" row; no Android runtime needed. */
class RecentSearchesTest {

    @Test fun newTermGoesFirst() {
        assertEquals(listOf("c", "a", "b"), mergeRecent(listOf("a", "b"), "c"))
    }

    @Test fun duplicateIgnoringCaseMovesToFrontOnce() {
        val merged = mergeRecent(listOf("Alpha", "beta", "gamma"), "BETA")
        assertEquals(listOf("BETA", "Alpha", "gamma"), merged)
    }

    @Test fun oldestIsDroppedBeyondTen() {
        val existing = (1..10).map { "term$it" }
        val merged = mergeRecent(existing, "fresh")
        assertEquals(10, merged.size)
        assertEquals("fresh", merged.first())
        assertEquals("term9", merged.last())
    }

    @Test fun blankTermIsIgnored() {
        val existing = listOf("a", "b")
        assertEquals(existing, mergeRecent(existing, ""))
        assertEquals(existing, mergeRecent(existing, "   \t "))
    }

    @Test fun surroundingWhitespaceIsTrimmed() {
        assertEquals(listOf("夜に駆ける", "a"), mergeRecent(listOf("a"), "  夜に駆ける  "))
    }

    @Test fun overlongTermIsCutTo64() {
        val merged = mergeRecent(emptyList(), "x".repeat(100))
        assertEquals(1, merged.size)
        assertEquals(64, merged.first().length)
    }
}
