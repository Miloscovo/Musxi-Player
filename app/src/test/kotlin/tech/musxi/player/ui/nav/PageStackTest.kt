package tech.musxi.player.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page stack model on its own: no Compose, no Android. */
class PageStackTest {
    private fun library(vararg pages: String, maxDepth: Int = 3): PageStack =
        pages.fold(PageStack.of("root", maxDepth)) { stack, key -> stack.push(key) }

    @Test fun startsAtTheRoot() {
        val stack = PageStack.of("root")
        assertEquals(listOf("root"), stack.keys)
        assertEquals(1, stack.depth)
        assertEquals("root", stack.top)
        assertTrue(stack.isRoot)
        assertNull(stack.below)
    }

    @Test fun pushPlacesThePageOnTopAndLeavesTheOldStackAlone() {
        val root = PageStack.of("root")
        val pushed = root.push("playlist-1")
        assertEquals(listOf("root", "playlist-1"), pushed.keys)
        assertEquals("playlist-1", pushed.top)
        assertEquals("root", pushed.below)
        assertEquals(listOf("root"), root.keys)
    }

    @Test fun pushStopsAtTheDepthLimit() {
        val full = library("a", "b")
        assertEquals(3, full.depth)
        assertFalse(full.canPush("c"))
        assertSame(full, full.push("c"))
    }

    @Test fun theLimitIsConfigurable() {
        assertEquals(2, library("a", "b", "c", maxDepth = 2).depth)
        // The root counts toward the limit: root plus four pages fills a limit of five.
        assertEquals(5, library("a", "b", "c", "d", maxDepth = 5).depth)
        assertEquals(5, library("a", "b", "c", "d", "e", maxDepth = 5).depth)
        val flat = PageStack.of("root", maxDepth = 1)
        assertSame(flat, flat.push("a"))
    }

    @Test fun aKeyCanBeOnTheStackOnlyOnce() {
        val stack = library("a")
        assertFalse(stack.canPush("a"))
        assertSame(stack, stack.push("a"))
        assertFalse(stack.canPush("root"))
    }

    @Test fun popRemovesOnlyTheTopPage() {
        assertEquals(listOf("root", "a"), library("a", "b").pop().keys)
    }

    @Test fun popNeverRemovesTheRoot() {
        val root = PageStack.of("root")
        assertSame(root, root.pop())
        assertSame(root, root.popToRoot())
    }

    @Test fun popToRootDropsEveryPageAboveIt() {
        val back = library("a", "b").popToRoot()
        assertEquals(listOf("root"), back.keys)
        assertEquals(3, back.maxDepth)
    }

    @Test fun withoutMiddleKeepsRootAndTopSoOnlyOnePageHasToLeave() {
        assertEquals(listOf("root", "b"), library("a", "b").withoutMiddle().keys)
        val two = library("a")
        assertSame(two, two.withoutMiddle())
    }

    @Test fun savedFormRestoresToAnEqualStack() {
        val stack = library("a", "b")
        assertEquals(stack, PageStack.restore(stack.toSaved(), "root"))
    }

    @Test fun missingOrForeignSavedDataGivesAFreshStack() {
        assertEquals(PageStack.of("root"), PageStack.restore(null, "root"))
        assertEquals(PageStack.of("root"), PageStack.restore(emptyList(), "root"))
        assertEquals(PageStack.of("root"), PageStack.restore(listOf("other", "a"), "root"))
    }

    @Test fun restoreDropsRepeatedKeysAndCutsAtTheLimit() {
        assertEquals(listOf("root", "a"), PageStack.restore(listOf("root", "a", "a", "root"), "root").keys)
        assertEquals(listOf("root", "a"), PageStack.restore(listOf("root", "a", "b", "c"), "root", maxDepth = 2).keys)
    }

    @Test fun aLimitBelowOneIsRejected() {
        val failure = runCatching { PageStack.of("root", maxDepth = 0) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test fun equalityCoversKeysAndLimit() {
        assertEquals(library("a"), library("a"))
        assertEquals(library("a").hashCode(), library("a").hashCode())
        assertFalse(library("a") == library("b"))
        assertFalse(library("a") == library("a", maxDepth = 4))
    }
}
