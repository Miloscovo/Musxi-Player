package tech.musxi.player

import android.os.Looper
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tech.musxi.accounts.AccountRepository
import tech.musxi.accounts.KugouLibraryRepository
import tech.musxi.core.account.*
import tech.musxi.core.library.MediaKey
import tech.musxi.core.playback.PlaybackFailure
import tech.musxi.playback.*

class KugouPlaybackProvider(private val accounts: AccountRepository, private val library: KugouLibraryRepository) : PlaybackSourceProvider {
    override val authorization = accounts.states.map { states -> states.getValue(Platform.KUGOU).let {
        it.generation.takeIf { _ -> it.phase == AccountPhase.CONNECTED }
    } }.distinctUntilChanged()
    override fun currentGeneration(): Long? = accounts.states.value.getValue(Platform.KUGOU).let {
        it.generation.takeIf { _ -> it.phase == AccountPhase.CONNECTED }
    }
    override fun resolve(mediaId: String, generation: Long): StreamLease = resolve(mediaId, generation, null)
    override fun qualityOptions(mediaId: String, generation: Long): tech.musxi.core.playback.QualityOptions {
        if (!mediaId.startsWith("kugou:") || currentGeneration() != generation)
            throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
        val options = awaitPlaybackResult(::mapFailure) { _, done ->
            library.qualities(MediaKey(Platform.KUGOU, mediaId.substringAfter(':')), done)
        }
        if (currentGeneration() != generation) throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
        return options
    }
    override fun resolve(mediaId: String, generation: Long, qualityId: String?): StreamLease {
        check(Looper.myLooper() != Looper.getMainLooper())
        if (!mediaId.startsWith("kugou:") || !PlaybackSourcePolicy.acceptsMediaId(mediaId) || currentGeneration() != generation)
            throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
        val capability = awaitPlaybackResult<KugouLibraryRepository.AudioCapability>(::mapFailure) { current, done ->
            library.resolveAudio(MediaKey(Platform.KUGOU, mediaId.substringAfter(':')), qualityId, done,
                isCurrent = { current() && currentGeneration() == generation })
        }
        if (currentGeneration() != generation) throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
        return StreamLease(null, capability.qualityId, tech.musxi.core.playback.PlaybackQualities.name(capability.qualityId)) {
            if (currentGeneration() != generation) throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
            var url: String? = null
            try { capability.deliverToPlayer { url = it } } catch (error: Exception) { throw mapFailure(error) }
            url ?: throw PlaybackSourceException(PlaybackFailure.PROTOCOL)
        }
    }
    private fun mapFailure(error: Throwable) = PlaybackSourceException(when ((error as? KugouLibraryRepository.LibraryFailure)?.category) {
        "expired", "account_changed" -> PlaybackFailure.AUTH_REQUIRED
        "source_expired" -> PlaybackFailure.SOURCE_EXPIRED
        "network", "http" -> PlaybackFailure.NETWORK
        "timeout" -> PlaybackFailure.TIMEOUT
        "rate", "risk" -> PlaybackFailure.RATE_LIMIT
        "permission", "membership", "copyright", "preview" -> PlaybackFailure.PERMISSION
        "unavailable" -> PlaybackFailure.UNAVAILABLE
        "protocol", "identity", "endpoint", "incomplete", "limit" -> PlaybackFailure.PROTOCOL
        else -> PlaybackFailure.UNKNOWN
    }, tech.musxi.core.playback.FailureReason.fromCategory((error as? tech.musxi.core.library.LibraryPageFailure)?.category))
}
