package tech.musxi.player

import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import tech.musxi.core.playback.PlaybackFailure
import tech.musxi.playback.PlaybackSourceException

/** Repository callbacks run on main; waiting is restricted to the player/IO thread. */
internal fun <T> awaitPlaybackResult(mapFailure: (Throwable) -> PlaybackSourceException,
    request: ((Result<T>) -> Unit) -> Unit): T {
    check(Looper.myLooper() != Looper.getMainLooper())
    val response = AtomicReference<Result<T>?>()
    val latch = CountDownLatch(1)
    request { response.set(it); latch.countDown() }
    try { if (!latch.await(90, TimeUnit.SECONDS)) throw PlaybackSourceException(PlaybackFailure.TIMEOUT) }
    catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw PlaybackSourceException(PlaybackFailure.NETWORK) }
    return response.get()?.getOrElse { throw mapFailure(it) } ?: throw PlaybackSourceException(PlaybackFailure.PROTOCOL)
}
