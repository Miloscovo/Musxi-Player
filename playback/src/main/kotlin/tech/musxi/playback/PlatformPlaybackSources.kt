package tech.musxi.playback

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import tech.musxi.core.playback.PlaybackFailure
import tech.musxi.core.playback.QualityOptions

/** Emits account changes as wakeups; callers recheck only the active queue's platform. */
class PlatformPlaybackSources(private val providers: Map<String, PlaybackSourceProvider>) : PlaybackSourceProvider {
    override val authorization = merge(*providers.values.map { it.authorization.map<Long?, Long?> { 0L } }.toTypedArray())
    // Compatibility for existing NetEase-only debug commands. Playback uses generationFor.
    override fun currentGeneration() = providers["netease"]?.currentGeneration()
    override fun generationFor(mediaId: String) = providers[mediaId.substringBefore(':')]?.generationFor(mediaId)
    override fun resolve(mediaId: String, generation: Long): StreamLease {
        val provider = providers[mediaId.substringBefore(':')]
            ?: throw PlaybackSourceException(PlaybackFailure.PROTOCOL)
        if (provider.generationFor(mediaId) != generation) throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
        return provider.resolve(mediaId, generation)
    }

    override fun resolve(mediaId: String, generation: Long, qualityId: String?): StreamLease {
        val provider = providers[mediaId.substringBefore(':')]
            ?: throw PlaybackSourceException(PlaybackFailure.PROTOCOL)
        if (provider.generationFor(mediaId) != generation) throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
        return provider.resolve(mediaId, generation, qualityId)
    }

    override fun qualityOptions(mediaId: String, generation: Long): QualityOptions {
        val provider = providers[mediaId.substringBefore(':')]
            ?: throw PlaybackSourceException(PlaybackFailure.PROTOCOL)
        if (provider.generationFor(mediaId) != generation) throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
        return provider.qualityOptions(mediaId, generation)
    }
}
