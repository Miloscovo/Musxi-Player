package tech.musxi.player

import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.Platform
import tech.musxi.loginpoc.KugouApi
import tech.musxi.loginpoc.KugouLibraryApi

/** Test-APK-only observation of the pinned playlist_del.js request. Production stays disabled.
 * Prepare is read-only; deletion needs its exact private target and exported random confirmation.
 * A non-empty target additionally requires explicit authorization and an unchanged track snapshot.
 * Never invoke the whole class or remove an intent marker to try another request variant.
 */
class KugouPlaylistDeletionProbeDeviceTest {
    @Test fun prepareEmptyOwnedPlaylistDeletion() = runProbe(write = false)
    @Test fun deletePreparedEmptyPlaylistOnce() = runProbe(write = true)
    @Test fun prepareOwnedPlaylistDeletion() = runProbe(write = false)
    @Test fun deletePreparedPlaylistOnce() = runProbe(write = true)

    private fun runProbe(write: Boolean) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Needs a deliberately selected probe method",
            args.getString("allowKugouPlaylistDeleteProbe") == "true")
        if (write) assumeTrue("Needs explicit authorization for exactly one prepared empty playlist",
            args.getString("allowPlatformWrites") == "true")
        val expectedName = requireNotNull(args.getString("deleteProbeExpectedName"))
        require(expectedName.isNotBlank() && expectedName.length <= 80)
        val allowNonEmpty = args.getString("allowNonEmptyKugouDelete") == "true"
        val phone = BackendBatch5DeviceTest.PhoneDriver()
        val app = phone.activity.application as MusxiApplication
        val intent = File(app.filesDir, "kugou-delete-probe-once-v1.json")
        val prepared = File(app.filesDir, "kugou-delete-probe-target-v1.json")
        val reportFile = File(app.filesDir, "kugou-delete-probe-report.json")
        val report = JSONObject().put("started", System.currentTimeMillis())
            .put("phase", if (write) "DELETE" else "PREPARE")
            .put("status", "PREPARING").put("writeRequests", 0)
            .put("songsAdded", false).put("playlistsCreated", false)
            .put("otherMutationRequests", 0)
        fun flush() = persist(reportFile, report)
        flush()
        try {
            if (intent.exists()) {
                report.put("status", "PREVIOUSLY_ATTEMPTED")
                return
            }
            phone.waitUntil(60_000) {
                app.accounts.states.value.getValue(Platform.KUGOU).phase == AccountPhase.CONNECTED
            }
            val owner = requireNotNull(app.accounts.playbackIdentity(Platform.KUGOU))
            check(owner.verified)
            fun current() = owner == app.accounts.playbackIdentity(Platform.KUGOU) &&
                app.accounts.states.value.getValue(Platform.KUGOU).phase == AccountPhase.CONNECTED
            @Suppress("UNCHECKED_CAST")
            val drivers = (field(app.accounts, "drivers\$delegate") as Lazy<Map<Platform, Any>>).value
            val api = (field(drivers.getValue(Platform.KUGOU), "api\$delegate") as Lazy<*>).value as KugouApi
            val sessionMethod = app.accounts.javaClass.getDeclaredMethod(
                "sessionSnapshot", Platform::class.java, Long::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            val session = sessionMethod.invoke(app.accounts, Platform.KUGOU, owner.generation) as KugouApi.Session
            val library = KugouLibraryApi(api)
            val before = snapshot(library, session, ::current)
            report.put("accountListCountBefore", before.size)
            if (!write) {
                val matches = before.filter { it.name == expectedName }
                report.put("matchingNameCount", matches.size)
                if (matches.size != 1) throw ProbeAbort("target_not_unique")
                val target = matches.single()
                requireOrdinary(target)
                val tracks = library.playlistTrackIds(session, target.id, ::current)
                if (tracks.size != target.trackCount) throw ProbeAbort("target_changed")
                if (tracks.isNotEmpty() && !allowNonEmpty) throw ProbeAbort("target_contains_tracks")
                val prior = if (prepared.exists()) JSONObject(prepared.readText()) else null
                if (prior != null && (prior.optString("owner") != owner.owner ||
                        prior.optString("targetId") != target.id || prior.optString("name") != expectedName))
                    throw ProbeAbort("prepared_target_changed")
                val confirmation = prior?.getString("confirmation") ?: UUID.randomUUID().toString()
                persist(prepared, JSONObject().put("owner", owner.owner).put("targetId", target.id)
                    .put("name", expectedName).put("confirmation", confirmation)
                    .put("trackCount", tracks.size).put("trackFingerprint", trackFingerprint(tracks))
                    .put("nonEmptyAuthorized", allowNonEmpty)
                    .put("preparedAt", System.currentTimeMillis()))
                report.put("status", "READY").put("targetOrdinaryOwned", true)
                    .put("targetEmpty", tracks.isEmpty()).put("targetTrackCount", tracks.size)
                    .put("nonEmptyAuthorized", allowNonEmpty).put("confirmationToken", confirmation)
                return
            }
            if (!prepared.isFile) throw ProbeAbort("not_prepared")
            val selection = JSONObject(prepared.readText())
            if (selection.getString("owner") != owner.owner || selection.getString("name") != expectedName ||
                args.getString("deleteProbeConfirmation") != selection.getString("confirmation"))
                throw ProbeAbort("confirmation_mismatch")
            if (System.currentTimeMillis() - selection.getLong("preparedAt") !in 0..30 * 60_000L)
                throw ProbeAbort("preparation_expired")
            val targetId = selection.getString("targetId")
            val target = before.singleOrNull { it.id == targetId } ?: throw ProbeAbort("target_absent")
            if (target.name != expectedName) throw ProbeAbort("target_changed")
            requireOrdinary(target)
            val tracks = library.playlistTrackIds(session, targetId, ::current)
            if (tracks.size != target.trackCount || tracks.size != selection.getInt("trackCount") ||
                trackFingerprint(tracks) != selection.getString("trackFingerprint"))
                throw ProbeAbort("target_tracks_changed")
            if (tracks.isNotEmpty() && (!allowNonEmpty || !selection.optBoolean("nonEmptyAuthorized")))
                throw ProbeAbort("target_contains_tracks")
            check(current())
            // Reserve permanently before opening any mutation connection, including a lost response.
            if (!intent.createNewFile()) throw ProbeAbort("previous_intent")
            persist(intent, JSONObject().put("owner", owner.owner).put("targetId", targetId)
                .put("started", report.getLong("started")).put("state", "RESERVED"))
            report.put("status", "DISPATCHING").put("targetOrdinaryOwned", true)
                .put("targetEmpty", tracks.isEmpty()).put("targetTrackCount", tracks.size)
                .put("nonEmptyAuthorized", allowNonEmpty).put("trackSnapshotUnchanged", true)
            flush()
            var dispatched = false
            try {
                val response = deleteOnce(api, session, targetId, ::current) {
                    dispatched = true
                    report.put("writeRequests", 1)
                    persist(intent, JSONObject(intent.readText()).put("state", "DISPATCHING"))
                    flush()
                }
                report.put("rootSuccessStatus", response.opt("status")?.toString() in setOf("1", "200"))
                    .put("nonzeroErrorCode", response.has("error_code") && response.opt("error_code")?.toString() != "0")
                    .put("responseSchema", schema(response))
            } catch (failure: Exception) {
                report.put("writeErrorType", failure.javaClass.simpleName.take(80))
                    .put("writeErrorCategory", (failure as? ProbeAbort)?.category ?: "check_or_transport")
            }
            flush()
            if (!current()) throw ProbeAbort("account_changed_after_intent")
            // Reconcile even if the POST response was lost. No second POST, no other type value.
            val after = snapshot(library, session, ::current)
            val absent = after.none { it.id == targetId }
            val othersUnchanged = fingerprint(before.filter { it.id != targetId }) ==
                fingerprint(after.filter { it.id != targetId })
            report.put("accountListCountAfter", after.size).put("confirmedAbsent", absent)
                .put("otherRowsUnchanged", othersUnchanged)
                .put("exactlyOneListRemoved", absent && othersUnchanged && before.size == after.size + 1)
                .put("status", when {
                    !dispatched -> "NO_WRITE_ATTEMPTED"
                    !absent -> "NOT_DELETED"
                    othersUnchanged && before.size == after.size + 1 -> "OBSERVED_DELETED"
                    else -> "ABSENT_WITH_OTHER_CHANGES"
                })
            persist(intent, JSONObject(intent.readText()).put("state", report.getString("status")))
        } catch (failure: Exception) {
            report.put("status", if (intent.exists()) "UNKNOWN_AFTER_INTENT" else "PREWRITE_FAILED")
                .put("errorType", failure.javaClass.simpleName.take(80))
                .put("category", (failure as? ProbeAbort)?.category ?: "check_or_transport")
        } finally {
            report.put("finished", System.currentTimeMillis()).put("intentMarkerExists", intent.exists())
            flush()
        }
    }

    private fun snapshot(library: KugouLibraryApi, session: KugouApi.Session,
        current: () -> Boolean): List<KugouLibraryApi.Playlist> {
        val deadline = System.nanoTime() + 60_000_000_000L
        val all = mutableListOf<KugouLibraryApi.Playlist>()
        val ids = mutableSetOf<String>()
        var offset = 0
        var total: Int? = null
        while (true) {
            if (!current() || System.nanoTime() >= deadline) throw ProbeAbort("listing_interrupted")
            val page = library.playlists(session, offset, 100)
            if (!current()) throw ProbeAbort("account_changed")
            if (page.total >= 0) {
                if (page.total > 20_000 || (total != null && total != page.total))
                    throw ProbeAbort("incomplete_listing")
                total = page.total
            } else if (total != null) throw ProbeAbort("listing_total_changed")
            for (row in page.items) {
                if (!ids.add(row.id) || ids.size > 20_000 || (total != null && ids.size > total))
                    throw ProbeAbort("incomplete_listing")
                all += row
            }
            if (page.nextOffset < 0) break
            // Kugou uses page numbers. An unknown total requires one extra page even after a
            // short page; the adapter advances the server window by 100, never by display size.
            if (page.items.isEmpty() || page.nextOffset != offset + 100 || page.nextOffset > 20_000)
                throw ProbeAbort("incomplete_listing")
            offset = page.nextOffset
        }
        if ((total != null && all.size != total) || !current()) throw ProbeAbort("incomplete_listing")
        return all.toList()
    }

    private fun requireOrdinary(row: KugouLibraryApi.Playlist) {
        if (!row.editable || row.favorite || row.systemDefault ||
            row.kind != KugouLibraryApi.Playlist.Kind.CREATED || row.trackCount < 0 ||
            !row.id.matches(Regex("[1-9][0-9]{0,15}")) || row.id.toLong() > 9_007_199_254_740_991L)
            throw ProbeAbort("target_not_ordinary_owned")
    }

    private fun deleteOnce(api: KugouApi, session: KugouApi.Session, id: String,
        current: () -> Boolean, beforeDispatch: () -> Unit): JSONObject {
        val device = field(api, "device") as KugouApi.Device
        val time = System.currentTimeMillis() / 1000
        val cryptoClass = Class.forName("tech.musxi.loginpoc.KugouCloudProtocol")
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
        val random = SecureRandom()
        val aes = CharArray(6) { alphabet[random.nextInt(alphabet.length)] }.concatToString()
        val crypto = cryptoClass.getDeclaredConstructor(String::class.java).apply { isAccessible = true }.newInstance(aes)
        fun call(method: String, types: Array<Class<*>>, vararg values: Any): Any =
            requireNotNull(cryptoClass.getDeclaredMethod(method, *types).apply { isAccessible = true }.invoke(crypto, *values))
        val clear = JSONObject().put("listid", id.toLong()).put("total_ver", 0).put("type", 1).toString()
        // playlist_del.js uses playlistAesEncrypt().str (Base64 text), unlike modify_list binary CBC.
        val body = Base64.getEncoder().encodeToString(call("encrypt", arrayOf(String::class.java), clear) as ByteArray)
        fun profile(name: String): MutableMap<String, String> {
            @Suppress("UNCHECKED_CAST")
            return KugouApi::class.java.getDeclaredMethod(name, KugouApi.Device::class.java,
                Long::class.javaPrimitiveType).apply { isAccessible = true }.invoke(null, device, time) as MutableMap<String, String>
        }
        val params = profile("defaults").apply {
            put("userid", session.userId); put("token", session.token)
            put("last_area", "gztx"); put("last_time", time.toString())
            put("key", cryptoClass.getDeclaredMethod("signature", Long::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(null, time) as String)
            put("p", call("portrait", arrayOf(KugouApi.Session::class.java), session) as String)
        }
        params["signature"] = KugouApi::class.java.getDeclaredMethod("androidSignature", Map::class.java,
            String::class.java).apply { isAccessible = true }.invoke(null, params, body) as String
        val query = KugouApi::class.java.getDeclaredMethod("query", Map::class.java)
            .apply { isAccessible = true }.invoke(null, params) as String
        val connection = URL("https://gateway.kugou.com/v2/delete_list?$query").openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 18_000; connection.readTimeout = 18_000
            connection.useCaches = false; connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            profile("headers").forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.setRequestProperty("x-router", "cloudlist.service.kugou.com")
            val bytes = body.toByteArray(StandardCharsets.US_ASCII)
            connection.setFixedLengthStreamingMode(bytes.size)
            if (!current()) throw ProbeAbort("account_changed")
            beforeDispatch()
            if (!current()) throw ProbeAbort("account_changed")
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
                    if (!current() || Thread.currentThread().isInterrupted) throw ProbeAbort("interrupted")
                    if (System.nanoTime() >= deadline) throw ProbeAbort("response_timeout")
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (count > 256 * 1024 - output.size()) throw ProbeAbort("response_limit")
                    output.write(chunk, 0, count)
                }
            }
            val decoded = call("decode", arrayOf(ByteArray::class.java), output.toByteArray()) as String
            return JSONObject(decoded)
        } finally { connection.disconnect() }
    }

    private fun fingerprint(rows: List<KugouLibraryApi.Playlist>): String {
        val value = JSONArray()
        rows.sortedBy { it.id }.forEach { row ->
            value.put(JSONArray().put(row.id).put(row.name).put(row.trackCount)
                .put(row.editable).put(row.favorite).put(row.systemDefault).put(row.kind.name))
        }
        return MessageDigest.getInstance("SHA-256").digest(value.toString().toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun trackFingerprint(tracks: List<String>): String =
        MessageDigest.getInstance("SHA-256").digest(JSONArray(tracks).toString().toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun schema(root: JSONObject): JSONArray {
        val result = JSONArray()
        fun visit(value: Any?, path: String, depth: Int) {
            if (result.length() >= 30) return
            val kind = when (value) {
                null -> "missing"; JSONObject.NULL -> "null"; is JSONObject -> "object"
                is JSONArray -> "array"; is Number -> "number"; is Boolean -> "boolean"
                is String -> "string"; else -> "other"
            }
            result.put(JSONObject().put("path", path).put("type", kind))
            if (value is JSONObject && depth < 2)
                listOf("status", "error_code", "data", "info", "listid", "total_ver", "type")
                    .filter(value::has).forEach { visit(value.opt(it), "$path.$it", depth + 1) }
        }
        visit(root, "root", 0)
        return result
    }

    private fun persist(file: File, value: JSONObject) {
        FileOutputStream(file).use { output ->
            output.write(value.toString(2).toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
    }
    private fun field(instance: Any, name: String): Any = requireNotNull(instance.javaClass
        .getDeclaredField(name).apply { isAccessible = true }.get(instance))
    private class ProbeAbort(val category: String) : java.io.IOException()
}
