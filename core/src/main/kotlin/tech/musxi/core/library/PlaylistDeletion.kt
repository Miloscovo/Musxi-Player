package tech.musxi.core.library

import tech.musxi.core.account.Platform

data class PlaylistDeletionResult(
    val playlist: MediaKey,
    val status: PlaylistChangeStatus,
    val confirmedAbsent: Boolean? = null,
    val error: String? = null,
)

interface PlaylistDeletionPort {
    fun generation(platform: Platform): Long?
    /** Full fresh account collection. A protected/non-owned ID fails, never becomes absent. */
    fun exists(target: EditablePlaylist, result: (Result<Boolean>) -> Unit)
    /** Rechecks ownership and attempts at most one request. No automatic retry. */
    fun delete(target: EditablePlaylist, result: (Result<Unit>) -> Unit)
}

/** A missing cache row is never deletion evidence. Failed writes still receive one fresh read. */
class PlaylistDeletionCoordinator(private val port: PlaylistDeletionPort) {
    private val writing = PlaylistMutationGate()

    fun delete(target: EditablePlaylist, result: (Result<PlaylistDeletionResult>) -> Unit) {
        val key = target.playlist.key
        if (target.favorite || target.playlist.kind != PlaylistKind.CREATED)
            return result(Result.failure(LibraryPageFailure("permission")))
        if (port.generation(key.platform) != target.accountGeneration)
            return result(Result.failure(LibraryPageFailure("account_changed")))
        writing.run(key.platform, result) { finish -> deleteOnce(target, finish) }
    }

    private fun deleteOnce(target: EditablePlaylist, result: (Result<PlaylistDeletionResult>) -> Unit) {
        val key = target.playlist.key
        fun current() = port.generation(key.platform) == target.accountGeneration
        fun finish(status: PlaylistChangeStatus, absent: Boolean? = null, error: String? = null) {
            result(Result.success(PlaylistDeletionResult(key, status, absent, error)))
        }
        port.exists(target) before@{ before ->
            if (!current()) return@before finish(PlaylistChangeStatus.FAILED, error = "account_changed")
            val exists = before.getOrElse {
                return@before finish(PlaylistChangeStatus.FAILED, error = category(it))
            }
            if (!exists) return@before finish(PlaylistChangeStatus.UNCHANGED, true)
            port.delete(target) write@{ write ->
                if (!current()) return@write finish(PlaylistChangeStatus.UNKNOWN, error = "account_changed")
                port.exists(target) confirm@{ confirmation ->
                    if (!current()) return@confirm finish(PlaylistChangeStatus.UNKNOWN, error = "account_changed")
                    val present = confirmation.getOrElse {
                        return@confirm finish(PlaylistChangeStatus.UNKNOWN, error = category(it))
                    }
                    if (!present) finish(PlaylistChangeStatus.APPLIED, true)
                    else finish(PlaylistChangeStatus.FAILED, false,
                        write.exceptionOrNull()?.let(::category) ?: "not_confirmed")
                }
            }
        }
    }

    private fun category(error: Throwable) = (error as? LibraryPageFailure)?.category ?: "unavailable"
}
