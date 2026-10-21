package tech.musxi.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueEditPlanTest {
    @Test
    fun `removing current first item selects next slot`() {
        val plan = QueueEdits.plan(
            size = 4,
            currentIndex = 0,
            edit = QueueEdit.Remove(0),
        )

        assertEquals(listOf(1, 2, 3), plan.order)
        assertEquals(0, plan.currentIndex)
        assertTrue(plan.currentRemoved)
    }

    @Test
    fun `removing current item selects next slot`() {
        val plan = QueueEdits.plan(
            size = 4,
            currentIndex = 1,
            edit = QueueEdit.Remove(1),
        )

        assertEquals(listOf(0, 2, 3), plan.order)
        assertEquals(1, plan.currentIndex)
        assertTrue(plan.currentRemoved)
    }

    @Test
    fun `removing current last item selects previous slot`() {
        val plan = QueueEdits.plan(
            size = 4,
            currentIndex = 3,
            edit = QueueEdit.Remove(3),
        )

        assertEquals(listOf(0, 1, 2), plan.order)
        assertEquals(2, plan.currentIndex)
        assertTrue(plan.currentRemoved)
    }

    @Test
    fun `removing current only item leaves an empty plan`() {
        val plan = QueueEdits.plan(
            size = 1,
            currentIndex = 0,
            edit = QueueEdit.Remove(0),
        )

        assertEquals(emptyList<Int>(), plan.order)
        assertNull(plan.currentIndex)
        assertTrue(plan.currentRemoved)
    }

    @Test
    fun `removing other item preserves current slot identity`() {
        val plan = QueueEdits.plan(
            size = 5,
            currentIndex = 2,
            edit = QueueEdit.Remove(0),
        )

        assertEquals(listOf(1, 2, 3, 4), plan.order)
        assertEquals(1, plan.currentIndex)
        assertFalse(plan.currentRemoved)
    }

    @Test
    fun `removing item after current preserves current position`() {
        val plan = QueueEdits.plan(
            size = 5,
            currentIndex = 2,
            edit = QueueEdit.Remove(4),
        )

        assertEquals(listOf(0, 1, 2, 3), plan.order)
        assertEquals(2, plan.currentIndex)
        assertFalse(plan.currentRemoved)
    }

    @Test
    fun `moving item right inserts at the destination slot`() {
        val plan = QueueEdits.plan(
            size = 5,
            currentIndex = 2,
            edit = QueueEdit.Move(from = 0, to = 4),
        )

        assertEquals(listOf(1, 2, 3, 4, 0), plan.order)
        assertEquals(1, plan.currentIndex)
        assertFalse(plan.currentRemoved)
    }

    @Test
    fun `moving item left inserts at the destination slot`() {
        val plan = QueueEdits.plan(
            size = 5,
            currentIndex = 2,
            edit = QueueEdit.Move(from = 4, to = 0),
        )

        assertEquals(listOf(4, 0, 1, 2, 3), plan.order)
        assertEquals(3, plan.currentIndex)
        assertFalse(plan.currentRemoved)
    }

    @Test
    fun `moving current item follows its original slot`() {
        val plan = QueueEdits.plan(
            size = 5,
            currentIndex = 2,
            edit = QueueEdit.Move(from = 2, to = 4),
        )

        assertEquals(listOf(0, 1, 3, 4, 2), plan.order)
        assertEquals(4, plan.currentIndex)
        assertFalse(plan.currentRemoved)
    }

    @Test
    fun `same destination move is a no-op`() {
        val plan = QueueEdits.plan(
            size = 5,
            currentIndex = 3,
            edit = QueueEdit.Move(from = 1, to = 1),
        )

        assertEquals(listOf(0, 1, 2, 3, 4), plan.order)
        assertEquals(3, plan.currentIndex)
        assertFalse(plan.currentRemoved)
    }

    @Test
    fun `clear empties the queue and removes current item`() {
        val plan = QueueEdits.plan(
            size = 3,
            currentIndex = 1,
            edit = QueueEdit.Clear,
        )

        assertEquals(emptyList<Int>(), plan.order)
        assertNull(plan.currentIndex)
        assertTrue(plan.currentRemoved)
    }

    @Test
    fun `invalid sizes and current indices use a fixed message`() {
        val edits: List<() -> Unit> = listOf(
            { QueueEdits.plan(0, 0, QueueEdit.Clear) },
            { QueueEdits.plan(20_001, 0, QueueEdit.Clear) },
            { QueueEdits.plan(3, -1, QueueEdit.Clear) },
            { QueueEdits.plan(3, 3, QueueEdit.Clear) },
        )

        edits.forEach { edit ->
            val error = assertThrows(IllegalArgumentException::class.java, edit)
            assertEquals("Invalid queue edit", error.message)
        }
    }

    @Test
    fun `invalid remove and move indices use a fixed message`() {
        val edits: List<() -> Unit> = listOf(
            { QueueEdits.plan(3, 1, QueueEdit.Remove(-1)) },
            { QueueEdits.plan(3, 1, QueueEdit.Remove(3)) },
            { QueueEdits.plan(3, 1, QueueEdit.Move(-1, 0)) },
            { QueueEdits.plan(3, 1, QueueEdit.Move(0, 3)) },
            { QueueEdits.plan(3, 1, QueueEdit.Move(3, 0)) },
        )

        edits.forEach { edit ->
            val error = assertThrows(IllegalArgumentException::class.java, edit)
            assertEquals("Invalid queue edit", error.message)
        }
    }

    @Test
    fun `twenty thousand items are supported`() {
        val plan = QueueEdits.plan(
            size = 20_000,
            currentIndex = 19_999,
            edit = QueueEdit.Remove(19_999),
        )

        assertEquals(19_999, plan.order.size)
        assertEquals(19_998, plan.currentIndex)
        assertTrue(plan.currentRemoved)
    }

    @Test
    fun `original slots remain distinct for a transposition`() {
        val first = QueueEdits.plan(
            size = 2,
            currentIndex = 0,
            edit = QueueEdit.Move(0, 1),
        )
        val second = QueueEdits.plan(
            size = 2,
            currentIndex = 0,
            edit = QueueEdit.Move(1, 0),
        )

        assertEquals(listOf(1, 0), first.order)
        assertEquals(1, first.currentIndex)
        assertEquals(listOf(1, 0), second.order)
        assertEquals(1, second.currentIndex)
    }

    private fun assertThrows(
        exceptionType: Class<out Throwable>,
        block: () -> Unit,
    ): Throwable {
        try {
            block()
        } catch (error: Throwable) {
            if (exceptionType.isInstance(error)) {
                return error
            }
            throw AssertionError("Unexpected exception type", error)
        }
        throw AssertionError("Expected ${exceptionType.name}")
    }
}
