package tech.musxi.player

import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.Platform
import tech.musxi.loginpoc.KugouApi
import tech.musxi.loginpoc.KugouLibraryApi
import javax.net.ssl.HttpsURLConnection

/** Explicit, one-shot protocol observation. Entirely absent from production APKs.
 * Never exports credentials, URLs, response values, playlist names or IDs.
 * Reference: MakcRe/KuGouMusicApi 7ac64e1 module/playlist_add.js (existing MIT notice).
 */
class KugouPlaylistCreationProbeDeviceTest {
    @Test fun observeOneAuthorizedEmptyPlaylistCreation() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Needs explicit one-playlist creation authorization",
            args.getString("allowPlatformWrites") == "true" &&
                args.getString("allowKugouPlaylistCreateProbe") == "true")
        val phone = BackendBatch5DeviceTest.PhoneDriver()
        val app = phone.activity.application as MusxiApplication
        val marker = File(app.filesDir, "kugou-create-probe-once-v1.json")
        val reportFile = File(app.filesDir, "kugou-create-probe-report.json")
        val report = JSONObject().put("started", System.currentTimeMillis())
            .put("status", "PREPARING").put("writeRequests", 0)
            .put("songsAdded", false).put("playlistsDeleted", false)
        fun flush() = reportFile.writeText(report.toString(2))
        flush()
        try {
            phone.waitUntil(60_000) {
                app.accounts.states.value.getValue(Platform.KUGOU).phase == AccountPhase.CONNECTED
            }
            val owner = requireNotNull(app.accounts.playbackIdentity(Platform.KUGOU))
            check(owner.verified) { "A verified writable account is required" }
            val ownerHash = MessageDigest.getInstance("SHA-256")
                .digest(owner.owner.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
            fun current(): Boolean = owner == app.accounts.playbackIdentity(Platform.KUGOU) &&
                app.accounts.states.value.getValue(Platform.KUGOU).phase == AccountPhase.CONNECTED
            @Suppress("UNCHECKED_CAST")
            val drivers = (field(app.accounts, "drivers\$delegate") as Lazy<Map<Platform, Any>>).value
            val api = (field(drivers.getValue(Platform.KUGOU), "api\$delegate") as Lazy<*>).value as KugouApi
            val sessionMethod = app.accounts.javaClass.getDeclaredMethod(
                "sessionSnapshot", Platform::class.java, Long::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            val session = sessionMethod.invoke(app.accounts, Platform.KUGOU, owner.generation) as KugouApi.Session
            val library = KugouLibraryApi(api)
            // Existence is the stop condition, even after a lost response or another account login.
            if (marker.exists()) {
                val prior = JSONObject(marker.readText())
                report.put("status", "PREVIOUSLY_ATTEMPTED").put("createdAgain", false)
                    .put("sameOwner", prior.optString("ownerHash") == ownerHash)
                return
            }
            val before = library.writablePlaylists(session, ::current)
            check(current()) { "Account changed before dispatch" }
            report.put("writableCountBefore", before.size)
            // Atomic create-new is the persistent duplicate guard; never delete it to retry.
            check(marker.createNewFile()) { "A previous create intent already exists" }
            marker.writeText(JSONObject().put("ownerHash", ownerHash).put("state", "PENDING")
                .put("started", report.getLong("started")).toString())
            check(current()) { "Account changed before dispatch" }
            report.put("status", "DISPATCHING").put("writeRequests", 1)
            flush()
            val response = createEmptyPlaylistOnce(api, session, ::current)
            report.put("responseSchema", schema(response))
                .put("rootStatusType", scalarType(response.opt("status")))
                .put("rootSuccessStatus", response.opt("status")?.toString() in setOf("1", "200"))
                .put("nonzeroErrorCode", response.has("error_code") && response.opt("error_code")?.toString() != "0")
            // Candidate fields are observations, not accepted protocol aliases or a production ID.
            // Only exact equality against a fresh server-owned list is recorded. No name/diff guess.
            val candidates = candidates(response)
            flush() // Preserve schema before any potentially failing readback.
            check(current()) { "Account changed after dispatch" }
            val after = library.writablePlaylists(session, ::current)
            report.put("writableCountAfter", after.size)
            val observed = JSONArray()
            for ((path, id) in candidates) {
                val matches = after.filter { it.id == id }
                observed.put(JSONObject().put("path", path).put("matchesCurrentWritableList", matches.size == 1)
                    .put("matchedOrdinary", matches.singleOrNull()?.let { !it.favorite && !it.systemDefault } ?: false)
                    .put("alreadyPresentBefore", before.any { it.id == id })
                    .put("matchedEmptyCount", matches.singleOrNull()?.trackCount == 0))
            }
            report.put("candidateIdentityChecks", observed).put("status", "OBSERVED")
            marker.writeText(JSONObject(marker.readText()).put("state", "OBSERVED").toString())
        } catch (failure: Exception) {
            // Do not export exception messages/causes: HTTP libraries can include credential URLs.
            report.put("status", if (marker.exists()) "UNKNOWN_AFTER_INTENT" else "PREWRITE_FAILED")
                .put("errorType", failure.javaClass.simpleName.take(80))
                .put("category", (failure as? ProbeAbort)?.category ?: "check_or_transport")
        } finally {
            report.put("finished", System.currentTimeMillis()).put("intentMarkerExists", marker.exists())
            flush()
        }
    }

    /** Uses the existing signing profile, fixed TLS gateway and one fixed-length POST; no retries. */
    private fun createEmptyPlaylistOnce(api: KugouApi, session: KugouApi.Session, current: () -> Boolean): JSONObject {
        val device = field(api, "device") as KugouApi.Device
        val time = System.currentTimeMillis() / 1000
        fun profile(methodName: String): MutableMap<String, String> {
            val method = KugouApi::class.java.getDeclaredMethod(methodName, KugouApi.Device::class.java,
                Long::class.javaPrimitiveType).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            return method.invoke(null, device, time) as MutableMap<String, String>
        }
        val body = JSONObject().put("userid", session.userId).put("token", session.token)
            .put("total_ver", 0).put("name", "Musxi创建验证").put("type", 0).put("source", 1)
            .put("is_pri", 0).put("list_create_gid", "").put("from_shupinmv", 0).toString()
        val params = profile("defaults").apply {
            put("userid", session.userId); put("token", session.token)
            put("last_time", time.toString()); put("last_area", "gztx")
        }
        val signature = KugouApi::class.java.getDeclaredMethod("androidSignature", Map::class.java,
            String::class.java).apply { isAccessible = true }.invoke(null, params, body) as String
        params["signature"] = signature
        val query = KugouApi::class.java.getDeclaredMethod("query", Map::class.java)
            .apply { isAccessible = true }.invoke(null, params) as String
        val target = URL("https://gateway.kugou.com/cloudlist.service/v5/add_list?$query")
        val connection = target.openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 18_000
            connection.readTimeout = 18_000
            connection.useCaches = false
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            profile("headers").forEach { (key, value) -> connection.setRequestProperty(key, value) }
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            check(current()) { "Account changed before network write" }
            connection.outputStream.use { it.write(bytes) }
            if (connection.responseCode != 200) throw ProbeAbort("http_status")
            if (connection.getHeaderField("ssa-code").let { it != null && it != "" && it != "0" })
                throw ProbeAbort("official_verification_required")
            if (connection.contentLengthLong > 256 * 1024) throw ProbeAbort("response_limit")
            val output = ByteArrayOutputStream()
            val deadline = System.nanoTime() + 30_000_000_000L
            connection.inputStream.use { input ->
                val chunk = ByteArray(4096)
                while (true) {
                    if (!current()) throw ProbeAbort("account_changed")
                    if (Thread.currentThread().isInterrupted) throw ProbeAbort("interrupted")
                    if (System.nanoTime() >= deadline) throw ProbeAbort("response_timeout")
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (count > 256 * 1024 - output.size()) throw ProbeAbort("response_limit")
                    output.write(chunk, 0, count)
                }
            }
            return JSONObject(output.toString("UTF-8"))
        } finally { connection.disconnect() }
    }

    private class ProbeAbort(val category: String) : java.io.IOException()

    private fun field(instance: Any, name: String): Any = requireNotNull(instance.javaClass
        .getDeclaredField(name).apply { isAccessible = true }.get(instance))

    private fun scalarType(value: Any?): String = when (value) {
        null -> "missing"
        JSONObject.NULL -> "null"
        is JSONObject -> "object"
        is JSONArray -> "array"
        is String -> "string"
        is Number -> "number"
        is Boolean -> "boolean"
        else -> "other"
    }

    private fun schema(root: JSONObject): JSONArray {
        val rows = JSONArray()
        // Fixed protocol field names only. Arbitrary response keys can contain user content.
        val keys = listOf("status", "error_code", "errcode", "ret", "data", "info", "list", "lists",
            "listid", "list_id", "id", "global_collection_id", "list_create_gid", "list_create_listid",
            "list_create_userid", "total_ver", "list_ver", "name", "type", "is_edit", "is_def")
        fun visit(value: Any?, path: String, depth: Int) {
            if (rows.length() >= 60) return
            rows.put(JSONObject().put("path", path).put("type", scalarType(value)))
            if (depth >= 3) return
            if (value is JSONObject) keys.filter(value::has).forEach { visit(value.opt(it), "$path.$it", depth + 1) }
            if (value is JSONArray && value.length() > 0) visit(value.opt(0), "$path[]", depth + 1)
        }
        visit(root, "root", 0)
        return rows
    }

    private fun candidates(root: JSONObject): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        fun candidate(raw: Any?, path: String) {
            if (raw !is String && raw !is Number) return
            val text = raw.toString()
            if (text.matches(Regex("[1-9][0-9]{0,18}")) && text.toLongOrNull() != null) result += path to text
        }
        fun inspect(value: JSONObject, path: String) {
            for (key in listOf("listid", "list_id", "id", "list_create_listid")) candidate(value.opt(key), "$path.$key")
        }
        inspect(root, "root")
        candidate(root.opt("data"), "root.data")
        for (container in listOf("data", "info")) root.optJSONObject(container)?.let {
            inspect(it, "root.$container")
            it.optJSONObject("info")?.let { nested -> inspect(nested, "root.$container.info") }
        }
        return result
    }
}
