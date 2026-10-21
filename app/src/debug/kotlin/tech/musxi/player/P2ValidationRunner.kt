package tech.musxi.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.SongSummary

/** Fixed debug operations. Favorite roundtrip requires explicit owner authorization; no private diagnostics. */
internal object P2ValidationRunner {
    private val main = Handler(Looper.getMainLooper())
    private var baseline = emptyList<SongSummary>()
    private var expected = emptyList<SongSummary>()
    private var currentKey: MediaKey? = null
    private var sourceKey: MediaKey? = null
    private var position = 0L
    private var revision = -1L
    private var result = "NONE"
    private class FavoriteCheck(val song: SongSummary, val keys: Set<MediaKey>) {
        var toggled = false
        var restoreSubmitted = false
        val originallyLiked get() = song.key in keys
    }
    private val favoriteChecks = mutableMapOf<Platform, FavoriteCheck>()

    fun run(app: MusxiApplication, step: String): Boolean {
        if (!step.startsWith("p2_")) return false
        val queue = app.playback.queueState.value
        val playback = app.playback.state.value
        fun captureExpected(next: List<SongSummary>) {
            expected = next
            currentKey = queue.songs.getOrNull(playback.queueIndex)?.key
            sourceKey = playback.sourceKey
            position = playback.positionMs
        }
        val done: (Result<Unit>) -> Unit = { result = if (it.isSuccess) "ACCEPTED" else "REJECTED" }
        val favoritePlatform = Platform.entries.firstOrNull { step.endsWith("_${it.name.lowercase()}") }
        if (favoritePlatform != null && step.startsWith("p2_fav_")) {
            val state = app.favorites.state.value.getValue(favoritePlatform)
            when {
                step.startsWith("p2_fav_prepare_") -> {
                    val saved = app.playbackJournal.state.value.getValue(favoritePlatform).queue
                    val song = saved?.songs?.getOrNull(saved.index)
                    val previous = favoriteChecks[favoritePlatform]
                    if (song != null && state.synced && !state.loading && (previous == null ||
                            (previous.toggled && state.keys == previous.keys))) {
                        favoriteChecks[favoritePlatform] = FavoriteCheck(song, state.keys.toSet())
                        result = "FAVORITE_PREPARED"
                    } else result = "FAVORITE_PREPARE_REJECTED"
                }
                step.startsWith("p2_fav_toggle_") -> {
                    val check = favoriteChecks[favoritePlatform]
                    if (check != null && !check.toggled && state.synced && !state.loading && state.keys == check.keys) {
                        check.toggled = true
                        app.favorites.setLiked(check.song, !check.originallyLiked)
                        result = "FAVORITE_TOGGLE_SUBMITTED"
                    } else result = "FAVORITE_TOGGLE_REJECTED"
                }
                step.startsWith("p2_fav_restore_") -> {
                    val check = favoriteChecks[favoritePlatform]
                    if (check != null && check.toggled && !check.restoreSubmitted && state.synced && !state.loading) {
                        check.restoreSubmitted = true
                        app.favorites.setLiked(check.song, check.originallyLiked)
                        result = "FAVORITE_RESTORE_SUBMITTED"
                    } else result = "FAVORITE_RESTORE_REJECTED"
                }
            }
        }
        when (step) {
            "p2_fav_probe_qq" -> favoriteChecks[Platform.QQ]?.let { check ->
                result = "DETAIL_PENDING"
                app.qqLibrary.song(check.song.key) { response ->
                    result = response.fold({ "DETAIL_SUCCESS" }, {
                        val code = (it as? tech.musxi.accounts.QqLibraryRepository.LibraryFailure)?.diagnostic
                        "DETAIL_FAILED_" + (code ?: (it as? tech.musxi.core.library.LibraryPageFailure)?.category ?: "unavailable")
                    })
                }
            }
            "p2_single_current" -> queue.songs.getOrNull(playback.queueIndex)?.let { song ->
                app.queuePlayback.playSong(song); result = "SINGLE_REQUESTED"
            }
            "p2_fav_import" -> {
                result = "FAVORITE_IMPORT_REJECTED"
                val file = java.io.File(app.noBackupFilesDir, "p2-favorite-recovery.json")
                if (file.isFile && file.length() <= 8 * 1024 * 1024) runCatching {
                    val root = org.json.JSONObject(file.readText())
                    val imported = mutableMapOf<Platform, FavoriteCheck>()
                    Platform.entries.forEach { platform ->
                        val original = root.getJSONObject(platform.name)
                        val saved = app.playbackJournal.state.value.getValue(platform).queue ?: error("missing")
                        val song = saved.songs.firstOrNull { it.key.id == original.getString("songId") } ?: error("missing")
                        val array = original.getJSONArray("ids"); check(array.length() <= 20_000)
                        val keys = (0 until array.length()).map { MediaKey(platform, array.getString(it)) }.toSet()
                        val state = app.favorites.state.value.getValue(platform)
                        check(state.synced && !state.loading)
                        check((keys - state.keys).all { it == song.key } && (state.keys - keys).all { it == song.key })
                        imported[platform] = FavoriteCheck(song, keys).also { it.toggled = keys != state.keys }
                    }
                    favoriteChecks.putAll(imported); result = "FAVORITE_IMPORTED"
                }
            }
            "p2_baseline" -> {
                baseline = queue.songs; revision = queue.revision
                captureExpected(baseline); result = "CAPTURED"
            }
            "p2_move_current_last" -> {
                val from = playback.queueIndex; val to = queue.songs.lastIndex
                if (from in queue.songs.indices) {
                    captureExpected(queue.songs.toMutableList().apply { add(to, removeAt(from)) })
                    result = "PENDING"; app.playback.moveQueueItem(from, to, queue.revision, done)
                }
            }
            "p2_move_last_first" -> if (queue.songs.isNotEmpty()) {
                captureExpected(queue.songs.toMutableList().apply { add(0, removeAt(lastIndex)) })
                result = "PENDING"; app.playback.moveQueueItem(queue.songs.lastIndex, 0, queue.revision, done)
            }
            "p2_remove_other" -> if (queue.songs.size > 1) {
                val index = if (playback.queueIndex == 0) queue.songs.lastIndex else 0
                captureExpected(queue.songs.filterIndexed { i, _ -> i != index })
                result = "PENDING"; app.playback.removeQueueItem(index, queue.revision, done)
            }
            "p2_remove_current" -> if (playback.queueIndex in queue.songs.indices) {
                captureExpected(queue.songs.filterIndexed { i, _ -> i != playback.queueIndex })
                result = "PENDING"; app.playback.removeQueueItem(playback.queueIndex, queue.revision, done)
            }
            "p2_append_current", "p2_next_current" -> queue.songs.getOrNull(playback.queueIndex)?.let { song ->
                val next = step == "p2_next_current"
                val index = if (next) playback.queueIndex + 1 else queue.songs.size
                captureExpected(queue.songs.toMutableList().apply { add(index, song) })
                result = "PENDING"; app.playback.enqueue(song, queue.revision, next, done)
            }
            "p2_clear" -> {
                captureExpected(emptyList()); result = "PENDING"; app.playback.clearQueue(queue.revision, done)
            }
            "p2_stale_remove" -> { captureExpected(queue.songs); result = "PENDING"; app.playback.removeQueueItem(0, revision, done) }
            "p2_invalid_remove" -> { captureExpected(queue.songs); result = "PENDING"; app.playback.removeQueueItem(-1, queue.revision, done) }
            "p2_sync_qq" -> app.favorites.refresh(Platform.QQ)
            "p2_sync_netease" -> app.favorites.refresh(Platform.NETEASE)
            "p2_sync_kugou" -> app.favorites.refresh(Platform.KUGOU)
            "p2_snapshot" -> Unit
        }
        main.postDelayed({
            val p = app.playback.state.value; val q = app.playback.queueState.value
            val diag = app.getSharedPreferences("p2-validation", Context.MODE_PRIVATE).edit().clear()
                .putString("step", step).putString("result", result).putLong("queueRevision", q.revision)
                .putInt("size", q.songs.size).putInt("index", p.queueIndex).putLong("position", p.positionMs)
                .putBoolean("orderMatches", q.songs.map { it.key } == expected.map { it.key })
                .putBoolean("currentMatches", q.songs.getOrNull(p.queueIndex)?.key == currentKey)
                .putBoolean("sourceMatches", p.sourceKey == sourceKey)
                .putBoolean("positionMatches", p.positionMs == position)
                .putBoolean("playing", p.playing).putBoolean("wanted", p.playWhenReady)
                .putBoolean("buffering", p.buffering).putString("failure", p.failure?.name ?: "NONE")
                .putString("repeat", p.repeatMode.name).putBoolean("shuffle", p.shuffleEnabled)
                .putInt("resolveCount", (app as DebugMusxiApplication).validationSources.resolveCount)
                .putInt("injectedFailures", app.validationSources.injectedCount)
            Platform.entries.forEach { platform ->
                val s = app.favorites.state.value.getValue(platform); val prefix = platform.name.lowercase()
                val check = favoriteChecks[platform]
                diag.putInt("${prefix}Count", s.keys.size).putBoolean("${prefix}Synced", s.synced)
                    .putBoolean("${prefix}Loading", s.loading).putBoolean("${prefix}CanWrite", s.canWrite)
                    .putBoolean("${prefix}Pending", s.pending != null).putString("${prefix}Error", s.error ?: "NONE")
                    .putBoolean("${prefix}CurrentLiked", currentKey?.let { it in s.keys } == true)
                    .putBoolean("${prefix}QueueSaved", app.playbackJournal.state.value.getValue(platform).queue != null)
                    .putBoolean("${prefix}FavoritePrepared", check != null)
                    .putBoolean("${prefix}OriginallyLiked", check?.originallyLiked == true)
                    .putBoolean("${prefix}ToggleSubmitted", check?.toggled == true)
                    .putBoolean("${prefix}RestoreSubmitted", check?.restoreSubmitted == true)
                    .putBoolean("${prefix}ToggledVerified", check != null && s.synced && !s.loading && (check.song.key in s.keys) != check.originallyLiked)
                    .putBoolean("${prefix}RestoredVerified", check != null && s.synced && !s.loading && s.keys == check.keys)
                    .putString("${prefix}Diagnostic", app.getSharedPreferences("p2-favorite-diagnostic", Context.MODE_PRIVATE).getString(prefix, "NONE"))
            }
            diag.apply()
        }, 500)
        return true
    }
}
