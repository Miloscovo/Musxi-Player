package tech.musxi.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tech.musxi.core.playback.PlaybackFailure
import tech.musxi.playback.PlaybackSourceException

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class PlaybackResultBridgeTest {
    @Test fun interruptedLoaderRevokesQueuedWorkAndRejectsLateCallback() {
        val started = CountDownLatch(1)
        val current = AtomicReference<() -> Boolean>()
        val callback = AtomicReference<(Result<String>) -> Unit>()
        val failure = AtomicReference<Throwable>()
        val thread = Thread {
            try {
                awaitPlaybackResult<String>({ PlaybackSourceException(PlaybackFailure.UNKNOWN) }) { active, done ->
                    current.set(active); callback.set(done); started.countDown()
                }
                failure.set(AssertionError("Interrupted request returned a capability"))
            } catch (error: Throwable) { failure.set(error) }
        }
        thread.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertTrue(current.get().invoke())
        thread.interrupt()
        thread.join(5_000)
        assertFalse(thread.isAlive)
        assertFalse(current.get().invoke())
        assertEquals(PlaybackFailure.NETWORK, (failure.get() as PlaybackSourceException).failure)
        callback.get().invoke(Result.success("late"))
        assertFalse(current.get().invoke())
    }
}
