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
    private val favoriteWrite: (SongSummary, Boolean, (Result<Unit>) -> Unit) -> Unit,
    private val changed: (Platform, LibraryCacheIdentity) -> Unit,
    private val catalogFor: ((Platform) -> PlaylistCatalog)? = null,
    private val onDeleted: (MediaKey) -> Unit = {},
) {
    /** Backend-owned pagination, revision and Musxi-only ordering; call open() on entering the page. */
    fun catalog(platform: Platform): PlaylistCatalog = requireNotNull(catalogFor)(platform)

    private val managementWrites = PlaylistMutationGate()
    /** Includes write-time permission checks and confirmation; the UI may disable conflicting actions. */
    val busyPlatforms = managementWrites.busyPlatforms

    private fun <T> exclusive(platform: Platform, result: (Result<T>) -> Unit,
        work: ((Result<T>) -> Unit) -> Unit) {
        managementWrites.run(platform, result, work)
    }

    private val deletion = PlaylistDeletionCoordinator(object : PlaylistDeletionPort {
        override fun generation(platform: Platform) = membershipPort.generation(platform)
        override fun exists(target: EditablePlaylist, result: (Result<Boolean>) -> Unit) {
            val platform = target.playlist.key.platform
            val owner = accounts.playbackIdentity(platform)
            if (owner?.verified != true || generation(platform) != target.accountGeneration)
                return result(Result.failure(LibraryPageFailure("account_changed")))
            val current = { accounts.playbackIdentity(platform) == owner && generation(platform) == owner.generation }
            val callback: (Result<Boolean>) -> Unit = { response ->
                result(if (current()) safe(response) else Result.failure(LibraryPageFailure("account_changed")))
            }
            when (platform) {
                Platform.KUGOU -> accounts.withKugouSession({ api, session ->
                    KugouLibraryApi(api).deletablePlaylist(session, target.playlist.key.id, current) != null
                }, callback, RequestOperation.FAVORITES_READ)
                Platform.QQ -> accounts.withQqSession({ api, session ->
                    QqLibraryApi(api, current).deletablePlaylist(session, target.playlist.key.id) != null
                }, callback, RequestOperation.FAVORITES_READ)
                Platform.NETEASE -> accounts.withNeteaseSession({ api, session ->
                    NeteaseLibraryApi(api, current).deletablePlaylist(session, target.playlist.key.id) != null
                }, callback, RequestOperation.FAVORITES_READ)
            }
        }
        override fun delete(target: EditablePlaylist, result: (Result<Unit>) -> Unit) {
            val platform = target.playlist.key.platform
            val owner = accounts.playbackIdentity(platform)
            if (owner?.verified != true || generation(platform) != target.accountGeneration)
                return result(Result.failure(LibraryPageFailure("account_changed")))
            val current = { accounts.playbackIdentity(platform) == owner && generation(platform) == owner.generation }
            val callback: (Result<Unit>) -> Unit = { response ->
                result(if (current()) safe(response) else Result.failure(LibraryPageFailure("account_changed")))
            }
            when (platform) {
                Platform.KUGOU -> accounts.withKugouSession({ api, session ->
                    KugouLibraryApi(api).deletePlaylist(session, target.playlist.key.id, current)
                }, callback, RequestOperation.FAVORITES_WRITE)
                Platform.QQ -> accounts.withQqSession({ api, session ->
                    QqLibraryApi(api, current).deletePlaylist(session, target.playlist.key.id)
                }, callback, RequestOperation.FAVORITES_WRITE)
                Platform.NETEASE -> accounts.withNeteaseSession({ api, session ->
                    NeteaseLibraryApi(api, current).deletePlaylist(session, target.playlist.key.id)
                }, callback, RequestOperation.FAVORITES_WRITE)
            }
        }
    })

    fun deletePlaylist(target: EditablePlaylist, result: (Result<PlaylistDeletionResult>) -> Unit) {
        val platform = target.playlist.key.platform
        if (!capabilities(platform).delete) return result(Result.failure(LibraryPageFailure("unsupported")))
        val owner = accounts.playbackIdentity(platform)
        exclusive(platform, result) { finish ->
            deletion.delete(target) { response ->
                if (owner != null && accounts.playbackIdentity(platform) == owner) {
                    if (response.getOrNull()?.confirmedAbsent == true) {
                        catalogFor?.invoke(platform)?.deleted(target.playlist.key)
                        onDeleted(target.playlist.key)
                    }
                    changed(platform, owner)
                }
                finish(response)
            }
        }
    }

    /** A fresh per-row menu capability. Hints never replace the platform's write-time checks. */
    fun managementInfo(key: MediaKey, result: (Result<PlaylistManagementInfo>) -> Unit) {
        val platform = key.platform
        val owner = accounts.playbackIdentity(platform)?.takeIf { it.verified &&
            membershipPort.generation(platform) == it.generation }
            ?: return result(Result.failure(LibraryPageFailure("account_unverified")))
        val current = { accounts.playbackIdentity(platform) == owner && membershipPort.generation(platform) == owner.generation }
        fun info(id: String, title: String, cover: String?, count: Int, kind: PlaylistKind,
            rename: Boolean, delete: Boolean): PlaylistManagementInfo {
            val summary = PlaylistSummary(MediaKey(platform, id), title, ArtworkPolicy.sanitize(platform, cover), count, kind)
            return PlaylistManagementInfo(EditablePlaylist(summary, owner.generation, kind == PlaylistKind.LIKED),
                rename, delete, kind == PlaylistKind.COLLECTED && capabilities(platform).follow,
                when { kind == PlaylistKind.LIKED -> "permission"
                    !rename && !delete -> "partial_support"
                    else -> null })
        }
        val callback: (Result<PlaylistManagementInfo>) -> Unit = { response ->
            result(if (current()) safe(response) else Result.failure(LibraryPageFailure("account_changed")))
        }
        when (platform) {
            Platform.KUGOU -> accounts.withKugouSession({ api, session ->
                val raw = KugouLibraryApi(api).accountPlaylist(session, key.id, current)
                info(raw.id, raw.name, raw.coverUrl, raw.trackCount,
                    when (raw.kind) { KugouLibraryApi.Playlist.Kind.LIKED -> PlaylistKind.LIKED
                        KugouLibraryApi.Playlist.Kind.CREATED -> PlaylistKind.CREATED
                        KugouLibraryApi.Playlist.Kind.COLLECTED -> PlaylistKind.COLLECTED
                        else -> PlaylistKind.OTHER }, raw.canRename(), raw.canDelete())
            }, callback, RequestOperation.FAVORITES_READ)
            Platform.QQ -> accounts.withQqSession({ api, session ->
                val library = QqLibraryApi(api, current)
                val raw = freshTarget<QqLibraryApi.Playlist>(key.id, current, { it.id }) { offset ->
                    library.playlists(session, offset, 100).let { Page(it.items, it.nextOffset.takeIf { n -> n >= 0 }, it.total.takeIf { n -> n >= 0 }) }
                }
                val kind = when (raw.kind) { QqLibraryApi.Playlist.Kind.LIKED -> PlaylistKind.LIKED
                    QqLibraryApi.Playlist.Kind.CREATED -> PlaylistKind.CREATED
                    QqLibraryApi.Playlist.Kind.COLLECTED -> PlaylistKind.COLLECTED
                    else -> PlaylistKind.OTHER }
                val ordinary = kind == PlaylistKind.CREATED && raw.id.substringBefore('_') != "0" &&
                    raw.id.substringAfter('_').toIntOrNull()?.let { it > 0 && it !in 201..209 } == true
                if (ordinary) library.deletablePlaylist(session, raw.id)
                info(raw.id, raw.name, raw.coverUrl, raw.trackCount, kind, ordinary, ordinary)
            }, callback, RequestOperation.FAVORITES_READ)
            Platform.NETEASE -> accounts.withNeteaseSession({ api, session ->
                val library = NeteaseLibraryApi(api, current)
                val raw = freshTarget<NeteaseLibraryApi.Playlist>(key.id, current, { it.id }) { offset ->
                    library.playlists(session, offset, 100).let { Page(it.items, it.nextOffset.takeIf { n -> n >= 0 }, it.total.takeIf { n -> n >= 0 }) }
                }
                val kind = when (raw.kind) { NeteaseLibraryApi.Playlist.Kind.LIKED -> PlaylistKind.LIKED
                    NeteaseLibraryApi.Playlist.Kind.CREATED -> PlaylistKind.CREATED
                    NeteaseLibraryApi.Playlist.Kind.COLLECTED -> PlaylistKind.COLLECTED
                    else -> PlaylistKind.OTHER }
                val ordinary = kind == PlaylistKind.CREATED && raw.writable && !raw.liked
                val deletable = ordinary && try {
                    library.deletablePlaylist(session, raw.id) != null
                } catch (failure: NeteaseApi.ApiException) {
                    if (failure.code != "permission") throw failure
                    false
                }
                info(raw.id, raw.name, raw.coverUrl, raw.trackCount, kind, deletable, deletable)
            }, callback, RequestOperation.FAVORITES_READ)
        }
    }

    private fun <T> freshTarget(id: String, current: () -> Boolean, key: (T) -> String,
        load: (Int) -> Page<T>): T {
        var offset = 0
        var total: Int? = null
        val seen = mutableSetOf<String>()
        var target: T? = null
        do {
            if (!current()) throw LibraryPageFailure("account_changed")
            val page = load(offset)
            if (page.total != null) {
                if (total != null && total != page.total) throw LibraryPageFailure("incomplete")
                total = page.total
            }
            page.items.forEach {
                if (!seen.add(key(it)) || seen.size > 20_000) throw LibraryPageFailure("incomplete")
                if (key(it) == id) target = it
            }
            val next = page.nextOffset ?: break
            if (next <= offset || next > 20_000) throw LibraryPageFailure("incomplete")
            offset = next
        } while (true)
        if (total != null && total != seen.size) throw LibraryPageFailure("incomplete")
        if (!current()) throw LibraryPageFailure("account_changed")
        return target ?: throw LibraryPageFailure("unavailable")
    }
    fun listWritable(platform: Platform, result: (Result<List<EditablePlaylist>>) -> Unit) {
        val owner = accounts.playbackIdentity(platform)?.takeIf { it.verified }
            ?: return result(Result.failure(LibraryPageFailure("account_unverified")))
        val current = { accounts.playbackIdentity(platform) == owner }
        fun target(id: String, name: String, cover: String?, count: Int, favorite: Boolean) = EditablePlaylist(
            PlaylistSummary(MediaKey(platform, id), name, ArtworkPolicy.sanitize(platform, cover), count,
                if (favorite) PlaylistKind.LIKED else PlaylistKind.CREATED), owner.generation, favorite)
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

    private fun writePresent(song: SongSummary, target: EditablePlaylist, present: Boolean, result: (Result<Unit>) -> Unit) {
        if (song.key.platform != target.playlist.key.platform) {
            result(Result.failure(LibraryPageFailure("invalid_request"))); return
        }
        listWritable(song.key.platform) { response ->
            val targets = response.getOrElse { result(Result.failure(it)); return@listWritable }
            val verified = targets.singleOrNull { it.playlist.key == target.playlist.key &&
                it.accountGeneration == target.accountGeneration && it.favorite == target.favorite }
            if (verified == null) result(Result.failure(LibraryPageFailure("permission")))
            else writeVerifiedPresent(song, verified, present, result)
        }
    }

    private fun writeVerifiedPresent(song: SongSummary, target: EditablePlaylist, present: Boolean, result: (Result<Unit>) -> Unit) {
        val platform = song.key.platform
        val owner = accounts.playbackIdentity(platform)
        if (target.playlist.key.platform != platform || owner?.verified != true || owner.generation != target.accountGeneration ||
            accounts.states.value.getValue(platform).phase != AccountPhase.CONNECTED)
            return result(Result.failure(LibraryPageFailure("account_changed")))
        if (target.favorite) { favoriteWrite(song, present, result); return }
        val current = { accounts.playbackIdentity(platform) == owner }
        val callback: (Result<Unit>) -> Unit = { response ->
            val checked = if (current()) safe(response) else Result.failure(LibraryPageFailure("account_changed"))
            if (current()) changed(platform, owner) // Also invalidate an ambiguous failed response.
            result(checked)
        }
        when (platform) {
            Platform.KUGOU -> accounts.withKugouSession({ api, session ->
                if (present) KugouLibraryApi(api).addToPlaylist(session, target.playlist.key.id, song.key.id, song.title, song.durationMs, current)
                else KugouLibraryApi(api).removeFromPlaylist(session, target.playlist.key.id, song.key.id, current)
            }, callback, RequestOperation.FAVORITES_WRITE)
            Platform.QQ -> accounts.withQqSession({ api, session ->
                if (present) QqLibraryApi(api, current).addToPlaylist(session, target.playlist.key.id, song.key.id)
                else QqLibraryApi(api, current).removeFromPlaylist(session, target.playlist.key.id, song.key.id)
            }, callback, RequestOperation.FAVORITES_WRITE)
            Platform.NETEASE -> accounts.withNeteaseSession({ api, session ->
                if (!current()) throw LibraryPageFailure("account_changed")
                if (present) NeteaseLibraryApi(api, current).addToPlaylist(session, target.playlist.key.id, song.key.id)
                else NeteaseLibraryApi(api, current).removeFromPlaylist(session, target.playlist.key.id, song.key.id)
            }, callback, RequestOperation.FAVORITES_WRITE)
        }
    }

    private val membershipPort = object : PlaylistMembershipPort {
        override fun generation(platform: Platform): Long? = accounts.playbackIdentity(platform)
            ?.takeIf { it.verified && accounts.states.value.getValue(platform).phase == AccountPhase.CONNECTED }?.generation
        override fun listWritable(platform: Platform, result: (Result<List<EditablePlaylist>>) -> Unit) =
            this@PlaylistRepository.listWritable(platform, result)
        override fun setPresent(song: SongSummary, target: EditablePlaylist, present: Boolean, result: (Result<Unit>) -> Unit) =
            writePresent(song, target, present, result)
        override fun contains(song: MediaKey, target: EditablePlaylist, result: (Result<Boolean>) -> Unit) {
            val owner = accounts.playbackIdentity(song.platform)
            if (song.platform != target.playlist.key.platform || owner?.verified != true ||
                generation(song.platform) != target.accountGeneration) {
                result(Result.failure(LibraryPageFailure("account_changed"))); return
            }
            val current = { accounts.playbackIdentity(song.platform) == owner && generation(song.platform) == owner.generation }
            val callback: (Result<Boolean>) -> Unit = { response ->
                result(if (current()) safe(response) else Result.failure(LibraryPageFailure("account_changed")))
            }
            playlistSongIds(target) { response -> callback(response.map { keys ->
                keys.any { it.id.equals(song.id, ignoreCase = song.platform == Platform.KUGOU) }
            }) }
        }
    }
    private val membership = PlaylistMembershipCoordinator(membershipPort)

    private val subscriptionPort = object : PlaylistSubscriptionPort {
        override fun generation(platform: Platform): Long? = membershipPort.generation(platform)

        override fun read(playlist: MediaKey, result: (Result<PlaylistSubscription>) -> Unit) {
            val platform = playlist.platform
            val owner = accounts.playbackIdentity(platform)?.takeIf { it.verified && generation(platform) == it.generation }
                ?: return result(Result.failure(LibraryPageFailure("account_unverified")))
            val current = { accounts.playbackIdentity(platform) == owner && generation(platform) == owner.generation }
            fun snapshot(id: String, title: String, cover: String?, count: Int, followed: Boolean) = PlaylistSubscription(
                PlaylistSummary(MediaKey(platform, id), title, ArtworkPolicy.sanitize(platform, cover), count,
                    PlaylistKind.COLLECTED), owner.generation, followed)
            val callback: (Result<PlaylistSubscription>) -> Unit = { response ->
                result(if (current()) safe(response) else Result.failure(LibraryPageFailure("account_changed")))
            }
            when (platform) {
                Platform.QQ -> accounts.withQqSession({ api, session ->
                    QqLibraryApi(api, current).playlistSubscription(session, playlist.id).let {
                        snapshot(playlist.id, it.playlist.name, it.playlist.coverUrl, it.playlist.trackCount, it.followed)
                    }
                }, callback, RequestOperation.FAVORITES_READ)
                Platform.NETEASE -> accounts.withNeteaseSession({ api, session ->
                    NeteaseLibraryApi(api, current).playlistSubscription(session, playlist.id).let {
                        snapshot(it.playlist.id, it.playlist.name, it.playlist.coverUrl, it.playlist.trackCount, it.followed)
                    }
                }, callback, RequestOperation.FAVORITES_READ)
                Platform.KUGOU -> result(Result.failure(LibraryPageFailure("unsupported")))
            }
        }

        override fun setFollowed(snapshot: PlaylistSubscription, followed: Boolean, result: (Result<Unit>) -> Unit) {
            val platform = snapshot.playlist.key.platform
            val owner = accounts.playbackIdentity(platform)
            if (owner?.verified != true || generation(platform) != snapshot.accountGeneration)
                return result(Result.failure(LibraryPageFailure("account_changed")))
            val current = { accounts.playbackIdentity(platform) == owner && generation(platform) == owner.generation }
            val callback: (Result<Unit>) -> Unit = { response ->
                if (current()) changed(platform, owner)
                result(if (current()) safe(response) else Result.failure(LibraryPageFailure("account_changed")))
            }
            when (platform) {
                Platform.QQ -> accounts.withQqSession({ api, session ->
                    QqLibraryApi(api, current).setPlaylistFollowed(session, snapshot.playlist.key.id, followed)
                }, callback, RequestOperation.FAVORITES_WRITE)
                Platform.NETEASE -> accounts.withNeteaseSession({ api, session ->
                    NeteaseLibraryApi(api, current).setPlaylistFollowed(session, snapshot.playlist.key.id, followed)
                }, callback, RequestOperation.FAVORITES_WRITE)
                Platform.KUGOU -> result(Result.failure(LibraryPageFailure("unsupported")))
            }
        }
    }
    private val subscription = PlaylistSubscriptionCoordinator(subscriptionPort)

    fun playlistSubscription(key: MediaKey, result: (Result<PlaylistSubscription>) -> Unit) = subscription.read(key, result)

    fun setPlaylistFollowed(snapshot: PlaylistSubscription, followed: Boolean, result: (Result<PlaylistFollowResult>) -> Unit) =
        exclusive(snapshot.playlist.key.platform, result) { subscription.update(snapshot, followed, it) }

    /** Fresh full ordered ids, never a retained page or a partial cache. */
    fun playlistSongIds(target: EditablePlaylist, result: (Result<List<MediaKey>>) -> Unit) {
        val platform = target.playlist.key.platform
        val owner = accounts.playbackIdentity(platform)
        if (owner?.verified != true || membershipPort.generation(platform) != target.accountGeneration)
            return result(Result.failure(LibraryPageFailure("account_changed")))
        val current = { accounts.playbackIdentity(platform) == owner && membershipPort.generation(platform) == owner.generation }
        val callback: (Result<List<String>>) -> Unit = { response ->
            result(if (current()) safe(response).map { ids -> ids.map { MediaKey(platform, it) } }
                else Result.failure(LibraryPageFailure("account_changed")))
        }
        when (platform) {
            Platform.KUGOU -> accounts.withKugouSession({ api, session ->
                KugouLibraryApi(api).playlistTrackIds(session, target.playlist.key.id, current)
            }, callback, RequestOperation.FAVORITES_READ)
            Platform.QQ -> accounts.withQqSession({ api, session ->
                QqLibraryApi(api, current).playlistTrackIds(session, target.playlist.key.id)
            }, callback, RequestOperation.FAVORITES_READ)
            Platform.NETEASE -> accounts.withNeteaseSession({ api, session ->
                if (!current()) throw LibraryPageFailure("account_changed")
                NeteaseLibraryApi(api, current).trackIds(session, target.playlist.key.id)
            }, callback, RequestOperation.FAVORITES_READ)
        }
    }

    fun membershipFor(song: MediaKey, result: (Result<SongPlaylistMembership>) -> Unit) = membership.read(song, result)

    fun updateMembership(song: SongSummary, snapshot: SongPlaylistMembership, desired: Map<MediaKey, Boolean>,
        result: (Result<PlaylistMembershipResult>) -> Unit) = exclusive(song.key.platform, result) {
            membership.update(song, snapshot, desired, it)
        }

    fun addToPlaylist(song: SongSummary, target: EditablePlaylist, result: (Result<Unit>) -> Unit) =
        exclusive(song.key.platform, result) { changeOne(song, target, true, it) }

    fun removeFromPlaylist(song: SongSummary, target: EditablePlaylist, result: (Result<Unit>) -> Unit) =
        exclusive(song.key.platform, result) { changeOne(song, target, false, it) }

    private fun changeOne(song: SongSummary, target: EditablePlaylist, present: Boolean, result: (Result<Unit>) -> Unit) {
        membershipPort.contains(song.key, target) { before ->
            val value = before.getOrElse { result(Result.failure(it)); return@contains }
            val snapshot = SongPlaylistMembership(song.key, target.accountGeneration, listOf(PlaylistMembershipEntry(target, value)))
            membership.update(song, snapshot, mapOf(target.playlist.key to present)) { response ->
                result(response.mapCatching { batch ->
                    if (!batch.successful) throw LibraryPageFailure(batch.results.first().error ?: "not_confirmed")
                })
            }
        }
    }

    /** A failed add never hides a successfully created list or encourages duplicate creation. */
    fun createPlaylistAndAdd(song: SongSummary, name: String, result: (Result<CreatedPlaylistSongResult>) -> Unit) {
        exclusive(song.key.platform, result) { finish ->
            createPlaylistOnce(song.key.platform, name) created@{ created ->
                val target = created.getOrElse { finish(Result.failure(it)); return@created }
                changeOne(song, target, true) { added -> finish(Result.success(CreatedPlaylistSongResult(target, added))) }
            }
        }
    }

    fun setSongsPresent(target: EditablePlaylist, songs: List<SongSummary>, present: Boolean,
        result: (Result<PlaylistSongsResult>) -> Unit) = exclusive(target.playlist.key.platform, result) {
            setSongsPresentOnce(target, songs, present, it)
        }

    private fun setSongsPresentOnce(target: EditablePlaylist, songs: List<SongSummary>, present: Boolean,
        result: (Result<PlaylistSongsResult>) -> Unit) {
        val selected = songs.toList()
        if (selected.size > 100 || selected.map { it.key }.distinct().size != selected.size ||
            selected.any { it.key.platform != target.playlist.key.platform }) {
            result(Result.failure(LibraryPageFailure("invalid_request"))); return
        }
        val outcomes = mutableListOf<PlaylistSongChangeResult>()
        fun next(index: Int) {
            if (index == selected.size) return result(Result.success(PlaylistSongsResult(target.playlist.key, outcomes.toList())))
            val song = selected[index]
            membershipPort.contains(song.key, target) before@{ before ->
                val exists = before.getOrElse {
                    outcomes += PlaylistSongChangeResult(song.key, PlaylistChangeResult(target.playlist.key, present,
                        PlaylistChangeStatus.FAILED, error = (it as? LibraryPageFailure)?.category ?: "unavailable"))
                    next(index + 1); return@before
                }
                val snapshot = SongPlaylistMembership(song.key, target.accountGeneration, listOf(PlaylistMembershipEntry(target, exists)))
                membership.update(song, snapshot, mapOf(target.playlist.key to present)) { changed ->
                    val outcome = changed.fold(onSuccess = { it.results.single() }, onFailure = {
                        PlaylistChangeResult(target.playlist.key, present, PlaylistChangeStatus.FAILED,
                            error = (it as? LibraryPageFailure)?.category ?: "unavailable")
                    })
                    outcomes += PlaylistSongChangeResult(song.key, outcome)
                    next(index + 1)
                }
            }
        }
        next(0)
    }

    fun capabilities(platform: Platform): PlaylistManagementCapabilities = when (platform) {
        Platform.QQ -> PlaylistManagementCapabilities(create = true, rename = true, follow = true, reason = "partial_support", delete = true)
        Platform.NETEASE -> PlaylistManagementCapabilities(create = true, rename = true, follow = false, reason = "verification_required", delete = true)
        Platform.KUGOU -> PlaylistManagementCapabilities(create = true, rename = true, follow = false, reason = "partial_support", delete = true)
    }

    fun createPlaylist(platform: Platform, name: String, result: (Result<EditablePlaylist>) -> Unit) {
        exclusive(platform, result) { createPlaylistOnce(platform, name, it) }
    }

    private fun createPlaylistOnce(platform: Platform, name: String, result: (Result<EditablePlaylist>) -> Unit) {
        val owner = accounts.playbackIdentity(platform)?.takeIf { it.verified &&
            membershipPort.generation(platform) == it.generation }
            ?: return result(Result.failure(LibraryPageFailure("account_unverified")))
        if (!capabilities(platform).create) return result(Result.failure(LibraryPageFailure("unsupported")))
        if (!validName(name)) return result(Result.failure(LibraryPageFailure("invalid_request")))
        val current = { accounts.playbackIdentity(platform) == owner && membershipPort.generation(platform) == owner.generation }
        fun target(id: String, title: String, cover: String?, count: Int) = EditablePlaylist(
            PlaylistSummary(MediaKey(platform, id), title, ArtworkPolicy.sanitize(platform, cover), count, PlaylistKind.CREATED),
            owner.generation, false)
        val callback: (Result<EditablePlaylist>) -> Unit = { response ->
            if (current()) response.getOrNull()?.let { catalogFor?.invoke(platform)?.created(it.playlist.key) }
            if (current()) changed(platform, owner)
            result(if (current()) safe(response) else Result.failure(LibraryPageFailure("account_changed")))
        }
        when (platform) {
            Platform.QQ -> accounts.withQqSession({ api, session ->
                if (!current()) throw LibraryPageFailure("account_changed")
                QqLibraryApi(api, current).createPlaylist(session, name).let { target(it.id, it.name, it.coverUrl, it.trackCount) }
            }, callback, RequestOperation.FAVORITES_WRITE)
            Platform.NETEASE -> accounts.withNeteaseSession({ api, session ->
                if (!current()) throw LibraryPageFailure("account_changed")
                val library = NeteaseLibraryApi(api, current)
                val id = library.createPlaylist(session, name)
                library.writablePlaylists(session).singleOrNull { it.id == id }?.let {
                    target(it.id, it.name, it.coverUrl, it.trackCount)
                } ?: throw LibraryPageFailure("incomplete")
            }, callback, RequestOperation.FAVORITES_WRITE)
            Platform.KUGOU -> accounts.withKugouSession({ api, session ->
                if (!current()) throw LibraryPageFailure("account_changed")
                KugouLibraryApi(api).createPlaylist(session, name, current).let {
                    target(it.id, it.name, it.coverUrl, it.trackCount)
                }
            }, callback, RequestOperation.FAVORITES_WRITE)
        }
    }

    fun renamePlaylist(target: EditablePlaylist, name: String, result: (Result<Unit>) -> Unit) {
        exclusive(target.playlist.key.platform, result) { renamePlaylistOnce(target, name, it) }
    }

    private fun renamePlaylistOnce(target: EditablePlaylist, name: String, result: (Result<Unit>) -> Unit) {
        val platform = target.playlist.key.platform
        if (membershipPort.generation(platform) != target.accountGeneration)
            return result(Result.failure(LibraryPageFailure("account_changed")))
        if (target.favorite) return result(Result.failure(LibraryPageFailure("permission")))
        if (!validName(name)) return result(Result.failure(LibraryPageFailure("invalid_request")))
        if (!capabilities(platform).rename) return result(Result.failure(LibraryPageFailure("unsupported")))
        val owner = requireNotNull(accounts.playbackIdentity(platform))
        val current = { accounts.playbackIdentity(platform) == owner && membershipPort.generation(platform) == owner.generation }
        val callback: (Result<Unit>) -> Unit = { response ->
            if (current()) changed(platform, owner)
            result(if (current()) safe(response) else Result.failure(LibraryPageFailure("account_changed")))
        }
        when (platform) {
            Platform.QQ -> accounts.withQqSession({ api, session ->
                if (!current()) throw LibraryPageFailure("account_changed")
                QqLibraryApi(api, current).renamePlaylist(session, target.playlist.key.id, name)
            }, callback, RequestOperation.FAVORITES_WRITE)
            Platform.KUGOU -> accounts.withKugouSession({ api, session ->
                if (!current()) throw LibraryPageFailure("account_changed")
                KugouLibraryApi(api).renamePlaylist(session, target.playlist.key.id, name, current)
            }, callback, RequestOperation.FAVORITES_WRITE)
            Platform.NETEASE -> accounts.withNeteaseSession({ api, session ->
                if (!current()) throw LibraryPageFailure("account_changed")
                NeteaseLibraryApi(api, current).renamePlaylist(session, target.playlist.key.id, name)
            }, callback, RequestOperation.FAVORITES_WRITE)
        }
    }

    private fun validName(name: String): Boolean = name.isNotBlank() && name.codePointCount(0, name.length) <= 80 &&
        name.none(Char::isISOControl)

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
            "permission", "protocol", "platform", "risk", "rate", "limit", "incomplete", "account_changed", "unsupported", "verification_required", "not_confirmed", "request") } ?: "unavailable")
    }
}
