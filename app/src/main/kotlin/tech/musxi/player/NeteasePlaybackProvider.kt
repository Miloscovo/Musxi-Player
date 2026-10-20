package tech.musxi.player

import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tech.musxi.accounts.AccountRepository
import tech.musxi.accounts.NeteaseLibraryRepository
import tech.musxi.core.account.*
import tech.musxi.core.library.MediaKey
import tech.musxi.core.playback.PlaybackFailure
import tech.musxi.playback.*

/** Loader-thread bridge. The main thread remains free for MediaSession and account callbacks. */
class NeteasePlaybackProvider(private val accounts: AccountRepository, private val library: NeteaseLibraryRepository) : PlaybackSourceProvider {
    override val authorization = accounts.states.map { states -> states.getValue(Platform.NETEASE).let {
        it.generation.takeIf { _ -> it.phase == AccountPhase.CONNECTED }
    } }.distinctUntilChanged()
    override fun currentGeneration(): Long? = accounts.states.value.getValue(Platform.NETEASE).let {
        it.generation.takeIf { _ -> it.phase == AccountPhase.CONNECTED }
    }

    override fun resolve(mediaId: String, generation: Long): StreamLease {
        check(Looper.myLooper() != Looper.getMainLooper())
        if (!mediaId.startsWith("netease:") || !PlaybackSourcePolicy.acceptsMediaId(mediaId) || currentGeneration() != generation)
            throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
        val latch = CountDownLatch(1)
        val response = AtomicReference<Result<NeteaseLibraryRepository.AudioCapability>?>()
        library.resolveAudio(MediaKey(Platform.NETEASE, mediaId.substringAfter(':'))) {
            response.set(it); latch.countDown()
        }
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) throw PlaybackSourceException(PlaybackFailure.TIMEOUT)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PlaybackSourceException(PlaybackFailure.NETWORK)
        }
        if (currentGeneration() != generation) throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
        val capability = response.get()?.getOrElse { throw mapFailure(it) }
            ?: throw PlaybackSourceException(PlaybackFailure.PROTOCOL)
        return StreamLease(capability.metadata) {
            if (currentGeneration() != generation) throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
            var url: String? = null
            try { capability.deliverToPlayer { url = it } } catch (error: Exception) { throw mapFailure(error) }
            url ?: throw PlaybackSourceException(PlaybackFailure.PROTOCOL)
        }
    }

    private fun mapFailure(error: Throwable) = PlaybackSourceException(when ((error as? NeteaseLibraryRepository.LibraryFailure)?.category) {
        "expired", "account_changed" -> PlaybackFailure.AUTH_REQUIRED
        "source_expired" -> PlaybackFailure.SOURCE_EXPIRED
        "network", "http" -> PlaybackFailure.NETWORK
        "timeout" -> PlaybackFailure.TIMEOUT
        "rate", "risk" -> PlaybackFailure.RATE_LIMIT
        "permission" -> PlaybackFailure.PERMISSION
        "unavailable" -> PlaybackFailure.UNAVAILABLE
        "protocol", "identity", "endpoint", "incomplete", "limit" -> PlaybackFailure.PROTOCOL
        else -> PlaybackFailure.UNKNOWN
    })
}
