package tech.musxi.accounts

import tech.musxi.core.account.Platform
import tech.musxi.core.library.*
import tech.musxi.core.diagnostics.RequestOperation
import tech.musxi.loginpoc.KugouApi
import tech.musxi.loginpoc.KugouLibraryApi
import tech.musxi.neteaseloginpoc.NeteaseApi
import tech.musxi.neteaseloginpoc.NeteaseLibraryApi
import tech.musxi.qqloginpoc.QqMusicApi
import tech.musxi.qqloginpoc.QqLibraryApi

/** Explicit account-local favorite sync. No cross-platform copying or automatic write retries. */
class FavoritesRepository(private val accounts: AccountRepository,
    private val diagnostic: (Platform, String, String) -> Unit = { _, _, _ -> }) {
    fun fetch(platform: Platform, current: () -> Boolean, result: (Result<FavoritesSnapshot>) -> Unit) {
        val callback: (Result<List<String>>) -> Unit = { response -> result(safe(response, platform, "fetch").map { ids ->
            FavoritesSnapshot(ids.map { MediaKey(platform, it) }.toSet())
        }) }
        when (platform) {
            Platform.NETEASE -> accounts.withNeteaseSession({ api, session ->
                requireCurrent(current)
                NeteaseLibraryApi(api).likedIds(session).also { requireCurrent(current) }
            }, callback, RequestOperation.FAVORITES_READ)
            Platform.KUGOU -> accounts.withKugouSession({ api, session ->
                requireCurrent(current); KugouLibraryApi(api).likedIds(session, current)
            }, callback, RequestOperation.FAVORITES_READ)
            Platform.QQ -> accounts.withQqSession({ api, session ->
                requireCurrent(current); QqLibraryApi(api, current).likedIds(session)
            }, callback, RequestOperation.FAVORITES_READ)
        }
    }

    fun setLiked(song: SongSummary, liked: Boolean, current: () -> Boolean, result: (Result<Unit>) -> Unit) {
        val callback: (Result<Unit>) -> Unit = { result(safe(it, song.key.platform, "write")) }
        when (song.key.platform) {
            Platform.NETEASE -> accounts.withNeteaseSession({ api, session ->
                requireCurrent(current); NeteaseLibraryApi(api).setLiked(session, song.key.id, liked)
            }, callback, RequestOperation.FAVORITES_WRITE)
            Platform.KUGOU -> accounts.withKugouSession({ api, session ->
                requireCurrent(current)
                KugouLibraryApi(api).setLiked(session, song.key.id, song.title, song.durationMs, liked, current)
            }, callback, RequestOperation.FAVORITES_WRITE)
            Platform.QQ -> accounts.withQqSession({ api, session ->
                requireCurrent(current); QqLibraryApi(api, current).setLiked(session, song.key.id, liked)
            }, callback, RequestOperation.FAVORITES_WRITE)
        }
    }

    private fun requireCurrent(current: () -> Boolean) {
        if (!current()) throw LibraryPageFailure("account_changed")
    }
    private fun <T> safe(response: Result<T>, platform: Platform, operation: String): Result<T> = response.fold({
        diagnostic(platform, operation, "success"); Result.success(it)
    }, { error ->
        val category = when (error) {
            is LibraryPageFailure -> error.category
            is NeteaseApi.ApiException -> error.code
            is KugouApi.ApiException -> error.code
            is QqMusicApi.ApiException -> error.code
            else -> "unavailable"
        }
        val safeCategory = category.takeIf { it in setOf("account_changed", "unavailable", "network",
            "timeout", "http", "expired", "identity", "protocol", "limit", "incomplete", "permission", "risk", "rate",
            "signature", "platform", "endpoint") } ?: "unavailable"
        val type = when (error) {
            is NeteaseApi.ApiException, is KugouApi.ApiException, is QqMusicApi.ApiException -> "api"
            is LibraryPageFailure -> "library"
            is java.io.IOException -> "io"
            is IllegalArgumentException -> "argument"
            is org.json.JSONException -> "json"
            is LinkageError -> "linkage"
            else -> "other"
        }
        val httpStatus = (error as? KugouApi.ApiException)?.identityDiagnostic?.takeIf { it.matches(Regex("http_status_[0-9]{3}")) }
        val cgiCode = (error as? QqMusicApi.ApiException)?.diagnostic?.takeIf { it.matches(Regex("cgi_code_-?[0-9]{1,10}")) }
        diagnostic(platform, operation, httpStatus ?: cgiCode ?: "${type}_$safeCategory")
        Result.failure(LibraryPageFailure(safeCategory))
    })
}
