package tech.musxi.player.lockscreen

import org.junit.Assert.*
import org.junit.Test

class LockScreenLyricsSessionTest {
    private val ready = LockScreenLyricsEnvironment(true, true, true, true, true, true, true)

    @Test fun neverLaunchesFromStateUpdatesOrWhenNotPlaying() {
        val session = LockScreenLyricsSession()
        assertEquals(LockScreenLyricsEffect.NONE, session.update(ready, LockScreenLyricsEvent.STATE_CHANGED))
        assertEquals(LockScreenLyricsEffect.NONE, session.update(ready.copy(playing = false), LockScreenLyricsEvent.SCREEN_ON))
        assertEquals(LockScreenLyricsPhase.WAITING_FOR_PLAYBACK, session.state.phase)
        assertEquals(0L, session.state.launchAttempts)
    }

    @Test fun everyLaunchPreconditionIsRequired() {
        val blocked = listOf(
            ready.copy(enabled = false) to LockScreenLyricsPhase.DISABLED,
            ready.copy(hostAvailable = false) to LockScreenLyricsPhase.HOST_UNAVAILABLE,
            ready.copy(overlayGranted = false) to LockScreenLyricsPhase.NEEDS_PERMISSION,
            ready.copy(interactive = false) to LockScreenLyricsPhase.WAITING_FOR_LOCK,
            ready.copy(locked = false) to LockScreenLyricsPhase.WAITING_FOR_LOCK,
            ready.copy(hasMedia = false) to LockScreenLyricsPhase.WAITING_FOR_PLAYBACK,
        )
        blocked.forEach { (environment, phase) ->
            val session = LockScreenLyricsSession()
            assertEquals(LockScreenLyricsEffect.NONE, session.update(environment, LockScreenLyricsEvent.SCREEN_ON))
            assertEquals(phase, session.state.phase)
        }
    }

    @Test fun requestRequiresConfirmedDisplayAndRejectsStaleAcks() {
        val session = LockScreenLyricsSession()
        assertEquals(LockScreenLyricsEffect.LAUNCH, session.update(ready, LockScreenLyricsEvent.SCREEN_ON))
        val id = session.state.requestId!!
        assertEquals(LockScreenLyricsPhase.REQUESTED, session.state.phase)
        assertFalse(session.displayed(id + 1))
        assertTrue(session.displayed(id))
        assertTrue(session.displayed(id))
        assertEquals(1L, session.state.confirmedDisplays)
        assertFalse(session.timedOut(id))
    }

    @Test fun pauseKeepsVisiblePageButStopClosesIt() {
        val session = LockScreenLyricsSession()
        session.update(ready, LockScreenLyricsEvent.SCREEN_ON)
        val id = session.state.requestId!!
        session.displayed(id)
        assertEquals(LockScreenLyricsEffect.NONE, session.update(ready.copy(playing = false), LockScreenLyricsEvent.STATE_CHANGED))
        assertEquals(LockScreenLyricsPhase.SHOWN, session.state.phase)
        assertEquals(LockScreenLyricsEffect.CLOSE, session.update(ready.copy(hasMedia = false), LockScreenLyricsEvent.STATE_CHANGED))
        assertFalse(session.accepts(id))
    }

    @Test fun dismissalConsumesCycleIncludingSongChangesAndModeToggles() {
        val session = LockScreenLyricsSession()
        session.update(ready, LockScreenLyricsEvent.SCREEN_ON)
        assertTrue(session.dismiss(session.state.requestId!!))
        assertEquals(LockScreenLyricsEffect.NONE, session.update(ready, LockScreenLyricsEvent.SCREEN_ON))
        session.update(ready.copy(enabled = false), LockScreenLyricsEvent.STATE_CHANGED)
        assertEquals(LockScreenLyricsEffect.NONE, session.update(ready, LockScreenLyricsEvent.SCREEN_ON))
        assertEquals(1L, session.state.launchAttempts)
        session.update(ready.copy(interactive = false), LockScreenLyricsEvent.SCREEN_OFF)
        assertEquals(LockScreenLyricsEffect.LAUNCH, session.update(ready, LockScreenLyricsEvent.SCREEN_ON))
        assertEquals(2L, session.state.launchAttempts)
    }

    @Test fun silentBlockAndExceptionHaveNoRetryUntilNextScreenCycle() {
        listOf(true, false).forEach { timeout ->
            val session = LockScreenLyricsSession()
            session.update(ready, LockScreenLyricsEvent.SCREEN_ON)
            val id = session.state.requestId!!
            assertTrue(if (timeout) session.timedOut(id) else session.failed(id))
            assertEquals(if (timeout) LockScreenLyricsPhase.UNCONFIRMED else LockScreenLyricsPhase.FAILED, session.state.phase)
            assertFalse(session.displayed(id))
            assertEquals(LockScreenLyricsEffect.NONE, session.update(ready, LockScreenLyricsEvent.SCREEN_ON))
        }
    }

    @Test fun screenOffCancelsRequestAndOldWindowCannotAttachToNewCycle() {
        val session = LockScreenLyricsSession()
        session.update(ready, LockScreenLyricsEvent.SCREEN_ON)
        val old = session.state.requestId!!
        assertEquals(LockScreenLyricsEffect.CLOSE, session.update(ready.copy(interactive = false), LockScreenLyricsEvent.SCREEN_OFF))
        session.update(ready, LockScreenLyricsEvent.SCREEN_ON)
        assertFalse(session.accepts(old))
        assertNotEquals(old, session.state.requestId)
    }

    @Test fun unlockingAndPermissionLossClosePageAndFenceDelayedAck() {
        listOf(ready.copy(locked = false), ready.copy(overlayGranted = false), ready.copy(enabled = false)).forEach { environment ->
            val session = LockScreenLyricsSession()
            session.update(ready, LockScreenLyricsEvent.SCREEN_ON)
            val id = session.state.requestId!!
            assertEquals(LockScreenLyricsEffect.CLOSE, session.update(environment, LockScreenLyricsEvent.STATE_CHANGED))
            assertFalse(session.displayed(id))
        }
    }

    @Test fun userPresentClosesEvenIfKeyguardSnapshotIsLate() {
        val session = LockScreenLyricsSession()
        session.update(ready, LockScreenLyricsEvent.SCREEN_ON)
        assertEquals(LockScreenLyricsEffect.CLOSE, session.update(ready, LockScreenLyricsEvent.USER_PRESENT))
        assertEquals(LockScreenLyricsEffect.NONE, session.update(ready, LockScreenLyricsEvent.SCREEN_ON))
        assertEquals(LockScreenLyricsPhase.DISMISSED, session.state.phase)
    }

    @Test fun repeatedOrLateScreenOffCannotCancelTheNewCycle() {
        val session = LockScreenLyricsSession()
        session.update(ready.copy(interactive = false), LockScreenLyricsEvent.SCREEN_OFF)
        session.update(ready.copy(interactive = false), LockScreenLyricsEvent.SCREEN_OFF)
        assertEquals(1L, session.state.cycle)
        session.update(ready, LockScreenLyricsEvent.SCREEN_ON)
        val id = session.state.requestId!!
        assertEquals(LockScreenLyricsEffect.NONE, session.update(ready, LockScreenLyricsEvent.SCREEN_OFF))
        assertTrue(session.accepts(id))
    }

    @Test fun pauseBeforeConfirmationInvalidatesThePendingWindow() {
        val session = LockScreenLyricsSession()
        session.update(ready, LockScreenLyricsEvent.SCREEN_ON)
        val id = session.state.requestId!!
        assertEquals(LockScreenLyricsEffect.CLOSE, session.update(ready.copy(playing = false), LockScreenLyricsEvent.STATE_CHANGED))
        assertFalse(session.accepts(id))
        assertFalse(session.displayed(id))
        assertEquals(0L, session.state.confirmedDisplays)
    }
}
