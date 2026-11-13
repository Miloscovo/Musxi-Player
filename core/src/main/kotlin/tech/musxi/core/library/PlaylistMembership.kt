package tech.musxi.core.library

import tech.musxi.core.account.Platform

/** A complete read of this song's membership in the account's writable lists. */
data class SongPlaylistMembership(
    val song: MediaKey,
    val accountGeneration: Long,
    val entries: List<PlaylistMembershipEntry>,
)
data class PlaylistMembershipEntry(val target: EditablePlaylist, val present: Boolean)
enum class PlaylistChangeStatus { APPLIED, UNCHANGED, CONFLICT, FAILED, UNKNOWN }
data class PlaylistChangeResult(
    val playlist: MediaKey,
    val requestedPresent: Boolean,
    val status: PlaylistChangeStatus,
    val confirmedPresent: Boolean? = null,
    val error: String? = null,
)
data class PlaylistMembershipResult(val song: MediaKey, val results: List<PlaylistChangeResult>) {
    val successful: Boolean get() = results.all {
        it.status == PlaylistChangeStatus.APPLIED || it.status == PlaylistChangeStatus.UNCHANGED
    }
}

/** Transport callbacks must finish once; account credentials never cross this boundary. */
interface PlaylistMembershipPort {
    fun generation(platform: Platform): Long?
    fun listWritable(platform: Platform, result: (Result<List<EditablePlaylist>>) -> Unit)
    fun contains(song: MediaKey, target: EditablePlaylist, result: (Result<Boolean>) -> Unit)
    fun setPresent(song: SongSummary, target: EditablePlaylist, present: Boolean, result: (Result<Unit>) -> Unit)
}

/** Explicit per-list intent. Never interprets an omitted list as a removal. */
class PlaylistMembershipCoordinator(private val port: PlaylistMembershipPort) {
    private val writing = mutableSetOf<Platform>()

    fun read(song: MediaKey, result: (Result<SongPlaylistMembership>) -> Unit) {
        val generation = port.generation(song.platform) ?: return fail("account_unverified", result)
        fun current() = port.generation(song.platform) == generation
        port.listWritable(song.platform) { lists ->
            if (!current()) return@listWritable fail("account_changed", result)
            val targets = lists.getOrElse { return@listWritable result(Result.failure(it)) }
            if (targets.size > 500) return@listWritable fail("limit", result)
            if (targets.map { it.playlist.key }.distinct().size != targets.size || targets.any {
                it.playlist.key.platform != song.platform || it.accountGeneration != generation
            }) return@listWritable fail("protocol", result)
            val entries = mutableListOf<PlaylistMembershipEntry>()
            fun next(index: Int) {
                if (!current()) return fail("account_changed", result)
                if (index == targets.size) return result(Result.success(SongPlaylistMembership(song, generation, entries.toList())))
                val target = targets[index]
                port.contains(song, target) { response ->
                    if (!current()) return@contains fail("account_changed", result)
                    response.fold(onSuccess = { entries += PlaylistMembershipEntry(target, it); next(index + 1) },
                        onFailure = { result(Result.failure(it)) })
                }
            }
            next(0)
        }
    }

    fun update(song: SongSummary, snapshot: SongPlaylistMembership, desired: Map<MediaKey, Boolean>,
        result: (Result<PlaylistMembershipResult>) -> Unit) {
        val platform = song.key.platform
        if (snapshot.song != song.key) return fail("invalid_request", result)
        if (port.generation(platform) != snapshot.accountGeneration) return fail("account_changed", result)
        val entries = snapshot.entries.associateBy { it.target.playlist.key }
        if (entries.size != snapshot.entries.size || desired.size > 500 || desired.keys.any {
            it.platform != platform || it !in entries
        } || entries.values.any { it.target.accountGeneration != snapshot.accountGeneration ||
            it.target.playlist.key.platform != platform }) return fail("invalid_request", result)
        synchronized(writing) { if (!writing.add(platform)) return fail("busy", result) }
        // Copy caller-owned collections before the first asynchronous callback.
        val changes = desired.toList()
        val results = mutableListOf<PlaylistChangeResult>()
        fun current() = port.generation(platform) == snapshot.accountGeneration
        fun finish() {
            synchronized(writing) { writing.remove(platform) }
            result(Result.success(PlaylistMembershipResult(song.key, results.toList())))
        }
        fun next(index: Int) {
            if (index == changes.size) return finish()
            val (key, wanted) = changes[index]
            val entry = entries.getValue(key)
            fun completed(status: PlaylistChangeStatus, present: Boolean?, error: String? = null) {
                results += PlaylistChangeResult(key, wanted, status, present, error)
                next(index + 1)
            }
            if (!current()) return completed(PlaylistChangeStatus.FAILED, null, "account_changed")
            port.contains(song.key, entry.target) before@{ before ->
                if (!current()) return@before completed(PlaylistChangeStatus.FAILED, null, "account_changed")
                val actual = before.getOrElse {
                    return@before completed(PlaylistChangeStatus.FAILED, null, category(it))
                }
                if (actual == wanted) return@before completed(PlaylistChangeStatus.UNCHANGED, actual)
                // An unchanged checkbox is preservation intent, not permission to undo a remote edit.
                if (entry.present == wanted) return@before completed(PlaylistChangeStatus.CONFLICT, actual, "conflict")
                port.setPresent(song, entry.target, wanted) write@{ write ->
                    // Any attempted write can have reached the server even if its response was lost.
                    if (!current()) return@write completed(PlaylistChangeStatus.UNKNOWN, null, "account_changed")
                    port.contains(song.key, entry.target) confirm@{ confirmation ->
                        if (!current()) return@confirm completed(PlaylistChangeStatus.UNKNOWN, null, "account_changed")
                        val confirmed = confirmation.getOrElse {
                            return@confirm completed(PlaylistChangeStatus.UNKNOWN, null, category(it))
                        }
                        when {
                            confirmed == wanted -> completed(PlaylistChangeStatus.APPLIED, confirmed)
                            write.isFailure -> completed(PlaylistChangeStatus.FAILED, confirmed, category(write.exceptionOrNull()!!))
                            else -> completed(PlaylistChangeStatus.FAILED, confirmed, "not_confirmed")
                        }
                    }
                }
            }
        }
        next(0)
    }

    private fun category(error: Throwable): String = (error as? LibraryPageFailure)?.category ?: "unavailable"
    private fun <T> fail(category: String, result: (Result<T>) -> Unit) = result(Result.failure(LibraryPageFailure(category)))
}
