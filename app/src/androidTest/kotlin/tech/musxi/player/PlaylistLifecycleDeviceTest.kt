package tech.musxi.player

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*
import tech.musxi.neteaseloginpoc.NeteaseApi
import tech.musxi.neteaseloginpoc.NeteaseLibraryApi
import tech.musxi.qqloginpoc.QqLibraryApi
import tech.musxi.qqloginpoc.QqMusicApi

/** Select one method explicitly. One NEW empty playlist per platform, then only its returned ID.
 * Permanent intents prevent retries. Historical markers and pre-existing playlists are untouched.
 * Raw IDs, owners and credentials stay private; export only the fixed report file.
 */
class PlaylistLifecycleDeviceTest {
    @Test fun qqReadOnlyPreflight() = run(Platform.QQ, false)
    @Test fun neteaseReadOnlyPreflight() = run(Platform.NETEASE, false)
    @Test fun qqCreateAndDeleteOnce() = run(Platform.QQ, true)
    @Test fun neteaseCreateAndDeleteOnce() = run(Platform.NETEASE, true)

    private data class Row(val id: String, val name: String, val count: Int,
        val kind: String, val writable: Boolean, val liked: Boolean)

    private fun run(platform: Platform, write: Boolean) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Select one deliberately authorized method",
            args.getString("allowPlaylistLifecycleProbe") == "true")
        if (write) assumeTrue("Needs explicit new-playlist create/delete authorization",
            args.getString("allowPlatformWrites") == "true" &&
                args.getString("allowNewEmptyPlaylistRoundTrip") == "true")
        val name = requireNotNull(args.getString("lifecycleExpectedName"))
        require(name.startsWith("Musxi") && name.length in 6..20 && name.none(Char::isISOControl))
        val phone = BackendBatch5DeviceTest.PhoneDriver()
        val app = phone.activity.application as MusxiApplication
        val suffix = platform.name.lowercase()
        val intent = File(app.filesDir, "playlist-lifecycle-$suffix-once-20261113-v1.json")
        val reportFile = File(app.filesDir, "playlist-lifecycle-$suffix-report.json")
        val report = JSONObject().put("platform", platform.name).put("started", System.currentTimeMillis())
            .put("phase", if (write) "ROUND_TRIP" else "READ_ONLY").put("status", "PREPARING")
            .put("createInvocations", 0).put("deleteInvocations", 0)
            .put("createRequests", 0).put("deleteRequests", 0)
            .put("songsAdded", false).put("otherMutationInvocations", 0)
        fun flush() = persist(reportFile, report)
        var restoreTransport: (() -> Unit)? = null
        fun ensure(ok: Boolean, category: String) { if (!ok) throw LibraryPageFailure(category) }
        fun <T> await(block: ((Result<T>) -> Unit) -> Unit): T {
            val done = CompletableFuture<Result<T>>()
            phone.runOnIdle { block { done.complete(it) } }
            return done.get(180, TimeUnit.SECONDS).getOrThrow()
        }
        flush()
        try {
            phone.waitUntil(60_000) { app.accounts.states.value.getValue(platform).phase == AccountPhase.CONNECTED }
            val owner = requireNotNull(app.accounts.playbackIdentity(platform))
            ensure(owner.verified, "account_unverified")
            fun current() = app.accounts.playbackIdentity(platform) == owner &&
                app.accounts.states.value.getValue(platform).phase == AccountPhase.CONNECTED
            @Suppress("UNCHECKED_CAST")
            val drivers = (field(app.accounts, "drivers\$delegate") as Lazy<Map<Platform, Any>>).value
            val api = field(drivers.getValue(platform), "api")
            val session = app.accounts.javaClass.getDeclaredMethod("sessionSnapshot", Platform::class.java,
                Long::class.javaPrimitiveType).apply { isAccessible = true }.invoke(app.accounts, platform, owner.generation)
            val qq = if (platform == Platform.QQ) QqLibraryApi(api as QqMusicApi, ::current) else null
            val ne = if (platform == Platform.NETEASE) NeteaseLibraryApi(api as NeteaseApi, ::current) else null
            var deleteId: String? = null
            // Observe the actual production transport without adding requests. Abort a second write
            // or any write outside this new-list cycle; capture only response field types for QQ.
            val transportField = api.javaClass.getDeclaredField("transport").apply { isAccessible = true }
            val transport = requireNotNull(transportField.get(api))
            val transportType = transportField.type
            val proxy = Proxy.newProxyInstance(transportType.classLoader, arrayOf(transportType)) { _, method, values ->
                if (method.name != "execute") return@newProxyInstance method.invoke(transport, *(values ?: emptyArray()))
                val request = requireNotNull(values?.single())
                val creating: Boolean
                val deleting: Boolean
                if (platform == Platform.QQ) {
                    val requestBody = field(request, "body") as? String
                    val operation = requestBody?.let { JSONObject(it).optJSONObject("req_0") }
                    val operationName = operation?.optString("method")
                    val route = operation?.let { it.optString("module") + ":" + operationName }
                    creating = operationName == "AddPlaylist"
                    deleting = operationName == "DelPlaylist"
                    if (!creating && !deleting) ensure(route in setOf(
                        "music.musicasset.PlaylistBaseRead:GetPlaylistByUin",
                        "music.musicasset.PlaylistFavRead:CgiGetPlaylistFavInfo",
                        "music.srfDissInfo.DissInfo:CgiGetDiss"), "unexpected_operation")
                    else ensure(operation?.optString("module") == "music.musicasset.PlaylistBaseWrite", "write_scope")
                    if (creating) ensure(requireNotNull(operation).getJSONObject("param").getString("dirName") == name, "write_scope")
                    if (deleting) ensure(deleteId != null && requireNotNull(operation).getJSONObject("param").getInt("dirId") ==
                        deleteId!!.substringAfter('_').toInt(), "write_scope")
                } else {
                    val endpoint = field(request, "endpoint") as String
                    creating = endpoint.substringBefore('?').endsWith("/playlist/create")
                    deleting = endpoint.substringBefore('?').endsWith("/playlist/remove")
                    if (!creating && !deleting) ensure(endpoint in setOf(
                        "https://music.163.com/weapi/user/playlist",
                        "https://interfacepc.music.163.com/eapi/v6/playlist/detail"), "unexpected_operation")
                    if (deleting) ensure(deleteId != null, "write_scope")
                }
                if (creating || deleting) {
                    ensure(write && current(), "write_scope")
                    val key = if (creating) "createRequests" else "deleteRequests"
                    ensure(report.optInt(key) == 0, "write_already_attempted")
                    report.put(key, 1); flush()
                }
                val response = try { method.apply { isAccessible = true }.invoke(transport, *requireNotNull(values)) }
                catch (failure: InvocationTargetException) { throw failure.targetException }
                if (creating && platform == Platform.QQ && response != null) {
                    val raw = field(response, "body") as ByteArray
                    val body = JSONObject(String(raw, StandardCharsets.UTF_8))
                    report.put("createResponseSchema", schema(body)); flush()
                }
                response
            }
            transportField.set(api, proxy)
            restoreTransport = { transportField.set(api, transport) }
            fun snapshot(): List<Row> {
                val result = mutableListOf<Row>()
                val seen = mutableSetOf<String>()
                var offset = 0
                var total: Int? = null
                val deadline = System.nanoTime() + 90_000_000_000L
                while (true) {
                    ensure(current() && System.nanoTime() < deadline, "account_changed_or_timeout")
                    val rows: List<Row>
                    val next: Int
                    val count: Int
                    if (qq != null) {
                        val page = qq.playlists(session as QqMusicApi.Session, offset, 100)
                        rows = page.items.map { Row(it.id, it.name, it.trackCount, it.kind.name, it.writable, it.liked) }
                        next = page.nextOffset; count = page.total
                    } else {
                        val page = requireNotNull(ne).playlists(session as NeteaseApi.Session, offset, 100)
                        rows = page.items.map { Row(it.id, it.name, it.trackCount, it.kind.name, it.writable, it.liked) }
                        next = page.nextOffset; count = page.total
                    }
                    ensure(current(), "account_changed")
                    if (count >= 0) {
                        ensure(count <= 20_000 && (total == null || total == count), "incomplete")
                        total = count
                    } else ensure(total == null, "incomplete")
                    rows.forEach { ensure(seen.add(it.id) && seen.size <= 20_000, "incomplete"); result += it }
                    if (next < 0) break
                    ensure(rows.isNotEmpty() && next > offset && next <= 20_000, "incomplete")
                    offset = next
                }
                ensure(total == null || result.size == total, "incomplete")
                return result
            }
            val before = snapshot()
            report.put("accountListCountBefore", before.size).put("accountVerified", true)
            val legacy = File(app.filesDir, "playlist-created-qq-once.json")
            if (platform == Platform.QQ) {
                report.put("legacyCreateMarkerExists", legacy.exists())
                if (legacy.exists()) {
                    val saved = JSONObject(legacy.readText())
                    report.put("legacyCreateHasExactId", saved.has("playlist"))
                        .put("legacyCreateSameOwner", saved.optString("owner") == owner.owner)
                        .put("legacyMatchingNameCount", before.count { it.name == saved.optString("name", "Musxi验收") })
                }
            }
            if (intent.exists()) {
                val saved = JSONObject(intent.readText())
                report.put("status", "PREVIOUSLY_ATTEMPTED").put("savedState", saved.optString("state"))
                    .put("sameOwner", saved.optString("owner") == owner.owner)
                if (saved.has("playlist") && saved.optString("owner") == owner.owner)
                    report.put("savedTargetPresent", before.any { it.id == saved.getString("playlist") })
                return
            }
            ensure(before.none { it.name == name }, "test_name_already_exists")
            ensure(app.playlists.capabilities(platform).create && app.playlists.capabilities(platform).delete, "unsupported")
            if (!write) { report.put("status", "READY"); return }
            ensure(current(), "account_changed")
            ensure(intent.createNewFile(), "previous_intent")
            persist(intent, JSONObject().put("owner", owner.owner).put("name", name)
                .put("started", report.getLong("started")).put("state", "CREATE_INTENT")
                .put("beforeFingerprint", fingerprint(before)))
            report.put("status", "CREATING").put("createInvocations", 1); flush()
            val created = await<EditablePlaylist> { app.playlists.createPlaylist(platform, name, it) }
            ensure(current(), "account_changed")
            ensure(created.accountGeneration == owner.generation && !created.favorite &&
                created.playlist.key.platform == platform && created.playlist.kind == PlaylistKind.CREATED &&
                before.none { it.id == created.playlist.key.id }, "identity")
            val targetId = created.playlist.key.id
            deleteId = targetId
            persist(intent, JSONObject(intent.readText()).put("playlist", targetId).put("state", "CREATED"))
            val afterCreate = snapshot()
            val row = afterCreate.singleOrNull { it.id == targetId }
            ensure(row != null && row.name == name && row.kind == "CREATED" && row.writable && !row.liked && row.count == 0,
                "created_target_not_empty_owned")
            val othersSame = fingerprint(before) == fingerprint(afterCreate.filter { it.id != targetId })
            ensure(othersSame && afterCreate.size == before.size + 1, "other_rows_changed")
            val ids = await<List<MediaKey>> { app.playlists.playlistSongIds(created, it) }
            ensure(ids.isEmpty(), "created_target_not_empty")
            val info = await<PlaylistManagementInfo> { app.playlists.managementInfo(created.playlist.key, it) }
            ensure(info.canDelete && info.target.playlist.key == created.playlist.key &&
                info.target.accountGeneration == owner.generation && info.target.playlist.trackCount == 0, "permission")
            report.put("creationConfirmed", true).put("createdTargetEmpty", true)
                .put("creationMatchedExactReturnedId", true).put("otherRowsUnchangedAfterCreate", othersSame)
                .put("accountListCountAfterCreate", afterCreate.size).put("managementCanDelete", true)
            // Re-read immediately before deletion; no existing or changed/nonempty target is eligible.
            ensure(fingerprint(snapshot()) == fingerprint(afterCreate) && current(), "target_or_account_changed")
            persist(intent, JSONObject(intent.readText()).put("state", "DELETE_INTENT"))
            report.put("status", "DELETING").put("deleteInvocations", 1); flush()
            var deletion: PlaylistDeletionResult? = null
            try { deletion = await { app.playlists.deletePlaylist(info.target, it) } }
            catch (failure: Exception) { report.put("deleteError", category(failure)) }
            deletion?.let { report.put("productionDeleteStatus", it.status.name)
                .put("productionConfirmedAbsent", it.confirmedAbsent ?: JSONObject.NULL)
                .put("productionDeleteError", it.error ?: JSONObject.NULL) }
            ensure(current(), "account_changed")
            val afterDelete = snapshot()
            val absent = afterDelete.none { it.id == targetId }
            val unchanged = fingerprint(before) == fingerprint(afterDelete)
            report.put("accountListCountAfterDelete", afterDelete.size).put("confirmedAbsent", absent)
                .put("allOriginalRowsUnchanged", unchanged)
            val status = when {
                !absent -> "NOT_DELETED"
                !unchanged -> "ABSENT_WITH_OTHER_CHANGES"
                deletion?.confirmedAbsent == true && deletion.status == PlaylistChangeStatus.APPLIED -> "PASSED"
                else -> "OBSERVED_ABSENT_PRODUCTION_UNCONFIRMED"
            }
            report.put("status", status)
            persist(intent, JSONObject(intent.readText()).put("state", status))
        } catch (failure: Exception) {
            report.put("status", if (intent.exists()) "STOPPED_AFTER_INTENT" else "PREWRITE_FAILED")
                .put("error", category(failure))
        } finally {
            try { restoreTransport?.invoke() }
            catch (failure: Exception) {
                report.put("transportRestoreError", failure.javaClass.simpleName.take(80))
                    .put("status", "REPORT_CLEANUP_FAILED")
            }
            report.put("finished", System.currentTimeMillis()).put("intentMarkerExists", intent.exists())
            flush()
        }
    }

    private fun category(failure: Exception) = when (failure) {
        is LibraryPageFailure -> failure.category
        is QqMusicApi.ApiException -> failure.code
        is NeteaseApi.ApiException -> failure.code
        else -> failure.javaClass.simpleName.take(80)
    }
    private fun fingerprint(rows: List<Row>): String {
        val value = JSONArray()
        rows.sortedBy { it.id }.forEach { value.put(JSONArray().put(it.id).put(it.name).put(it.count)
            .put(it.kind).put(it.writable).put(it.liked)) }
        return MessageDigest.getInstance("SHA-256").digest(value.toString().toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
    }
    private fun persist(file: File, data: JSONObject) = FileOutputStream(file).use {
        it.write(data.toString(2).toByteArray(StandardCharsets.UTF_8)); it.fd.sync()
    }
    private fun field(instance: Any, name: String): Any = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance) ?: error("Missing test access")

    private fun schema(root: JSONObject): JSONArray {
        val output = JSONArray()
        fun visit(value: Any?, path: String, depth: Int) {
            if (output.length() >= 40) return
            val type = when (value) {
                null -> "missing"; JSONObject.NULL -> "null"; is JSONObject -> "object"
                is JSONArray -> "array"; is Number -> "number"; is String -> "string"
                is Boolean -> "boolean"; else -> "other"
            }
            output.put(JSONObject().put("path", path).put("type", type))
            if (value is JSONObject && depth < 4)
                listOf("code", "req_0", "data", "result", "retCode", "tid", "dirId", "dirid", "dirName")
                    .filter(value::has).forEach { visit(value.opt(it), "$path.$it", depth + 1) }
        }
        visit(root, "root", 0)
        return output
    }
}
