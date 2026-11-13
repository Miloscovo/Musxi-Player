package tech.musxi.player.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.musxi.player.ui.theme.Haptic

class NoticeHostTest {

    @Test
    fun newNoticeReplacesOldOne() {
        val state = NoticeState()
        state.show("first")
        val first = state.current!!
        state.show("second")
        val second = state.current!!

        assertEquals("second", second.text)
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun dismissingOldIdLeavesNewNoticeVisible() {
        val state = NoticeState()
        state.show("first")
        val oldId = state.current!!.id
        state.show("second")

        state.dismiss(oldId)

        assertEquals("second", state.current?.text)
    }

    @Test
    fun dismissingCurrentIdClearsNotice() {
        val state = NoticeState()
        state.show("only")
        state.dismiss(state.current!!.id)

        assertNull(state.current)
    }

    @Test
    fun sameTextShownAgainGetsNewId() {
        val state = NoticeState()
        state.show("已复制")
        val firstId = state.current!!.id
        state.show("已复制")
        val secondId = state.current!!.id

        assertEquals("已复制", state.current?.text)
        assertNotEquals(firstId, secondId)
    }

    @Test
    fun staleDismissAfterRepeatedTextDoesNotClearNewNotice() {
        val state = NoticeState()
        state.show("已复制")
        val staleId = state.current!!.id
        state.show("已复制")

        state.dismiss(staleId)

        assertEquals("已复制", state.current?.text)
    }

    @Test
    fun noticeWithoutHapticPlaysNone() {
        val state = NoticeState()
        state.show("已复制")

        assertNull(state.takeHaptic(state.current!!.id))
    }

    @Test
    fun hapticIsHandedOutOncePerNotice() {
        val state = NoticeState()
        state.show("已加入播放队列", Haptic.CONFIRM)
        val id = state.current!!.id

        assertEquals(Haptic.CONFIRM, state.takeHaptic(id))
        assertNull(state.takeHaptic(id))
    }

    @Test
    fun repeatedTextGetsItsOwnHaptic() {
        val state = NoticeState()
        state.show("当前账号不能修改收藏", Haptic.REJECT)
        state.takeHaptic(state.current!!.id)
        state.show("当前账号不能修改收藏", Haptic.REJECT)

        assertEquals(Haptic.REJECT, state.takeHaptic(state.current!!.id))
    }

    @Test
    fun replacedNoticeNoLongerPlays() {
        val state = NoticeState()
        state.show("first", Haptic.REJECT)
        val oldId = state.current!!.id
        state.show("second", Haptic.CONFIRM)

        assertNull(state.takeHaptic(oldId))
        assertEquals(Haptic.CONFIRM, state.takeHaptic(state.current!!.id))
    }
}
