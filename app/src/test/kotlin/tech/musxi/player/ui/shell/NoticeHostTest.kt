package tech.musxi.player.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
