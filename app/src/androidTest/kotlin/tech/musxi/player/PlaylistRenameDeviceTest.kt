package tech.musxi.player

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AssumptionViolatedException
import org.junit.Assume.assumeTrue
import org.junit.Test
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.Platform
import tech.musxi.core.library.EditablePlaylist
import tech.musxi.core.library.LibraryPageFailure
import tech.musxi.core.library.MediaKey

/**
 * Select this class explicitly. One existing ordinary list is renamed to a short temporary name and
 * restored, so the recovery record is written to private app files before the first server write.
 */
class PlaylistRenameDeviceTest {
    @Test fun qqOrdinaryPlaylistRename() = roundTrip(Platform.QQ)
    @Test fun kugouOrdinaryPlaylistRename() = roundTrip(Platform.KUGOU)
    @Test fun neteaseOrdinaryPlaylistRename() = roundTrip(Platform.NETEASE)

    private fun roundTrip(platform: Platform) {
        val arguments = InstrumentationRegistry.getArguments()
        val started = System.currentTimeMillis()
        val files = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        val reportFile = File(files, "playlist-rename-${platform.name.lowercase()}.json")
        val pending = File(files, "playlist-rename-recovery-${platform.name.lowercase()}.json")
        val report = JSONObject().put("platform", platform.name).put("started", started)
        if (arguments.getString("allowPlatformWrites") != "true" ||
            arguments.getString("allowPlaylistRename") != "true") {
            report.put("status", "SKIPPED").put("error", "authorization")
                .put("recoveryPending", pending.exists()).put("finished", System.currentTimeMillis())
            reportFile.writeText(report.toString(2))
            assumeTrue("Requires explicit real-platform write and rename authorization", false)
        }
        val phone = BackendBatch5DeviceTest.PhoneDriver()
        val app = phone.activity.application as MusxiApplication
        fun <T> await(block: ((Result<T>) -> Unit) -> Unit): T {
            val done = CompletableFuture<Result<T>>()
            phone.runOnIdle { block { done.complete(it) } }
            val settled = try {
                done.get(180, TimeUnit.SECONDS)
            } catch (timeout: TimeoutException) {
                throw LibraryPageFailure("timeout")
            } catch (failed: ExecutionException) {
                throw failed.cause ?: failed
            }
            return settled.getOrElse { failure -> throw failure }
        }
        fun targets() = await<List<EditablePlaylist>> { app.playlists.listWritable(platform, it) }
        fun ids(target: EditablePlaylist) = await<List<MediaKey>> { app.playlists.playlistSongIds(target, it) }
            .map { it.id }
        fun ordinary(id: String) = targets().singleOrNull { !it.favorite && it.playlist.key.id == id }
        fun strings(source: JSONObject, key: String) = source.getJSONArray(key)
            .let { rows -> List(rows.length()) { rows.getString(it) } }
        fun ensure(condition: Boolean, category: String) {
            if (!condition) throw LibraryPageFailure(category)
        }
        fun classify(error: Throwable): String =
            (error as? LibraryPageFailure)?.category ?: error.javaClass.simpleName

        fun cleanup() {
            if (!pending.exists()) return
            val saved = JSONObject(pending.readText())
            ensure(app.accounts.playbackIdentity(platform)?.owner == saved.getString("owner"), "account_changed")
            val playlist = saved.getString("playlist")
            val originalName = saved.getString("originalName")
            val tempName = saved.getString("tempName")
            val originalIds = strings(saved, "originalIds")
            val favoriteIds = strings(saved, "favoriteIds")
            val current = ordinary(playlist) ?: throw LibraryPageFailure("incomplete")
            // Only this test's own temporary name is renamed back; another client's name is never overwritten.
            when (current.playlist.title) {
                originalName -> report.put("restoreRenamed", false)
                tempName -> {
                    await<Unit> { app.playlists.renamePlaylist(current, originalName, it) }
                    report.put("restoreRenamed", true)
                }
                else -> throw LibraryPageFailure("conflict")
            }
            val restored = ordinary(playlist) ?: throw LibraryPageFailure("incomplete")
            ensure(restored.playlist.title == originalName, "not_confirmed")
            ensure(ids(restored) == originalIds, "content_changed")
            val favorite = targets().firstOrNull { it.favorite } ?: throw LibraryPageFailure("incomplete")
            ensure(ids(favorite) == favoriteIds, "favorites_changed")
            report.put("restored", true).put("restoredCount", originalIds.size)
            ensure(pending.renameTo(File(files,
                "playlist-rename-restored-${platform.name.lowercase()}-${saved.getLong("started")}.json")),
                "not_confirmed")
        }

        var status = "FAILED"
        var errorCategory: String? = null
        var failure: Throwable? = null
        try {
            try {
                phone.waitUntil(60_000) { app.accounts.states.value.getValue(platform).phase == AccountPhase.CONNECTED }
            } catch (timeout: IllegalStateException) {
                throw LibraryPageFailure("timeout")
            }
            val identity = app.accounts.playbackIdentity(platform) ?: throw LibraryPageFailure("account_unverified")
            ensure(identity.verified, "account_unverified")
            cleanup() // An interrupted opted-in run is restored before another rename can start.
            val available = targets()
            // The original name is written back verbatim, so it must still satisfy the write rules.
            // Kugou's first non-favorite row may be its fixed-name default list. The backend
            // refuses that metadata write; prefer the later ordinary candidate for this test.
            val candidates = if (platform == Platform.KUGOU) available.asReversed() else available
            val target = candidates.firstOrNull {
                !it.favorite && it.playlist.title.isNotBlank() && it.playlist.title.trim() == it.playlist.title &&
                    (platform != Platform.QQ || it.playlist.title.length <= 20) &&
                    (arguments.getString("requireNonEmptyPlaylist") != "true" || it.playlist.trackCount > 0) &&
                    it.playlist.title.codePointCount(0, it.playlist.title.length) <= 80 &&
                    it.playlist.title.none(Char::isISOControl)
            }
            if (target == null) {
                status = "SKIPPED"
                errorCategory = if (arguments.getString("requireNonEmptyPlaylist") == "true")
                    "no_nonempty_ordinary_playlist" else "no_ordinary_playlist"
                throw AssumptionViolatedException("No existing ordinary writable playlist for $platform")
            }
            val favorite = available.firstOrNull { it.favorite } ?: throw LibraryPageFailure("incomplete")
            val originalName = target.playlist.title
            val originalIds = ids(target)
            val favoriteIds = ids(favorite)
            val tempName = "Musxi验收" + (started % 1_000_000L).toString().padStart(6, '0')
            report.put("baselineCount", originalIds.size).put("favoriteCount", favoriteIds.size)
            // Persist intent before dispatch: a lost response can still follow a successful rename.
            pending.writeText(JSONObject().put("owner", identity.owner).put("started", started)
                .put("playlist", target.playlist.key.id).put("originalName", originalName)
                .put("tempName", tempName).put("originalIds", JSONArray(originalIds))
                .put("favoriteIds", JSONArray(favoriteIds)).toString())
            await<Unit> { app.playlists.renamePlaylist(target, tempName, it) }
            val renamed = ordinary(target.playlist.key.id) ?: throw LibraryPageFailure("incomplete")
            ensure(renamed.playlist.title == tempName, "not_confirmed")
            ensure(ids(renamed) == originalIds, "content_changed")
            ensure(ids(favorite) == favoriteIds, "favorites_changed")
            report.put("renameConfirmed", true).put("contentUnchanged", true).put("favoritesUnchanged", true)
            cleanup()
            status = "PASSED"
        } catch (thrown: Throwable) {
            failure = thrown
            status = if (thrown is AssumptionViolatedException) "SKIPPED" else "FAILED"
            if (status == "FAILED" && errorCategory == null) errorCategory = classify(thrown)
        } finally {
            val cleanupFailure = try {
                cleanup()
                null
            } catch (thrown: Throwable) {
                thrown
            }
            if (cleanupFailure != null) {
                status = "FAILED"
                report.put("cleanupError", classify(cleanupFailure))
                if (errorCategory == null) errorCategory = classify(cleanupFailure)
                if (failure == null) failure = cleanupFailure
            }
            report.put("status", status).put("recoveryPending", pending.exists())
                .put("finished", System.currentTimeMillis())
            if (errorCategory != null) report.put("error", errorCategory)
            reportFile.writeText(report.toString(2))
        }
        failure?.let { throw it }
    }
}
