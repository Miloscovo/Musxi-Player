package tech.musxi.playback

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*

class QueueCommandMailboxTest {
    private fun replace(index: Int = 0) = QueueCommandMailbox.Action.Replace(PlaybackQueue(
        listOf(SongSummary(MediaKey(Platform.NETEASE, "1"), "one", emptyList(), "", 1000),
            SongSummary(MediaKey(Platform.NETEASE, "2"), "two", emptyList(), "", 1000)), index, 1))

    @Test fun stopRejectsPreviouslyQueuedPlaybackEvenWhenDeliveredAfterClear() {
        val mailbox = QueueCommandMailbox()
        val play = mailbox.submit(replace())
        val stop = mailbox.submit(QueueCommandMailbox.Action.Clear)
        assertSame(QueueCommandMailbox.Action.Clear, mailbox.take(stop))
        assertNull(mailbox.take(play))
    }

    @Test fun delayedClearCannotEraseNewSelection() {
        val mailbox = QueueCommandMailbox()
        val stop = mailbox.submit(QueueCommandMailbox.Action.Clear)
        val selected = replace(1)
        val play = mailbox.submit(selected)
        assertNull(mailbox.take(stop))
        assertSame(selected, mailbox.take(play))
    }

    @Test fun latestSelectionWinsBeforeAnyCommandIsDelivered() {
        val mailbox = QueueCommandMailbox()
        val first = mailbox.submit(replace())
        val stop = mailbox.submit(QueueCommandMailbox.Action.Clear)
        val second = replace(1)
        val last = mailbox.submit(second)
        assertFalse(mailbox.isPending(first))
        assertFalse(mailbox.isPending(stop))
        assertTrue(mailbox.isPending(last))
        assertNull(mailbox.take(first))
        assertNull(mailbox.take(stop))
        assertSame(second, mailbox.take(last))
        assertFalse(mailbox.isPending(last))
    }

    @Test fun systemStopOrServiceDestructionInvalidatesPendingPlayback() {
        val mailbox = QueueCommandMailbox()
        val play = mailbox.submit(replace())
        mailbox.invalidate()
        assertNull(mailbox.take(play))
        val next = replace(1)
        assertSame(next, mailbox.take(mailbox.submit(next)))
    }

    @Test fun duplicateDeliveryNeverReplaysConsumedQueue() {
        val mailbox = QueueCommandMailbox()
        val token = mailbox.submit(replace())
        assertNotNull(mailbox.take(token))
        assertNull(mailbox.take(token))
    }

    @Test fun rapidRowClicksDispatchOnlyTheLatestSlotAndItsRenderedRevision() {
        val mailbox = QueueCommandMailbox()
        val first = mailbox.submit(QueueCommandMailbox.Action.Jump(0, 7))
        val latest = QueueCommandMailbox.Action.Jump(1, 7)
        val last = mailbox.submit(latest)
        assertNull(mailbox.take(first))
        assertSame(latest, mailbox.take(last))
        assertNull(mailbox.take(last))
    }

    @Test fun delayedJumpCannotOvertakeAReplacementOrReorder() {
        val mailbox = QueueCommandMailbox()
        val jump = mailbox.submit(QueueCommandMailbox.Action.Jump(1, 7))
        val replacement = replace()
        val replaced = mailbox.submit(replacement)
        assertNull(mailbox.take(jump))
        assertSame(replacement, mailbox.take(replaced))

        val secondJump = mailbox.submit(QueueCommandMailbox.Action.Jump(1, 8))
        val move = QueueCommandMailbox.Action.Edit(8, tech.musxi.core.playback.QueueEdit.Move(1, 0))
        val moved = mailbox.submit(move)
        assertNull(mailbox.take(secondJump))
        assertSame(move, mailbox.take(moved))
    }

    @Test fun clearAndSystemStopPreventAPendingJumpFromRestartingPlayback() {
        val mailbox = QueueCommandMailbox()
        val jump = mailbox.submit(QueueCommandMailbox.Action.Jump(1, 7))
        val clear = mailbox.submit(QueueCommandMailbox.Action.Clear)
        assertNull(mailbox.take(jump))
        assertSame(QueueCommandMailbox.Action.Clear, mailbox.take(clear))
        val retry = mailbox.submit(QueueCommandMailbox.Action.Jump(0, 9))
        mailbox.invalidate()
        assertFalse(mailbox.isPending(retry))
        assertNull(mailbox.take(retry))
    }
}
