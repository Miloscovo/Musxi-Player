package tech.musxi.accounts

import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.Platform
import tech.musxi.core.diagnostics.RequestOperation
import tech.musxi.core.library.*
import tech.musxi.loginpoc.KugouApi
import tech.musxi.loginpoc.KugouLibraryApi
import tech.musxi.neteaseloginpoc.NeteaseApi
import tech.musxi.neteaseloginpoc.NeteaseLibraryApi
import tech.musxi.qqloginpoc.QqMusicApi
import tech.musxi.qqloginpoc.QqLibraryApi

/** Menu writes remain on their own platform and recheck server ownership before mutation. */
class PlaylistRepository(
    private val accounts: AccountRepository,
    private val favoriteWrite: (SongSummary, (Result<Unit>) -> Unit) -> Unit,
    private val changed: (Platform, LibraryCacheIdentity) -> Unit,
) {
    fun listWritable(platform: Platform, result: (Result<List<EditablePlaylist>>) -> Unit) {
        val owner = accounts.playbackIdentity(platform)?.takeIf { it.verified }
            ?: return result(Result.failure(LibraryPageFailure("account_unverified")))
        val current = { accounts.playbackIdentity(platform) == owner }
        fun target(id: String, name: String, cover: String?, count: Int, favorite: Boolean) = EditablePlaylist(
            PlaylistSummary(MediaKey(platform, id), name, ArtworkPolicy.sanitize(platform, cover), count), owner.generation, favorite)
        val callback: (Result<List<EditablePlaylist>>) -> Unit = { response ->
            result(if (current()) safe(response) else Result.failure(LibraryPageFailure("account_changed")))
        }
        when (platform) {
            Platform.KUGOU -> accounts.withKugouSession({ api, session ->
                KugouLibraryApi(api).writablePlaylists(session, current).map { target(it.id, it.name, it.coverUrl, it.trackCount, it.favorite) }
            }, callback, RequestOperation.FAVORITES_READ)
            Platform.QQ -> accounts.withQqSession({ api, session ->
                QqLibraryApi(api, current).writablePlaylists(session).map { target(it.id, it.name, it.coverUrl, it.trackCount, it.liked) }
            }, callback, RequestOperation.FAVORITES_READ)
            Platform.NETEASE -> accounts.withNeteaseSession({ api, session ->
                NeteaseLibraryApi(api, current).writablePlaylists(session).map { target(it.id, it.name, it.coverUrl, it.trackCount, it.liked) }
            }, callback, RequestOperation.FAVORITES_READ)
        }
    }

    fun addToPlaylist(song: SongSummary, target: EditablePlaylist, result: (Result<Unit>) -> Unit) {
        val platform = song.key.platform
        val owner = accounts.playbackIdentity(platform)
        if (target.playlist.key.platform != platform || owner?.verified != true || owner.generation != target.accountGeneration ||
            accounts.states.value.getValue(platform).phase != AccountPhase.CONNECTED)
            return result(Result.failure(LibraryPageFailure("account_changed")))
        if (target.favorite) { favoriteWrite(song, result); return }
        val current = { accounts.playbackIdentity(platform) == owner }
        val callback: (Result<Unit>) -> Unit = { response ->
            val checked = if (current()) safe(response) else Result.failure(LibraryPageFailure("account_changed"))
            if (checked.isSuccess) changed(platform, owner)
            result(checked)
        }
        when (platform) {
            Platform.KUGOU -> accounts.withKugouSession({ api, session ->
                KugouLibraryApi(api).addToPlaylist(session, target.playlist.key.id, song.key.id, song.title, song.durationMs, current)
            }, callback, RequestOperation.FAVORITES_WRITE)
            Platform.QQ -> accounts.withQqSession({ api, session ->
                QqLibraryApi(api, current).addToPlaylist(session, target.playlist.key.id, song.key.id)
            }, callback, RequestOperation.FAVORITES_WRITE)
            Platform.NETEASE -> accounts.withNeteaseSession({ api, session ->
                if (!current()) throw LibraryPageFailure("account_changed")
                NeteaseLibraryApi(api, current).addToPlaylist(session, target.playlist.key.id, song.key.id)
            }, callback, RequestOperation.FAVORITES_WRITE)
        }
    }

    private fun <T> safe(response: Result<T>): Result<T> = response.recoverCatching { failure ->
        val category = when (failure) {
            is LibraryPageFailure -> failure.category
            is KugouApi.ApiException -> failure.code
            is QqMusicApi.ApiException -> failure.code
            is NeteaseApi.ApiException -> failure.code
            is java.io.IOException -> "network"
            else -> "unavailable"
        }
        throw LibraryPageFailure(category.takeIf { it in setOf("network", "timeout", "http", "expired", "identity",
            "permission", "protocol", "risk", "rate", "limit", "incomplete", "account_changed") } ?: "unavailable")
    }
}
