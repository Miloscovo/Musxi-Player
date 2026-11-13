package tech.musxi.accounts

import tech.musxi.core.account.Platform
import tech.musxi.core.diagnostics.RequestOperation
import tech.musxi.core.library.ArtworkPolicy
import tech.musxi.core.library.LibraryPageFailure
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.SongSummary
import tech.musxi.core.recommendation.RecommendationFetch
import tech.musxi.loginpoc.KugouApi
import tech.musxi.loginpoc.KugouLibraryApi
import tech.musxi.neteaseloginpoc.NeteaseApi
import tech.musxi.neteaseloginpoc.NeteaseLibraryApi
import tech.musxi.qqloginpoc.QqLibraryApi
import tech.musxi.qqloginpoc.QqMusicApi

/** Authenticated metadata reads on the existing account request lanes; no feedback writes. */
class RecommendationRepository(private val accounts: AccountRepository) : RecommendationFetch {
    override fun fetch(platform: Platform, isCurrent: () -> Boolean, result: (Result<List<SongSummary>>) -> Unit) {
        fun checkCurrent() { if (!isCurrent()) throw LibraryPageFailure("cancelled") }
        val callback: (Result<List<SongSummary>>) -> Unit = { response ->
            if (!isCurrent()) result(Result.failure(LibraryPageFailure("cancelled")))
            else result(response.fold({ Result.success(it) }, { Result.failure(LibraryPageFailure(category(it))) }))
        }
        when (platform) {
            Platform.NETEASE -> accounts.withNeteaseSession({ api, session ->
                checkCurrent()
                NeteaseLibraryApi(api, isCurrent).dailyRecommendations(session).map { song ->
                    SongSummary(MediaKey(platform, song.id), song.name, song.artists, song.album, song.durationMs,
                        ArtworkPolicy.sanitize(platform, song.coverUrl), song.artistIds, song.albumId)
                }.also { checkCurrent() }
            }, callback, RequestOperation.OTHER)
            Platform.QQ -> accounts.withQqSession({ api, session ->
                checkCurrent()
                QqLibraryApi(api, isCurrent).personalRecommendations(session).map { song ->
                    SongSummary(MediaKey(platform, song.id), song.name, song.artists, song.album, song.durationMs,
                        ArtworkPolicy.sanitize(platform, song.coverUrl), song.artistIds, song.albumId)
                }.also { checkCurrent() }
            }, callback, RequestOperation.OTHER)
            Platform.KUGOU -> accounts.withKugouSession({ api, session ->
                checkCurrent()
                KugouLibraryApi(api).dailyRecommendations(session).map { song ->
                    SongSummary(MediaKey(platform, song.id), song.name, song.artists, song.album, song.durationMs,
                        ArtworkPolicy.sanitize(platform, song.coverUrl), song.artistIds, song.albumId)
                }.also { checkCurrent() }
            }, callback, RequestOperation.OTHER)
        }
    }

    private fun category(error: Throwable): String {
        val code = when (error) {
            is LibraryPageFailure -> error.category
            is NeteaseApi.ApiException -> error.code
            is QqMusicApi.ApiException -> error.code
            is KugouApi.ApiException -> error.code
            is java.net.SocketTimeoutException -> "timeout"
            is java.io.IOException -> "network"
            is IllegalArgumentException -> "protocol"
            else -> "unclassified"
        }
        return code.takeIf { it in setOf("expired", "network", "risk", "rate", "protocol", "identity", "limit", "platform", "request",
            "cancelled", "permission", "unavailable", "endpoint", "http", "timeout", "account_changed", "account_unavailable") }
            ?: "unclassified"
    }
}
